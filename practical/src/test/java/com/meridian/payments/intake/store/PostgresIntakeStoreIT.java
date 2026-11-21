package com.meridian.payments.intake.store;

import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.canonical.CanonicalEvent;
import com.meridian.payments.intake.publish.Broker;
import com.meridian.payments.intake.publish.CanonicalEventPublisher;
import com.meridian.payments.intake.publish.CanonicalMessage;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The durable store, against a real PostgreSQL.
 *
 * <p>The class is skipped when {@code INTAKE_IT_JDBC_URL} is not set, which keeps the default suite
 * free of infrastructure and makes the database tests an explicit run rather than an accident of the
 * machine. CI sets the variable and runs them on a service container, so the proof is a gate and not
 * only a local habit.
 *
 * <p>Each test asserts the state of the database, not only the value the store returned: a store that
 * answers ACCEPTED and writes nothing would pass the first kind of assertion and fail this one.
 */
class PostgresIntakeStoreIT {

    private static final String URL = System.getenv("INTAKE_IT_JDBC_URL");
    private static final String USER = environment("INTAKE_IT_DB_USER", "intake");
    private static final String PASSWORD = environment("INTAKE_IT_DB_PASSWORD", "");
    private static final Duration TOLERANCE = Duration.ofSeconds(2);

    private static Database database;
    private PostgresIntakeStore store;

    @BeforeAll
    static void open() {
        assumeTrue(URL != null && !URL.isBlank(),
                "INTAKE_IT_JDBC_URL is not set: the PostgreSQL tests need a database");
        database = Database.open(URL, USER, PASSWORD);
    }

    @AfterAll
    static void close() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void emptyTheTables() throws SQLException {
        store = new PostgresIntakeStore(database);
        execute("DELETE FROM outbox_job");
        execute("DELETE FROM payment_event");
        execute("DELETE FROM payment_state");
    }

    @Test
    void theMigrationRunnerAppliesTheSchema() throws SQLException {
        assertTrue(count("SELECT count(*) FROM flyway_schema_history WHERE success") >= 1,
                "the schema was applied by a recorded migration");
    }

    @Test
    void oneAcceptWritesTheEventAndBothJobs() throws SQLException {
        assertEquals(Acceptance.ACCEPTED, store.accept(event(Provider.QUETZAL, "evt-1")));

        assertEquals(1, count("SELECT count(*) FROM payment_event"));
        assertEquals(2, count("SELECT count(*) FROM outbox_job"));
        assertEquals(1, count("SELECT count(*) FROM outbox_job WHERE destination = 'CANONICAL_EVENT'"));
        assertEquals(1, count("SELECT count(*) FROM outbox_job WHERE destination = 'SALESFORCE'"));
    }

    @Test
    void aSecondDeliveryIsDuplicateAndAddsNothing() throws SQLException {
        CanonicalEvent event = event(Provider.QUETZAL, "evt-duplicate");

        assertEquals(Acceptance.ACCEPTED, store.accept(event));
        assertEquals(Acceptance.DUPLICATE, store.accept(event));

        assertEquals(1, count("SELECT count(*) FROM payment_event"));
        assertEquals(2, count("SELECT count(*) FROM outbox_job"));
    }

    @Test
    void theSameEventIdUnderAnotherProviderIsAnotherEvent() throws SQLException {
        assertEquals(Acceptance.ACCEPTED, store.accept(event(Provider.QUETZAL, "shared-1")));
        assertEquals(Acceptance.ACCEPTED, store.accept(event(Provider.ANDINA, "shared-1")));

        assertEquals(2, count("SELECT count(*) FROM payment_event"));
        assertEquals(4, count("SELECT count(*) FROM outbox_job"));
    }

