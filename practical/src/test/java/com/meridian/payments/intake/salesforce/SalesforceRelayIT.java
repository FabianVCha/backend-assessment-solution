package com.meridian.payments.intake.salesforce;

import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.canonical.CanonicalEvent;
import com.meridian.payments.intake.store.Acceptance;
import com.meridian.payments.intake.store.ClaimLostException;
import com.meridian.payments.intake.store.Database;
import com.meridian.payments.intake.store.Destination;
import com.meridian.payments.intake.store.PendingJob;
import com.meridian.payments.intake.store.PostgresIntakeStore;
import com.meridian.payments.intake.store.PostgresSalesforceStore;
import com.meridian.payments.intake.store.RetrySchedule;
import com.meridian.payments.intake.store.SalesforceStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Salesforce lane against a real database and a scripted destination.
 *
 * <p>Each case is one row of the fault matrix: what the destination answered, and what the relay must do
 * with it. The assertion is always two things — the job's state and how many times the destination was
 * called — because a retry that is not counted and a skip that is not recorded look identical from the
 * outside.
 */
class SalesforceRelayIT {

    private static final String URL = System.getenv("INTAKE_IT_JDBC_URL");
    private static final String USER = environment("INTAKE_IT_DB_USER", "intake");
    private static final String PASSWORD = environment("INTAKE_IT_DB_PASSWORD", "");

    private static Database database;
    private static PostgresIntakeStore intake;
    private static PostgresSalesforceStore store;

    @BeforeAll
    static void open() {
        assumeTrue(URL != null && !URL.isBlank(),
                "INTAKE_IT_JDBC_URL is not set: the PostgreSQL tests need a database");
        database = Database.open(URL, USER, PASSWORD);
        intake = new PostgresIntakeStore(database);
        store = new PostgresSalesforceStore(database);
    }

    @AfterAll
    static void close() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void emptyTheTables() throws SQLException {
        execute("DELETE FROM outbox_job");
        execute("DELETE FROM payment_event");
        execute("DELETE FROM payment_state");
    }

    @Test
    void anAppliedWriteIsRecordedOnTheJobAndOnTheProjection() throws SQLException {
        accept("evt-sf-1", "PAY-1", "CAPTURED", Instant.parse("2026-12-01T10:00:00Z"));
        SimulatedSalesforce salesforce = new SimulatedSalesforce();

        int applied = relay(salesforce).drain();

        assertEquals(1, applied);
        assertEquals(1, salesforce.writeCount());
        SalesforceClient.PaymentWrite write = salesforce.writes().get(0);
        assertEquals("QZ-MX-PAY-1", write.paymentKey());
        assertEquals("CAPTURED", write.status());
        assertEquals(125_000L, write.amountMinor());
        assertEquals("state=PUBLISHED, code=APPLIED", jobState("evt-sf-1"));
        assertEquals("CAPTURED", remoteAppliedStatus("PAY-1"));
    }

    @Test
    void anInvalidFieldIsTerminalAndIsNotSentAgain() throws SQLException {
        accept("evt-sf-2", "PAY-2", "CAPTURED", Instant.parse("2026-12-01T10:00:00Z"));
        SimulatedSalesforce salesforce = new SimulatedSalesforce()
                .failOnce("QZ-MX-PAY-2", SalesforceClient.Result.INVALID_FIELD);

        relay(salesforce).drain();
        assertEquals("state=DEAD, code=INVALID_FIELD", jobState("evt-sf-2"));

        relay(salesforce).drain();

        assertEquals(1, salesforce.writeCount(), "a wrong field is not fixed by asking again");
    }

