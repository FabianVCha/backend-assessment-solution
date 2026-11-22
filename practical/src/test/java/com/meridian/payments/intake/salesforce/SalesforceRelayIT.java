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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        execute("TRUNCATE salesforce_budget, salesforce_budget_call, salesforce_budget_lease");
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
    void anAdmittedWriteCountsBeforeTheCallAndReleasesAfterIt() throws SQLException {
        accept("evt-budget-admitted", "B-1", "CAPTURED", Instant.EPOCH);
        long[] duringCall = new long[2];
        SalesforceClient client = write -> {
            try {
                duringCall[0] = count("SELECT sum(calls) FROM salesforce_budget");
                duringCall[1] = count("SELECT count(*) FROM salesforce_budget_lease");
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
            return SalesforceClient.Result.APPLIED;
        };

        assertEquals(1, new SalesforceRelay(store, client, 10).drain());

        assertEquals(1, duringCall[0]);
        assertEquals(1, duringCall[1]);
        assertEquals(1, count("SELECT sum(calls) FROM salesforce_budget"));
        assertEquals(0, count("SELECT count(*) FROM salesforce_budget_lease"));
    }

    @Test
    void rollingLimitIncludesCallsFromYesterdayAndDefersTheWholeBatch() throws SQLException {
        execute("""
                INSERT INTO salesforce_budget VALUES
                    ((now() AT TIME ZONE 'UTC')::date - 1, 1, 0),
                    ((now() AT TIME ZONE 'UTC')::date, 149999, 0);
                INSERT INTO salesforce_budget_call(admitted_at)
                    VALUES (date_trunc('day', now(), 'UTC') - interval '1 microsecond')
                """);
        accept("evt-budget-first", "B-2", "CAPTURED", Instant.EPOCH);
        accept("evt-budget-rest", "B-3", "CAPTURED", Instant.EPOCH);
        SimulatedSalesforce salesforce = new SimulatedSalesforce();

        assertEquals(0, relay(salesforce).drain());

        assertEquals(0, salesforce.writeCount());
        assertEquals(2, count("""
                SELECT count(*) FROM outbox_job WHERE destination = 'SALESFORCE'
                  AND state = 'PENDING' AND outcome_code = 'BUDGET_EXHAUSTED' AND attempts = 0
                  AND claim_token IS NULL
                  AND next_attempt_at BETWEEN now() + interval '2 minutes' AND now() + interval '3 minutes'
                """));
        assertEquals(150_000, count("SELECT sum(calls) FROM salesforce_budget"));

        execute("UPDATE salesforce_budget_call SET admitted_at = now() - interval '25 hours'");
        execute("UPDATE outbox_job SET next_attempt_at = now() WHERE destination = 'SALESFORCE'");
        assertEquals(1, relay(salesforce).drain(), "the expired call makes room for exactly one write");
        assertEquals(1, salesforce.writeCount());
    }

    @Test
    void concurrencyLimitCountsRequestsThatStartedBeforeMidnight() throws SQLException {
        execute("""
                INSERT INTO salesforce_budget VALUES ((now() AT TIME ZONE 'UTC')::date - 1, 25, 0);
                INSERT INTO salesforce_budget_lease
                SELECT gen_random_uuid(), now() + interval '1 minute' FROM generate_series(1, 25)
                """);
        accept("evt-budget-busy", "B-4", "CAPTURED", Instant.EPOCH);
        SimulatedSalesforce salesforce = new SimulatedSalesforce();

        assertEquals(0, relay(salesforce).drain());

        assertEquals(0, salesforce.writeCount());
        assertEquals("state=PENDING, code=BUDGET_EXHAUSTED", jobState("evt-budget-busy"));
        assertEquals(25, count("SELECT count(*) FROM salesforce_budget_lease"));
        assertEquals(25, count("SELECT sum(calls) FROM salesforce_budget"));
    }

    @Test
    void aThrownClientFailureStillReleasesTheInFlightCount() throws SQLException {
        accept("evt-budget-throw", "B-5", "CAPTURED", Instant.EPOCH);
        IllegalStateException failure = new IllegalStateException("client failed");
        SalesforceClient client = write -> { throw failure; };

        assertEquals(failure, assertThrows(IllegalStateException.class,
                () -> new SalesforceRelay(store, client, 10).drain()));

        assertEquals(1, count("SELECT sum(calls) FROM salesforce_budget"));
        assertEquals(0, count("SELECT count(*) FROM salesforce_budget_lease"));
    }

    @Test
    void twoReplicasCannotBothTakeTheLastRollingCall() throws Exception {
        execute("""
                INSERT INTO salesforce_budget VALUES ((now() AT TIME ZONE 'UTC')::date, 149999, 0)
                """);
        raceForLastSlot(150_000, 1, 0);
    }

    @Test
    void twoReplicasCannotBothTakeTheLastConcurrentSlot() throws Exception {
        execute("""
                INSERT INTO salesforce_budget VALUES ((now() AT TIME ZONE 'UTC')::date - 1, 24, 0);
                INSERT INTO salesforce_budget_lease
                SELECT gen_random_uuid(), now() + interval '1 minute' FROM generate_series(1, 24)
                """);
        raceForLastSlot(25, 25, 24);
    }

    private static void raceForLastSlot(long finalCalls, long inFlightDuringCall, long finalInFlight)
            throws Exception {
        accept("evt-budget-race-a", "race-a", "CAPTURED", Instant.EPOCH);
        accept("evt-budget-race-b", "race-b", "CAPTURED", Instant.EPOCH);
        CountDownLatch begin = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        SalesforceClient client = write -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
            }
            return SalesforceClient.Result.APPLIED;
        };
        try (Database second = Database.open(URL, USER, PASSWORD);
             var workers = Executors.newFixedThreadPool(2)) {
            var results = new ExecutorCompletionService<Integer>(workers);
            try {
                for (SalesforceStore replica : List.of(store, new PostgresSalesforceStore(second))) {
                    results.submit(() -> {
                        await(begin);
                        return new SalesforceRelay(replica, client, 1).drain();
                    });
                }
                begin.countDown();
                await(entered);
                var denied = results.poll(10, TimeUnit.SECONDS);
                assertNotNull(denied);
                assertEquals(0, denied.get());
                assertEquals(1, calls.get());
                assertEquals(finalCalls, count("SELECT sum(calls) FROM salesforce_budget"));
                assertEquals(inFlightDuringCall, count("SELECT count(*) FROM salesforce_budget_lease"));
                assertEquals(1, count("""
                        SELECT count(*) FROM outbox_job WHERE state = 'PENDING'
                            AND outcome_code = 'BUDGET_EXHAUSTED' AND attempts = 0 AND claim_token IS NULL
                        """));

                release.countDown();
                var admitted = results.poll(10, TimeUnit.SECONDS);
                assertNotNull(admitted);
                assertEquals(1, admitted.get());
                assertEquals(finalInFlight, count("SELECT count(*) FROM salesforce_budget_lease"));
            } finally {
                release.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "relay synchronization timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
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
    void aTimeoutHoldsThePaymentUntilReconciliation() throws SQLException {
        accept("evt-sf-7", "PAY-7", "CAPTURED", Instant.parse("2026-12-01T10:00:00Z"));
        SimulatedSalesforce salesforce = new SimulatedSalesforce()
                .failOnce("QZ-MX-PAY-7", SalesforceClient.Result.TIMEOUT);

        relay(salesforce).drain();

        assertEquals("state=HELD, code=TIMEOUT", jobState("evt-sf-7"));
        accept("evt-sf-later", "PAY-7", "REFUNDED", Instant.parse("2026-12-01T11:00:00Z"));
        accept("evt-sf-other", "PAY-other", "CAPTURED", Instant.EPOCH);
        assertEquals(1, relay(salesforce).drain());
        assertEquals("state=PENDING, code=null", jobState("evt-sf-later"));
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
        assertEquals(1, relay(salesforce).drain());
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
    void simultaneousClaimsTakeAtMostOneJobForThePayment() throws Exception {
        accept("first", "SAME", "CAPTURED", Instant.EPOCH);
        accept("second", "SAME", "REFUNDED", Instant.EPOCH.plusSeconds(1));
        CountDownLatch start = new CountDownLatch(1);
        try (Database second = Database.open(URL, USER, PASSWORD);
             var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> {
                await(start);
                return store.claimDue(10, "one", Duration.ofSeconds(30));
            });
            var two = workers.submit(() -> {
                await(start);
                return new PostgresSalesforceStore(second).claimDue(10, "two", Duration.ofSeconds(30));
            });
            start.countDown();
            assertEquals(1, one.get(10, TimeUnit.SECONDS).size() + two.get(10, TimeUnit.SECONDS).size());
        }
        assertEquals(1, count("SELECT count(*) FROM outbox_job WHERE state = 'CLAIMED'"));
    }

    @Test
    void anExpiredClaimHoldsThePaymentAndRejectsStaleCompletion() throws SQLException {
        accept("expired", "SAME", "CAPTURED", Instant.EPOCH);
        var claim = store.claimDue(1, "old", Duration.ofSeconds(30)).getFirst();
        accept("later", "SAME", "REFUNDED", Instant.EPOCH.plusSeconds(1));
        execute("UPDATE outbox_job SET claim_expires_at = now() - interval '1 second' WHERE state = 'CLAIMED'");
        assertFalse(store.canDispatch(claim));
        assertThrows(ClaimLostException.class, () -> store.applied(claim));
        assertEquals(List.of(), store.claimDue(10, "new", Duration.ofSeconds(30)));
        assertEquals("state=HELD, code=CLAIM_EXPIRED", jobState("expired"));
        assertEquals("state=PENDING, code=null", jobState("later"));
    }

    @Test
    void sameTimeRefundTakesPrecedenceInEitherArrivalOrder() throws SQLException {
        accept("refund-a", "TIE-A", "REFUNDED", Instant.EPOCH);
        accept("capture-a", "TIE-A", "CAPTURED", Instant.EPOCH);
        accept("capture-b", "TIE-B", "CAPTURED", Instant.EPOCH);
        accept("refund-b", "TIE-B", "REFUNDED", Instant.EPOCH);
        SimulatedSalesforce client = new SimulatedSalesforce();
        var relay = relay(client);
        assertEquals(1, relay.drain());
        assertEquals(1, relay.drain());
        assertEquals(List.of("REFUNDED", "REFUNDED"),
                client.writes().stream().map(SalesforceClient.PaymentWrite::status).toList());
        assertEquals("REFUNDED", remoteAppliedStatus("TIE-A"));
        assertEquals("REFUNDED", remoteAppliedStatus("TIE-B"));
    }

    @Test
    void failedProjectionUpdateDoesNotCommitJobCompletion() throws SQLException {
        accept("evt-atomic", "ATOMIC", "CAPTURED", Instant.EPOCH);
        var claim = store.claimDue(1, "worker", Duration.ofSeconds(30)).getFirst();
        execute("""
                CREATE FUNCTION reject_applied_projection() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'projection write unavailable'; END $$;
                CREATE TRIGGER reject_applied_projection BEFORE UPDATE ON payment_state
                FOR EACH ROW EXECUTE FUNCTION reject_applied_projection()
                """);
        try {
            assertThrows(com.meridian.payments.intake.store.StoreUnavailableException.class,
                    () -> store.applied(claim));
            assertEquals("state=CLAIMED, code=null", jobState("evt-atomic"));
            assertNull(remoteAppliedStatus("ATOMIC"));
        } finally {
            execute("DROP TRIGGER reject_applied_projection ON payment_state; DROP FUNCTION reject_applied_projection()");
        }
    }

    @Test
    void expiredReservationsRecoverCapacityWithoutErasingCallHistory() throws SQLException {
        for (int i = 0; i < 25; i++) {
            assertTrue(store.tryAcquireBudget().isPresent());
        }
        assertTrue(store.tryAcquireBudget().isEmpty());
        execute("UPDATE salesforce_budget_lease SET expires_at = now() - interval '1 second'");
        var fresh = store.tryAcquireBudget().orElseThrow();
        assertEquals(1, count("SELECT count(*) FROM salesforce_budget_lease"));
        assertEquals(26, count("SELECT sum(calls) FROM salesforce_budget"));
        assertEquals(26, count("SELECT count(*) FROM salesforce_budget_call"));
        store.releaseBudget(fresh);
        store.releaseBudget(fresh);
        assertEquals(0, count("SELECT count(*) FROM salesforce_budget_lease"));
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
