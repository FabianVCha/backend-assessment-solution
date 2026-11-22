package com.meridian.payments.intake.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.canonical.CanonicalEvent;
import com.meridian.payments.intake.canonical.CanonicalEvents;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The inbox and the outbox in PostgreSQL.
 *
 * <p>Acceptance is one transaction and one constraint. The event is inserted with
 * {@code ON CONFLICT (provider, event_id) DO NOTHING}; a row count of zero means another delivery
 * already committed this event, so this one is a duplicate and no job is written. There is no
 * read-then-insert: two replicas receiving the same event at the same moment both reach the same
 * constraint, one commits and the other is told it is a duplicate. That is the only place the
 * decision is made, and it is the database's.
 *
 * <p>The event row and both jobs commit together. A failure after the event insert but before the
 * jobs leaves nothing visible, so the provider's retry is the first acceptance rather than a
 * half-recorded one. The 2xx is returned by the caller only after this method returns, which is
 * after the commit.
 *
 * <p>What the store returns to the publisher is the document it stored, parsed back. Publication
 * cannot send a fact assembled from columns at the last moment.
 */
public final class PostgresIntakeStore implements IntakeStore, OutboxStore {

    private static final String INSERT_EVENT = """
            INSERT INTO payment_event (
                intake_id, provider, event_id, payment_key, policy_number, status, status_changed_at,
                occurred_at, received_at, correlation_id, payload_hash, canonical)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (provider, event_id) DO NOTHING
            """;

    private static final String INSERT_JOB = """
            INSERT INTO outbox_job (intake_id, destination, next_attempt_at)
            VALUES (?, ?, ?)
            """;

    private static final String COUNT_DUPLICATE = """
            UPDATE payment_event
            SET duplicate_count = duplicate_count + 1, last_duplicate_at = statement_timestamp()
            WHERE provider = ? AND event_id = ?
            """;

    private static final String DUE_JOBS = """
            SELECT o.destination, o.attempts, e.provider, e.event_id, e.canonical
            FROM outbox_job o
            JOIN payment_event e ON e.intake_id = o.intake_id
            WHERE o.destination = ?
              AND o.state = 'PENDING'
              AND o.next_attempt_at <= now()
            ORDER BY o.next_attempt_at, o.outbox_id
            LIMIT ?
            """;

