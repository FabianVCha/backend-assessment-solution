package com.meridian.payments.intake.ops;

import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.store.Database;
import com.meridian.payments.intake.store.Destination;
import com.meridian.payments.intake.store.StoreUnavailableException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Audits and resets one existing job in one transaction. The unique command key prevents retries
 * from resetting work twice, and the job row lock keeps a replay from stealing a worker's claim.
 * The original event and payment projection are never changed: the relay still decides ordering.
 */
public final class ReplayService {

    public enum Outcome {
        REQUEUED, KEY_REQUIRED, KEY_TARGET_CONFLICT, REQUESTER_REQUIRED, REASON_REQUIRED,
        PROVIDER_UNKNOWN, DESTINATION_UNKNOWN, EVENT_NOT_FOUND, JOB_NOT_FOUND, JOB_CLAIMED,
        RECONCILIATION_REQUIRED, JOB_NOT_REPLAYABLE
    }

    private static final String REGISTER = """
            INSERT INTO replay_command
                (key_scope, idempotency_key, provider, event_id, destination, reason, requester, outcome)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'RECEIVED')
            ON CONFLICT (key_scope, idempotency_key) DO NOTHING
            """;
    private static final String READ_OUTCOME = """
            SELECT provider, event_id, destination, outcome FROM replay_command
            WHERE key_scope = ? AND idempotency_key = ?
            """;
    private static final String RECORD_OUTCOME = """
            UPDATE replay_command SET outcome = ? WHERE key_scope = ? AND idempotency_key = ?
            """;
    private static final String FIND_EVENT = """
            SELECT intake_id, canonical ->> 'country' AS country
            FROM payment_event WHERE provider = ? AND event_id = ?
            """;
    private static final String LOCK_JOB = """
            SELECT outbox_id, state, attempts, outcome_code
            FROM outbox_job WHERE intake_id = ? AND destination = ? FOR UPDATE
            """;
    private static final String RESET = """
            UPDATE outbox_job
            SET state = 'PENDING', outcome_code = 'REPLAY', attempts = 0, next_attempt_at = now(),
                last_error = NULL, published_at = NULL,
                claim_owner = NULL, claim_token = NULL, claim_expires_at = NULL
            WHERE outbox_id = ?
            """;

    private final Database database;

    public ReplayService(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public Outcome replay(String provider, String eventId, String destination, String reason,
                          String requester, String idempotencyKey) {
        return replay(provider, eventId, destination, reason, requester, idempotencyKey, null);
    }

    Outcome replayScoped(OpsIdentity.Caller caller, String provider, String eventId,
                         String destination, String reason, String idempotencyKey) {
        return replay(provider, eventId, destination, reason, caller.subject(), idempotencyKey, caller.countries());
    }

    private Outcome replay(String provider, String eventId, String destination, String reason,
                           String requester, String idempotencyKey, Set<String> countries) {
        if (blank(idempotencyKey)) {
            return Outcome.KEY_REQUIRED;
        }
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                Event scopedEvent = null;
                String keyScope = "CLI";
                if (countries != null) {
                    try (var timeout = connection.createStatement()) {
                        timeout.execute("SET LOCAL statement_timeout = '5s'");
                    }
                    scopedEvent = findEvent(connection, provider, eventId, countries);
                    if (scopedEvent == null) {
                        connection.rollback();
                        return Outcome.EVENT_NOT_FOUND;
                    }
                    keyScope = scopedEvent.country();
                }
                Outcome outcome;
                if (register(connection, keyScope, idempotencyKey, provider, eventId, destination, reason, requester)) {
                    if (blank(reason)) {
                        outcome = Outcome.REASON_REQUIRED;
                    } else if (blank(requester)) {
                        outcome = Outcome.REQUESTER_REQUIRED;
                    } else if (!known(Provider.class, provider)) {
                        outcome = Outcome.PROVIDER_UNKNOWN;
                    } else if (!known(Destination.class, destination)) {
                        outcome = Outcome.DESTINATION_UNKNOWN;
                    } else {
                        Event event = countries == null ? findEvent(connection, provider, eventId, null) : scopedEvent;
                        outcome = event == null ? Outcome.EVENT_NOT_FOUND
                                : resetJob(connection, event.intakeId(), destination);
                    }
                    if (countries != null && outcome != Outcome.REQUEUED) {
                        // HTTP records completed queue resets only. Roll back the provisional key
                        // along with any work; the legacy CLI still audits rejected decisions.
                        connection.rollback();
                        return outcome;
                    }
                    try (var record = connection.prepareStatement(RECORD_OUTCOME)) {
                        record.setString(1, outcome.name());
                        record.setString(2, keyScope);
                        record.setString(3, idempotencyKey);
                        record.executeUpdate();
                    }
                } else {
                    // A conflicting insert waits for the first command to commit. This fresh
                    // statement sees its final outcome, not the transaction-local RECEIVED value.
                    try (var read = connection.prepareStatement(READ_OUTCOME)) {
                        read.setString(1, keyScope);
                        read.setString(2, idempotencyKey);
                        try (var rows = read.executeQuery()) {
                            if (!rows.next()) {
                                throw new SQLException("the submitted replay key has no outcome");
                            }
                            // The key identifies the original target, not permission to claim that
                            // some other event or lane was requeued. Keep the original audit intact.
                            if (Objects.equals(provider, rows.getString("provider"))
                                    && Objects.equals(eventId, rows.getString("event_id"))
                                    && Objects.equals(destination, rows.getString("destination"))) {
                                outcome = Outcome.valueOf(rows.getString("outcome"));
                            } else {
                                outcome = Outcome.KEY_TARGET_CONFLICT;
                            }
                        }
                    }
                }
                connection.commit();
                return outcome;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("the replay command could not be completed", e);
        }
    }