    @Test
    void aLockedRowIsRetriedWithTheSameCurveAsEverythingElse() throws SQLException {
        accept("evt-sf-3", "PAY-3", "CAPTURED", Instant.parse("2026-12-01T10:00:00Z"));
        SimulatedSalesforce salesforce = new SimulatedSalesforce()
                .failOnce("QZ-MX-PAY-3", SalesforceClient.Result.UNABLE_TO_LOCK_ROW);

        relay(salesforce).drain();

        assertEquals("state=PENDING, code=UNABLE_TO_LOCK_ROW", jobState("evt-sf-3"));
        Instant due = nextAttempt("evt-sf-3");
        Duration wait = Duration.between(Instant.now(), due);
        assertTrue(wait.compareTo(RetrySchedule.after(1).minusSeconds(2)) >= 0
                        && wait.compareTo(RetrySchedule.after(1).plusSeconds(2)) <= 0,
                "a 400 that means 'try later' follows the same schedule: was " + wait);
    }

    @Test
    void aRateLimitStopsTheLaneAndPutsTheRestBack() throws SQLException {
        accept("evt-sf-4", "PAY-4", "CAPTURED", Instant.parse("2026-12-01T10:00:00Z"));
        accept("evt-sf-5", "PAY-5", "CAPTURED", Instant.parse("2026-12-01T10:00:01Z"));
        SimulatedSalesforce salesforce = new SimulatedSalesforce()
                .failNext(SalesforceClient.Result.RATE_LIMITED);

        relay(salesforce).drain();

        assertEquals(1, salesforce.writeCount(), "the lane stops instead of burning the exhausted budget");
        long rateLimited = count("SELECT count(*) FROM outbox_job WHERE state = 'PENDING' "
                + "AND outcome_code = 'RATE_LIMITED'");
        assertEquals(2, rateLimited, "both jobs wait for the budget to recover");
    }

    @Test
    void aLostResponseIsHeldAndNeverSentAgain() throws SQLException {
        accept("evt-sf-6", "PAY-6", "CAPTURED", Instant.parse("2026-12-01T10:00:00Z"));
        SimulatedSalesforce salesforce = new SimulatedSalesforce()
                .failOnce("QZ-MX-PAY-6", SalesforceClient.Result.LOST_RESPONSE);

        relay(salesforce).drain();
        assertEquals("state=HELD, code=LOST_RESPONSE", jobState("evt-sf-6"));
        assertNull(remoteAppliedStatus("PAY-6"),
                "nobody saw the remote apply it, so the projection must not claim it did");

        relay(salesforce).drain();

        assertEquals(1, salesforce.writeCount(),
                "an ambiguous write is a human decision, not another attempt");
    }

    @Test
    void aTimeoutIsRetriedBecauseItIsNotYetAmbiguous() throws SQLException {
        accept("evt-sf-7", "PAY-7", "CAPTURED", Instant.parse("2026-12-01T10:00:00Z"));
        SimulatedSalesforce salesforce = new SimulatedSalesforce()
                .failOnce("QZ-MX-PAY-7", SalesforceClient.Result.TIMEOUT);

        relay(salesforce).drain();

        assertEquals("state=PENDING, code=TIMEOUT", jobState("evt-sf-7"));
    }

    @Test
    void anOlderEventIsSkippedAndTheNewerOneIsApplied() throws SQLException {
        Instant capture = Instant.parse("2026-12-01T10:00:00Z");
        Instant reversal = Instant.parse("2026-12-01T11:00:00Z");
        accept("evt-sf-old", "PAY-8", "CAPTURED", capture);
        accept("evt-sf-new", "PAY-8", "REFUNDED", reversal);
        SimulatedSalesforce salesforce = new SimulatedSalesforce();

        relay(salesforce).drain();

        assertEquals("state=PUBLISHED, code=STALE_SKIPPED", jobState("evt-sf-old"));
        assertEquals("state=PUBLISHED, code=APPLIED", jobState("evt-sf-new"));
        assertEquals(1, salesforce.writeCount(), "the older fact never reached the destination");
        assertEquals("REFUNDED", salesforce.writes().get(0).status());
        assertEquals("REFUNDED", remoteAppliedStatus("PAY-8"));
    }