    /**
     * Eight replicas deliver the same signed event at the same instant. The database decides: one
     * insert wins the unique constraint and the other seven are duplicates. Application code never
     * reads first, so there is no window between the check and the write.
     */
    @Test
    void concurrentDuplicatesCommitExactlyOneEvent() throws Exception {
        CanonicalEvent event = event(Provider.QUETZAL, "evt-race");
        int replicas = 8;
        CyclicBarrier startTogether = new CyclicBarrier(replicas);
        ExecutorService pool = Executors.newFixedThreadPool(replicas);
        List<Future<Acceptance>> results = new ArrayList<>();
        try {
            for (int replica = 0; replica < replicas; replica++) {
                results.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return store.accept(event);
                }));
            }
            int accepted = 0;
            int duplicates = 0;
            for (Future<Acceptance> result : results) {
                if (result.get(20, TimeUnit.SECONDS) == Acceptance.ACCEPTED) {
                    accepted++;
                } else {
                    duplicates++;
                }
            }
            assertEquals(1, accepted, "exactly one delivery is the first");
            assertEquals(replicas - 1, duplicates);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, count("SELECT count(*) FROM payment_event"));
        assertEquals(2, count("SELECT count(*) FROM outbox_job"));
    }

    /**
     * The second job cannot be written. The event row must not survive on its own: a 503 that leaves a
     * half-recorded payment behind would be worse than the failure it reports.
     */
    @Test
    void aFailureWhileWritingTheSecondJobLeavesNoRows() throws SQLException {
        execute("""
                CREATE OR REPLACE FUNCTION reject_salesforce_job() RETURNS trigger AS $$
                BEGIN
                    IF NEW.destination = 'SALESFORCE' THEN
                        RAISE EXCEPTION 'injected failure while writing the second job';
                    END IF;
                    RETURN NEW;
                END
                $$ LANGUAGE plpgsql
                """);
        execute("""
                CREATE TRIGGER outbox_job_reject BEFORE INSERT ON outbox_job
                FOR EACH ROW EXECUTE FUNCTION reject_salesforce_job()
                """);
        try {
            assertThrows(StoreUnavailableException.class,
                    () -> store.accept(event(Provider.QUETZAL, "evt-rollback")));
        } finally {
            execute("DROP TRIGGER outbox_job_reject ON outbox_job");
            execute("DROP FUNCTION reject_salesforce_job()");
        }

        assertEquals(0, count("SELECT count(*) FROM payment_event WHERE event_id = 'evt-rollback'"));
        assertEquals(0, count("SELECT count(*) FROM outbox_job"));
    }

    @Test
    void theCanonicalPublisherDrainsOnlyItsOwnLane() throws SQLException {
        store.accept(event(Provider.QUETZAL, "evt-lane"));
        List<CanonicalMessage> sent = new ArrayList<>();
        Broker broker = sent::add;

        int published = new CanonicalEventPublisher(store, broker, 10).publishPending();

        assertEquals(1, published);
        assertEquals(1, sent.size());
        assertTrue(store.pending(Destination.CANONICAL_EVENT, 10).isEmpty(),
                "the published lane holds nothing due");
        assertEquals(1, store.pending(Destination.SALESFORCE, 10).size(),
                "the other lane is untouched by a broker publication");
    }

    @Test
    void whatThePublisherReadsIsWhatWasAccepted() {
        CanonicalEvent accepted = event(Provider.ANDINA, "evt-roundtrip");
        store.accept(accepted);

        PendingJob job = store.pending(Destination.CANONICAL_EVENT, 1).get(0);

        assertEquals(accepted, job.event(), "the stored document is read back, not rebuilt");
    }

    /** The SQL implements the same curve as {@link RetrySchedule}; this is what pins the two. */
    @Test
    void aFailedAttemptSchedulesTheSameCurveAsTheInMemoryStore() throws InterruptedException {
        store.accept(event(Provider.QUETZAL, "evt-retry"));

        for (int attempt = 1; attempt <= 3; attempt++) {
            PendingJob job = store.claim(
                    Destination.CANONICAL_EVENT, 1, "worker", Duration.ofSeconds(30)).get(0);
            Instant before = Instant.now();
            store.failed(job, "the broker is down");
            Instant due = store.nextAttemptAt(
                    Provider.QUETZAL, "evt-retry", Destination.CANONICAL_EVENT);
            Duration wait = Duration.between(before, due);
            Duration expected = RetrySchedule.after(attempt);

            assertTrue(wait.compareTo(expected.minus(TOLERANCE)) >= 0
                            && wait.compareTo(expected.plus(TOLERANCE)) <= 0,
                    "attempt " + attempt + ": expected about " + expected + ", database scheduled " + wait);

            // Wait for the row to be due; the next claim would otherwise find nothing, and that is the
            // schedule working as intended rather than a reason to make the lease longer.
            Thread.sleep(Math.max(0, Duration.between(Instant.now(), due).toMillis()) + 100);
        }
    }

    /**
     * Two workers polling at the same instant split the batch. The database decides with
     * {@code FOR UPDATE SKIP LOCKED}: a job is handed to one claimant, not to both.
     */
    @Test
    void twoWorkersClaimDisjointJobs() {
        for (String id : List.of("evt-a", "evt-b", "evt-c", "evt-d")) {
            store.accept(event(Provider.QUETZAL, id));
        }

        List<PendingJob> first = store.claim(
                Destination.CANONICAL_EVENT, 2, "worker-a", Duration.ofSeconds(30));
        List<PendingJob> second = store.claim(
                Destination.CANONICAL_EVENT, 2, "worker-b", Duration.ofSeconds(30));

        assertEquals(2, first.size());
        assertEquals(2, second.size());
        Set<String> claimed = new HashSet<>();
        first.forEach(job -> claimed.add(job.job().eventId()));
        second.forEach(job -> claimed.add(job.job().eventId()));
        assertEquals(4, claimed.size(), "no job was handed to both workers");
        assertEquals(List.of(),
                store.claim(Destination.CANONICAL_EVENT, 4, "worker-c", Duration.ofSeconds(30)),
                "the whole lane is claimed");
    }

    /**
     * A worker that dies holding a lease must not park the payment. When the lease expires the job is
     * due again, and the worker that took over is the only one that can complete it.
     */
    @Test
    void anExpiredLeaseCanBeReclaimedAndTheOldTokenCompletesNothing()
            throws InterruptedException, SQLException {
        store.accept(event(Provider.QUETZAL, "evt-lease"));
        PendingJob stale = store.claim(
                Destination.CANONICAL_EVENT, 1, "worker-a", Duration.ofSeconds(1)).get(0);

        // Wait for the database to say the lease is dead. "Sleep a bit more than a second" is the same
        // wall-clock assumption that made a publisher test fail on a loaded runner: the JVM and the
        // database are not required to agree on what time it is, and the row is the source of truth.
        awaitLeaseExpiry("evt-lease");

        PendingJob current = store.claim(
                Destination.CANONICAL_EVENT, 1, "worker-b", Duration.ofSeconds(30)).get(0);

        assertEquals(stale.job(), current.job(), "the work came back after the lease died");
        assertNotEquals(stale.claimToken(), current.claimToken());
        assertThrows(ClaimLostException.class, () -> store.published(stale),
                "the expired worker must not mark someone else's job done");
        assertEquals(1, count("SELECT count(*) FROM outbox_job WHERE state = 'CLAIMED'"),
                "the job still belongs to the worker that took it");

        store.published(current);
        assertEquals(1, count("SELECT count(*) FROM outbox_job WHERE state = 'PUBLISHED'"));
    }

    @Test
    void aJobReadWithoutAClaimCannotBeCompleted() {
        store.accept(event(Provider.QUETZAL, "evt-read"));
        PendingJob read = store.pending(Destination.CANONICAL_EVENT, 1).get(0);

        assertThrows(IllegalArgumentException.class, () -> store.published(read),
                "reading is not claiming");
    }

    /** Two publisher replicas over the same table: each event is sent once, not twice. */
    @Test
    void twoReplicasPublishEachEventOnce() {
        for (String id : List.of("evt-1", "evt-2")) {
            store.accept(event(Provider.QUETZAL, id));
        }
        List<CanonicalMessage> sent = new ArrayList<>();
        Broker broker = sent::add;
        CanonicalEventPublisher first = new CanonicalEventPublisher(store, broker, 10, "replica-a");
        CanonicalEventPublisher second = new CanonicalEventPublisher(store, broker, 10, "replica-b");

        int total = first.publishPending() + second.publishPending()
                + first.publishPending() + second.publishPending();

        assertEquals(2, total);
        assertEquals(2, sent.size(), "no event was sent twice");
        assertEquals(2, sent.stream().map(CanonicalMessage::messageId).distinct().count());
    }

    /** The projection answers "where is this payment", so it survives an older notification arriving late. */
    @Test
    void theProjectionKeepsTheNewestStatusByTheProviderClock() throws SQLException {
        store.accept(event(Provider.QUETZAL, "evt-capture", "PAY-9", "CAPTURED",
                Instant.parse("2026-12-01T10:00:00Z")));
        store.accept(event(Provider.QUETZAL, "evt-reversal", "PAY-9", "REVERSED",
                Instant.parse("2026-12-01T11:00:00Z")));

        assertProjection("PAY-9", "REVERSED", "evt-reversal", 2);
    }

    @Test
    void aDelayedCaptureDoesNotReplaceANewerReversal() throws SQLException {
        store.accept(event(Provider.QUETZAL, "evt-reversal", "PAY-9", "REVERSED",
                Instant.parse("2026-12-01T11:00:00Z")));
        store.accept(event(Provider.QUETZAL, "evt-late-capture", "PAY-9", "CAPTURED",
                Instant.parse("2026-12-01T10:30:00Z")));

        assertProjection("PAY-9", "REVERSED", "evt-reversal", 2);
    }

    @Test
    void aDuplicateIsCountedOnce() throws SQLException {
        CanonicalEvent event = event(Provider.QUETZAL, "evt-dup-state", "PAY-9", "CAPTURED",
                Instant.parse("2026-12-01T10:00:00Z"));
        store.accept(event);
        store.accept(event);

        assertProjection("PAY-9", "CAPTURED", "evt-dup-state", 1);
    }

    @Test
    void aRolledBackAcceptLeavesNoProjection() throws SQLException {
        execute("""
                CREATE OR REPLACE FUNCTION reject_salesforce_job() RETURNS trigger AS $$
                BEGIN
                    IF NEW.destination = 'SALESFORCE' THEN
                        RAISE EXCEPTION 'injected failure while writing the second job';
                    END IF;
                    RETURN NEW;
                END
                $$ LANGUAGE plpgsql
                """);
        execute("""
                CREATE TRIGGER outbox_job_reject BEFORE INSERT ON outbox_job
                FOR EACH ROW EXECUTE FUNCTION reject_salesforce_job()
                """);
        try {
            assertThrows(StoreUnavailableException.class, () -> store.accept(
                    event(Provider.QUETZAL, "evt-rollback-state", "PAY-9", "CAPTURED",
                            Instant.parse("2026-12-01T10:00:00Z"))));
        } finally {
            execute("DROP TRIGGER outbox_job_reject ON outbox_job");
            execute("DROP FUNCTION reject_salesforce_job()");
        }

        assertEquals(0, count("SELECT count(*) FROM payment_state"));
    }

    private static void assertProjection(
            String paymentKey, String status, String eventId, int observed) throws SQLException {
        try (Connection connection = database.connection();
             var statement = connection.prepareStatement(
                     "SELECT latest_status, latest_event_id, observed_events "
                             + "FROM payment_state WHERE payment_key = ?")) {
            statement.setString(1, paymentKey);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "the payment has a projection");
                assertEquals(status, rows.getString("latest_status"));
                assertEquals(eventId, rows.getString("latest_event_id"));
                assertEquals(observed, rows.getInt("observed_events"));
            }
        }
    }

    /** Blocks until the database itself reports the claim lease as expired. */
    private static void awaitLeaseExpiry(String eventId) throws SQLException, InterruptedException {
        Instant deadline = Instant.now().plusSeconds(15);
        String expired = "SELECT count(*) FROM outbox_job o "
                + "JOIN payment_event e ON e.intake_id = o.intake_id "
                + "WHERE e.event_id = '" + eventId + "' "
                + "AND o.state = 'CLAIMED' AND o.claim_expires_at <= now()";
        while (count(expired) == 0) {
            if (Instant.now().isAfter(deadline)) {
                fail("the lease on " + eventId + " never expired");
            }
            Thread.sleep(100);
        }
    }

    private static CanonicalEvent event(Provider provider, String eventId) {
        return event(provider, eventId, "PAY-2026-0001", "CAPTURED",
                Instant.parse("2026-12-01T10:00:00Z"));
    }

    private static CanonicalEvent event(
            Provider provider, String eventId, String paymentKey, String status, Instant statusChangedAt) {
        return new CanonicalEvent(
                eventId,
                provider,
                provider == Provider.ANDINA ? "CO" : "MX",
                paymentKey,
                "TX-0001",
                "POL-0001",
                19900,
                provider == Provider.ANDINA ? "COP" : "MXN",
                2,
                status,
                null,
                // The real Quetzal sample reports no refund semantics: null is the fact, and the
                // database round trip must carry it. The Colombian fixture exercises the filled shape.
                provider == Provider.ANDINA ? "AMOUNT_AS_REPORTED" : null,
                provider == Provider.ANDINA ? "FULL" : null,
                statusChangedAt,
                Instant.parse("2026-12-01T09:59:30Z"),
                Instant.parse("2026-12-01T10:00:05Z"),
                "corr-" + eventId,
                "hash-" + eventId);
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

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