    private static boolean register(Connection connection, String... values) throws SQLException {
        try (var register = connection.prepareStatement(REGISTER)) {
            for (int index = 0; index < values.length; index++) {
                register.setString(index + 1, values[index]);
            }
            return register.executeUpdate() == 1;
        }
    }

    private record Event(UUID intakeId, String country) {}

    private static Event findEvent(Connection connection, String provider, String eventId, Set<String> countries)
            throws SQLException {
        String sql = countries == null ? FIND_EVENT : FIND_EVENT + " AND canonical ->> 'country' = ANY(?)";
        try (var event = connection.prepareStatement(sql)) {
            event.setString(1, provider);
            event.setString(2, eventId);
            if (countries != null) {
                event.setArray(3, connection.createArrayOf("text", countries.toArray()));
            }
            try (var rows = event.executeQuery()) {
                return rows.next() ? new Event(rows.getObject("intake_id", UUID.class), rows.getString("country"))
                        : null;
            }
        }
    }

    private static Outcome resetJob(Connection connection, UUID intakeId, String destination) throws SQLException {
        long outboxId;
        try (var job = connection.prepareStatement(LOCK_JOB)) {
            job.setObject(1, intakeId);
            job.setString(2, destination);
            try (var rows = job.executeQuery()) {
                if (!rows.next()) {
                    return Outcome.JOB_NOT_FOUND;
                }
                String state = rows.getString("state");
                if ("CLAIMED".equals(state)) {
                    return Outcome.JOB_CLAIMED;
                }
                if ("HELD".equals(state)) {
                    return Outcome.RECONCILIATION_REQUIRED;
                }
                if (!"DEAD".equals(state) && !("PENDING".equals(state)
                        && rows.getInt("attempts") > 0 && !"RATE_LIMITED".equals(rows.getString("outcome_code"))
                        && !"BUDGET_EXHAUSTED".equals(rows.getString("outcome_code"))
                        && !"REPLAY".equals(rows.getString("outcome_code")))) {
                    return Outcome.JOB_NOT_REPLAYABLE;
                }
                outboxId = rows.getLong("outbox_id");
            }
        }
        try (var reset = connection.prepareStatement(RESET)) {
            reset.setLong(1, outboxId);
            reset.executeUpdate();
        }
        return Outcome.REQUEUED;
    }

    private static <T extends Enum<T>> boolean known(Class<T> type, String value) {
        if (blank(value)) {
            return false;
        }
        try {
            Enum.valueOf(type, value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