    @Test
    void aPaymentWithAWriteInFlightIsNotClaimedByAnotherWorker() {
        accept("evt-sf-9", "PAY-9", "CAPTURED", Instant.parse("2026-12-01T10:00:00Z"));
        List<PendingJob> first = store.claimDue(10, "worker-a", Duration.ofSeconds(30));
        assertEquals(1, first.size());

        // A new fact for the same payment arrives while the first write is in flight.
        accept("evt-sf-10", "PAY-9", "REFUNDED", Instant.parse("2026-12-01T11:00:00Z"));

        assertEquals(List.of(), store.claimDue(10, "worker-b", Duration.ofSeconds(30)),
                "one writer per payment: the second worker waits for the lease to end");
    }

    @Test
    void aCompletionWithAStaleTokenChangesNothing() {
        accept("evt-sf-11", "PAY-11", "CAPTURED", Instant.parse("2026-12-01T10:00:00Z"));
        PendingJob claimed = store.claimDue(1, "worker-a", Duration.ofSeconds(30)).get(0);
        PendingJob forged = new PendingJob(claimed.job(), claimed.event(), "0".repeat(8) + "-0000-0000-0000-000000000000");

        assertThrows(ClaimLostException.class, () -> store.applied(forged));
        assertThrows(ClaimLostException.class, () -> store.dead(forged, "INVALID_FIELD"));
        assertThrows(ClaimLostException.class, () -> store.held(forged, "LOST_RESPONSE"));
        assertThrows(ClaimLostException.class, () -> store.failed(forged, "SERVER_ERROR"));
    }

    private static SalesforceRelay relay(SimulatedSalesforce salesforce) {
        return new SalesforceRelay(store, salesforce, 10, "relay-test");
    }

    private static void accept(String eventId, String paymentNumber, String status, Instant changedAt) {
        assertEquals(Acceptance.ACCEPTED, intake.accept(event(
                eventId, "QZ-MX-" + paymentNumber, status, changedAt)));
    }

    private static String jobState(String eventId) throws SQLException {
        try (Connection connection = database.connection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT o.state, o.outcome_code FROM outbox_job o "
                             + "JOIN payment_event e ON e.intake_id = o.intake_id "
                             + "WHERE e.event_id = '" + eventId + "' AND o.destination = 'SALESFORCE'")) {
            assertTrue(rows.next(), "the job exists: " + eventId);
            return "state=" + rows.getString("state") + ", code=" + rows.getString("outcome_code");
        }
    }

    private static Instant nextAttempt(String eventId) throws SQLException {
        try (Connection connection = database.connection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT o.next_attempt_at FROM outbox_job o "
                             + "JOIN payment_event e ON e.intake_id = o.intake_id "
                             + "WHERE e.event_id = '" + eventId + "' AND o.destination = 'SALESFORCE'")) {
            assertTrue(rows.next());
            return rows.getObject(1, java.time.OffsetDateTime.class).toInstant();
        }
    }

    private static String remoteAppliedStatus(String paymentNumber) throws SQLException {
        try (Connection connection = database.connection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT remote_applied_status FROM payment_state WHERE payment_key = 'QZ-MX-"
                             + paymentNumber + "'")) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = database.connection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection connection = database.connection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static CanonicalEvent event(String eventId, String paymentKey, String status, Instant changedAt) {
        return new CanonicalEvent(
                eventId,
                Provider.QUETZAL,
                "MX",
                paymentKey,
                "ch_" + eventId,
                "POL-MX-000412785",
                125_000L,
                "MXN",
                2,
                status,
                null,
                null,
                "REFUNDED".equals(status) ? "UNKNOWN" : null,
                changedAt,
                changedAt,
                Instant.parse("2026-12-03T15:00:00Z"),
                eventId,
                "0".repeat(64));
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