    private static final String CLAIMABLE = """
            SELECT outbox_id
            FROM outbox_job
            WHERE destination = ?
              AND ((state = 'PENDING' AND next_attempt_at <= now())
                   OR (state = 'CLAIMED' AND claim_expires_at <= now()))
            ORDER BY next_attempt_at, outbox_id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    private static final String MARK_CLAIMED = """
            UPDATE outbox_job
            SET state = 'CLAIMED',
                claim_owner = ?,
                claim_token = CAST(? AS uuid),
                claim_expires_at = now() + make_interval(secs => ?)
            WHERE outbox_id = ANY(?)
            """;

    private static final String CLAIMED_JOBS = """
            SELECT o.outbox_id, e.provider, e.event_id, e.canonical
            FROM outbox_job o
            JOIN payment_event e ON e.intake_id = o.intake_id
            WHERE o.outbox_id = ANY(?)
            ORDER BY o.next_attempt_at, o.outbox_id
            """;

    private static final String MARK_PUBLISHED = """
            UPDATE outbox_job o
            SET state = 'PUBLISHED',
                published_at = now(),
                claim_owner = NULL,
                claim_token = NULL,
                claim_expires_at = NULL
            FROM payment_event e
            WHERE o.intake_id = e.intake_id
              AND e.provider = ? AND e.event_id = ? AND o.destination = ?
              AND o.claim_token = CAST(? AS uuid)
            """;

    private static final String MARK_FAILED = """
            UPDATE outbox_job o
            SET attempts = o.attempts + 1,
                state = 'PENDING',
                last_error = left(?, 500),
                claim_owner = NULL,
                claim_token = NULL,
                claim_expires_at = NULL,
                next_attempt_at = now() + make_interval(secs => LEAST(60, (1 << LEAST(o.attempts, 6))))
            FROM payment_event e
            WHERE o.intake_id = e.intake_id
              AND e.provider = ? AND e.event_id = ? AND o.destination = ?
              AND o.claim_token = CAST(? AS uuid)
            """;

    private static final String UPSERT_PAYMENT_STATE = """
            INSERT INTO payment_state (
                payment_key, provider, country, latest_status, latest_event_id,
                latest_status_changed_at, latest_received_at, observed_events)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT (payment_key) DO UPDATE
            SET observed_events = payment_state.observed_events + 1,
                updated_at = now(),
                (latest_status, latest_event_id, latest_status_changed_at, latest_received_at) = (
                    SELECT candidate.status, candidate.event_id, candidate.changed_at, candidate.received_at
                    FROM (VALUES
                        (EXCLUDED.latest_status, EXCLUDED.latest_event_id,
                         EXCLUDED.latest_status_changed_at, EXCLUDED.latest_received_at),
                        (payment_state.latest_status, payment_state.latest_event_id,
                         payment_state.latest_status_changed_at, payment_state.latest_received_at)
                    ) AS candidate(status, event_id, changed_at, received_at)
                    ORDER BY candidate.changed_at DESC, payment_status_rank(candidate.status) DESC,
                             candidate.event_id COLLATE "C" DESC
                    LIMIT 1
                )
            """;

    private final Database database;
    private final Clock clock;
    private static final ObjectMapper JSON = new ObjectMapper();

    public PostgresIntakeStore(Database database) {
        this(database, Clock.systemUTC());
    }

    public PostgresIntakeStore(Database database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Acceptance accept(CanonicalEvent event) {
        Objects.requireNonNull(event, "event");
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                UUID intakeId = UUID.randomUUID();
                if (!insertEvent(connection, intakeId, event)) {
                    // Record the delivery against the winning event, without touching its facts or jobs.
                    try (var duplicate = connection.prepareStatement(COUNT_DUPLICATE)) {
                        duplicate.setString(1, event.provider().name());
                        duplicate.setString(2, event.eventId());
                        duplicate.executeUpdate();
                    }
                    connection.commit();
                    return Acceptance.DUPLICATE;
                }
                insertJob(connection, intakeId, Destination.CANONICAL_EVENT);
                insertJob(connection, intakeId, Destination.SALESFORCE);
                // The projection moves with the fact it summarises: if this transaction rolls back, the
                // operations view cannot claim a state the inbox does not hold.
                upsertPaymentState(connection, event);
                connection.commit();
                return Acceptance.ACCEPTED;
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw new StoreUnavailableException("the intake store rejected the write", e);
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("the intake store is unavailable", e);
        }
    }

    @Override
    public List<PendingJob> pending(Destination destination, int limit) {
        Objects.requireNonNull(destination, "destination");
        if (limit < 1) {
            throw new IllegalArgumentException("a batch needs at least one job");
        }
        List<PendingJob> jobs = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(DUE_JOBS)) {
            statement.setString(1, destination.name());
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Provider provider = Provider.valueOf(rows.getString("provider"));
                    String eventId = rows.getString("event_id");
                    CanonicalEvent event = CanonicalEvents.fromJson(
                            (ObjectNode) JSON.readTree(rows.getString("canonical")));
                    jobs.add(new PendingJob(new OutboxJob(provider, eventId, destination), event));
                }
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("the outbox could not be read", e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the stored canonical event is not readable", e);
        }
        return jobs;
    }

    @Override
    public void published(PendingJob job) {
        Objects.requireNonNull(job, "job");
        requireClaim(job);
        int updated = update(MARK_PUBLISHED, statement -> {
            statement.setString(1, job.job().provider().name());
            statement.setString(2, job.job().eventId());
            statement.setString(3, job.job().destination().name());
            statement.setString(4, job.claimToken());
        });
        if (updated == 0) {
            throw new ClaimLostException("the lease on " + job.job() + " belongs to another worker now");
        }
    }

    @Override
    public void failed(PendingJob job, String reason) {
        Objects.requireNonNull(job, "job");
        requireClaim(job);
        int updated = update(MARK_FAILED, statement -> {
            statement.setString(1, reason == null ? "publication failed" : reason);
            statement.setString(2, job.job().provider().name());
            statement.setString(3, job.job().eventId());
            statement.setString(4, job.job().destination().name());
            statement.setString(5, job.claimToken());
        });
        if (updated == 0) {
            throw new ClaimLostException("the lease on " + job.job() + " belongs to another worker now");
        }
    }

    /**
     * Takes due jobs with {@code FOR UPDATE SKIP LOCKED}, so two workers polling at the same instant
     * split the batch instead of blocking each other or both sending the same message.
     */
    @Override
    public List<PendingJob> claim(Destination destination, int limit, String owner, Duration lease) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(lease, "lease");
        if (limit < 1) {
            throw new IllegalArgumentException("a batch needs at least one job");
        }
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("a lease must last longer than nothing");
        }
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                List<Long> ids = dueIds(connection, destination, limit);
                if (ids.isEmpty()) {
                    connection.commit();
                    return List.of();
                }
                String token = UUID.randomUUID().toString();
                try (PreparedStatement statement = connection.prepareStatement(MARK_CLAIMED)) {
                    statement.setString(1, owner);
                    statement.setString(2, token);
                    statement.setLong(3, Math.max(1, lease.toSeconds()));
                    statement.setArray(4, connection.createArrayOf("bigint", ids.toArray()));
                    statement.executeUpdate();
                }
                List<PendingJob> claimed = claimedJobs(connection, ids, destination, token);
                connection.commit();
                return claimed;
            } catch (SQLException | JsonProcessingException e) {
                rollbackQuietly(connection);
                throw new StoreUnavailableException("the outbox could not be claimed", e);
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("the outbox could not be claimed", e);
        }
    }

    private List<Long> dueIds(Connection connection, Destination destination, int limit)
            throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(CLAIMABLE)) {
            statement.setString(1, destination.name());
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getLong("outbox_id"));
                }
            }
        }
        return ids;
    }

    private List<PendingJob> claimedJobs(
            Connection connection, List<Long> ids, Destination destination, String token)
            throws SQLException, JsonProcessingException {
        List<PendingJob> claimed = new ArrayList<>(ids.size());
        try (PreparedStatement statement = connection.prepareStatement(CLAIMED_JOBS)) {
            statement.setArray(1, connection.createArrayOf("bigint", ids.toArray()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Provider provider = Provider.valueOf(rows.getString("provider"));
                    String eventId = rows.getString("event_id");
                    CanonicalEvent event = CanonicalEvents.fromJson(
                            (ObjectNode) JSON.readTree(rows.getString("canonical")));
                    claimed.add(new PendingJob(
                            new OutboxJob(provider, eventId, destination), event, token));
                }
            }
        }
        return claimed;
    }

    private static void requireClaim(PendingJob job) {
        if (!job.claimed()) {
            throw new IllegalArgumentException("a job must be claimed before it can be completed");
        }
    }

    /** When the job is next due, for tests and for the operations view that will come later. */
    public Instant nextAttemptAt(Provider provider, String eventId, Destination destination) {
        String sql = """
                SELECT o.next_attempt_at
                FROM outbox_job o
                JOIN payment_event e ON e.intake_id = o.intake_id
                WHERE e.provider = ? AND e.event_id = ? AND o.destination = ?
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, provider.name());
            statement.setString(2, eventId);
            statement.setString(3, destination.name());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException("no such job");
                }
                return rows.getObject("next_attempt_at", OffsetDateTime.class).toInstant();
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("the outbox could not be read", e);
        }
    }

    private boolean insertEvent(Connection connection, UUID intakeId, CanonicalEvent event)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_EVENT)) {
            statement.setObject(1, intakeId);
            statement.setString(2, event.provider().name());
            statement.setString(3, event.eventId());
            statement.setString(4, event.paymentKey());
            statement.setString(5, event.policyNumber());
            statement.setString(6, event.status());
            statement.setObject(7, at(event.statusChangedAt()));
            statement.setObject(8, at(event.occurredAt()));
            statement.setObject(9, at(event.receivedAt()));
            statement.setString(10, event.correlationId());
            statement.setString(11, event.payloadHash());
            statement.setString(12, event.toJson().toString());
            return statement.executeUpdate() == 1;
        }
    }

    private void insertJob(Connection connection, UUID intakeId, Destination destination)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_JOB)) {
            statement.setObject(1, intakeId);
            statement.setString(2, destination.name());
            statement.setObject(3, at(clock.instant()));
            statement.executeUpdate();
        }
    }

    /**
     * Keeps the payment projection in step with the event that was just accepted.
     *
     * <p>The status only moves when the provider's own timestamp is not older than what the row holds.
     * A capture that arrives late therefore cannot undo a reversal: the newest fact by the provider's
     * clock wins, and arrival order decides nothing. The counter of observed events moves on every
     * accepted notification, because a notification that changed no status is still a fact.
     */
    private void upsertPaymentState(Connection connection, CanonicalEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_PAYMENT_STATE)) {
            statement.setString(1, event.paymentKey());
            statement.setString(2, event.provider().name());
            statement.setString(3, event.country());
            statement.setString(4, event.status());
            statement.setString(5, event.eventId());
            statement.setObject(6, at(event.statusChangedAt()));
            statement.setObject(7, at(event.receivedAt()));
            statement.executeUpdate();
        }
    }

    private int update(String sql, StatementBinder binder) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new StoreUnavailableException("the outbox could not be updated", e);
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure is the one worth reporting.
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
