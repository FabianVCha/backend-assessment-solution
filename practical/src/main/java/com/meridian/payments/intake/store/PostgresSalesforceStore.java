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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The Salesforce lane in PostgreSQL.
 *
 * <p>Three properties live here, and each one is a question a reviewer should ask:
 *
 * <ol>
 *   <li><b>One writer per payment.</b> The claim excludes any job whose payment already has a write in
 *       flight, so two replicas do not push two facts for one payment at the same time. The exclusion is
 *       a filter on the claim query, checked under the same statement that takes the row; the residual
 *       window is documented rather than hidden — two claims can still begin in the same instant, and only
 *       the destination can close that completely.
 *   <li><b>Ordering by the provider's clock.</b> The relay reads the payment projection before it writes.
 *       An event older than the payment's newest fact is not written at all: a delayed capture must not
 *       replace a reversal that already landed.
 *   <li><b>Three ways to end.</b> Applied, terminal, or ambiguous. {@code HELD} is reserved for the last
 *       one; a timeout that might have landed is never marked failed and never marked done.
 * </ol>
 */
public final class PostgresSalesforceStore implements SalesforceStore {

    private static final String CLAIMABLE = """
            SELECT o.outbox_id
            FROM outbox_job o
            JOIN payment_event e ON e.intake_id = o.intake_id
            WHERE o.destination = 'SALESFORCE'
              AND ((o.state = 'PENDING' AND o.next_attempt_at <= now())
                   OR (o.state = 'CLAIMED' AND o.claim_expires_at <= now()))
              AND NOT EXISTS (
                  SELECT 1
                  FROM outbox_job flight
                  JOIN payment_event other ON other.intake_id = flight.intake_id
                  WHERE flight.destination = 'SALESFORCE'
                    AND other.payment_key = e.payment_key
                    AND flight.outbox_id <> o.outbox_id
                    AND flight.state = 'CLAIMED'
                    AND flight.claim_expires_at > now())
            ORDER BY o.next_attempt_at, o.outbox_id
            LIMIT ?
            FOR UPDATE OF o SKIP LOCKED
            """;

    private static final String MARK_CLAIMED = """
            UPDATE outbox_job
            SET state = 'CLAIMED', claim_owner = ?, claim_token = CAST(? AS uuid),
                claim_expires_at = now() + make_interval(secs => ?)
            WHERE outbox_id = ANY(?)
            """;

    private static final String CLAIMED_JOBS = """
            SELECT e.provider, e.event_id, e.canonical
            FROM outbox_job o
            JOIN payment_event e ON e.intake_id = o.intake_id
            WHERE o.outbox_id = ANY(?)
            ORDER BY o.next_attempt_at, o.outbox_id
            """;

    private static final String PROJECTION = """
            SELECT latest_status, latest_status_changed_at, remote_applied_status
            FROM payment_state WHERE payment_key = ?
            """;

    private static final String COMPLETE = """
            UPDATE outbox_job o
            SET state = ?, outcome_code = ?, claim_owner = NULL, claim_token = NULL,
                claim_expires_at = NULL,
                published_at = CASE WHEN ? = 'PUBLISHED' THEN now() ELSE o.published_at END
            FROM payment_event e
            WHERE o.intake_id = e.intake_id
              AND e.provider = ? AND e.event_id = ? AND o.destination = 'SALESFORCE'
              AND o.claim_token = CAST(? AS uuid)
            """;

    private static final String MARK_APPLIED = """
            UPDATE payment_state SET remote_applied_status = ?, updated_at = now()
            WHERE payment_key = ?
            """;

    private static final String RESCHEDULE = """
            UPDATE outbox_job o
            SET state = 'PENDING', attempts = o.attempts + 1, outcome_code = ?,
                claim_owner = NULL, claim_token = NULL, claim_expires_at = NULL,
                next_attempt_at = now() + make_interval(secs => LEAST(60, (1 << LEAST(o.attempts, 6))))
            FROM payment_event e
            WHERE o.intake_id = e.intake_id
              AND e.provider = ? AND e.event_id = ? AND o.destination = 'SALESFORCE'
              AND o.claim_token = CAST(? AS uuid)
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Database database;

    public PostgresSalesforceStore(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public List<PendingJob> claimDue(int limit, String owner, Duration lease) {
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
                List<Long> ids = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(CLAIMABLE)) {
                    statement.setInt(1, limit);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            ids.add(rows.getLong("outbox_id"));
                        }
                    }
                }
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
                                    new OutboxJob(provider, eventId, Destination.SALESFORCE), event, token));
                        }
                    }
                }
                connection.commit();
                return claimed;
            } catch (SQLException | JsonProcessingException e) {
                rollbackQuietly(connection);
                throw new StoreUnavailableException("the salesforce lane could not be claimed", e);
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("the salesforce lane could not be claimed", e);
        }
    }

    @Override
    public PaymentProjection projection(String paymentKey) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(PROJECTION)) {
            statement.setString(1, paymentKey);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new PaymentProjection(
                        rows.getString("latest_status"),
                        rows.getObject("latest_status_changed_at", OffsetDateTime.class).toInstant(),
                        rows.getString("remote_applied_status"));
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("the payment projection could not be read", e);
        }
    }

    @Override
    public void applied(PendingJob job) {
        Objects.requireNonNull(job, "job");
        complete(job, "PUBLISHED", "APPLIED");
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(MARK_APPLIED)) {
            statement.setString(1, job.event().status());
            statement.setString(2, job.event().paymentKey());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new StoreUnavailableException("the applied status could not be recorded", e);
        }
    }

    @Override
    public void skipped(PendingJob job) {
        complete(job, "PUBLISHED", "STALE_SKIPPED");
    }

    @Override
    public void dead(PendingJob job, String code) {
        complete(job, "DEAD", code);
    }

    @Override
    public void held(PendingJob job, String code) {
        complete(job, "HELD", code);
    }

    @Override
    public void failed(PendingJob job, String code) {
        Objects.requireNonNull(job, "job");
        requireClaim(job);
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(RESCHEDULE)) {
            statement.setString(1, code);
            statement.setString(2, job.job().provider().name());
            statement.setString(3, job.job().eventId());
            statement.setString(4, job.claimToken());
            if (statement.executeUpdate() == 0) {
                throw new ClaimLostException("the lease on " + job.job() + " belongs to another worker now");
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("the salesforce lane could not be rescheduled", e);
        }
    }

    private void complete(PendingJob job, String state, String code) {
        Objects.requireNonNull(job, "job");
        requireClaim(job);
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(COMPLETE)) {
            statement.setString(1, state);
            statement.setString(2, code);
            statement.setString(3, state);
            statement.setString(4, job.job().provider().name());
            statement.setString(5, job.job().eventId());
            statement.setString(6, job.claimToken());
            if (statement.executeUpdate() == 0) {
                throw new ClaimLostException("the lease on " + job.job() + " belongs to another worker now");
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("the salesforce lane could not be completed", e);
        }
    }

    private static void requireClaim(PendingJob job) {
        if (!job.claimed()) {
            throw new IllegalArgumentException("a job must be claimed before it can be completed");
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
}
