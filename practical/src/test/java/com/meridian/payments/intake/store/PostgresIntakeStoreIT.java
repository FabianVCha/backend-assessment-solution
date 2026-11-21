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
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void aFailedAttemptSchedulesTheSameCurveAsTheInMemoryStore() {
        store.accept(event(Provider.QUETZAL, "evt-retry"));
        PendingJob job = store.pending(Destination.CANONICAL_EVENT, 1).get(0);

        for (int attempt = 1; attempt <= 4; attempt++) {
            Instant before = Instant.now();
            store.failed(job, "the broker is down");
            Instant due = store.nextAttemptAt(
                    Provider.QUETZAL, "evt-retry", Destination.CANONICAL_EVENT);
            Duration wait = Duration.between(before, due);
            Duration expected = RetrySchedule.after(attempt);

            assertTrue(wait.compareTo(expected.minus(TOLERANCE)) >= 0
                            && wait.compareTo(expected.plus(TOLERANCE)) <= 0,
                    "attempt " + attempt + ": expected about " + expected + ", database scheduled " + wait);
        }
    }

    private static CanonicalEvent event(Provider provider, String eventId) {
        return new CanonicalEvent(
                eventId,
                provider,
                provider == Provider.ANDINA ? "CO" : "MX",
                "PAY-2026-0001",
                "TX-0001",
                "POL-0001",
                19900,
                provider == Provider.ANDINA ? "COP" : "MXN",
                2,
                "CAPTURED",
                null,
                // The real Quetzal sample reports no refund semantics: null is the fact, and the
                // database round trip must carry it. The Colombian fixture exercises the filled shape.
                provider == Provider.ANDINA ? "AMOUNT_AS_REPORTED" : null,
                provider == Provider.ANDINA ? "FULL" : null,
                Instant.parse("2026-12-01T10:00:00Z"),
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
