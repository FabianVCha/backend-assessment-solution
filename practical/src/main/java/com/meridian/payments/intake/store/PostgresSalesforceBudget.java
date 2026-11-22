package com.meridian.payments.intake.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Reserves shared Salesforce capacity before a write. Serializing admissions across UTC days
 * prevents two replicas taking the last slot, even across midnight. The lock is released on
 * commit, before contacting Salesforce, so independent requests can run concurrently.
 */
final class PostgresSalesforceBudget {

    private static final String LOCK = "SELECT pg_advisory_xact_lock(141422, 1)";
    private static final String ADMIT = """
            WITH clock AS (
                SELECT statement_timestamp() AS at,
                       date_trunc('day', statement_timestamp(), 'UTC') AS midnight
            ), admitted AS (
                INSERT INTO salesforce_budget (utc_day, calls, in_flight)
                SELECT (midnight AT TIME ZONE 'UTC')::date, 1, 0
                FROM clock
                WHERE COALESCE((SELECT calls FROM salesforce_budget
                                WHERE utc_day = (midnight AT TIME ZONE 'UTC')::date), 0)
                    + (SELECT count(*) FROM salesforce_budget_call
                       WHERE admitted_at >= at - interval '24 hours' AND admitted_at < midnight) < 150000
                  AND (SELECT count(*) FROM salesforce_budget_lease WHERE expires_at > at) < 25
                ON CONFLICT (utc_day) DO UPDATE SET calls = salesforce_budget.calls + 1
                RETURNING utc_day
            ), recorded AS (
                INSERT INTO salesforce_budget_call (admitted_at)
                SELECT at FROM admitted CROSS JOIN clock
            ), reserved AS (
                INSERT INTO salesforce_budget_lease (token, expires_at)
                SELECT ?, at + interval '30 seconds' FROM admitted CROSS JOIN clock
                RETURNING token
            )
            SELECT token FROM reserved
            """;
    private static final String RELEASE = """
            DELETE FROM salesforce_budget_lease WHERE token = ?
            """;

    private final Database database;

    PostgresSalesforceBudget(Database database) {
        this.database = database;
    }

    Optional<UUID> tryAcquire() {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            // A separate statement gives READ COMMITTED a fresh snapshot after waiting for the lock.
            try (var lock = connection.createStatement()) {
                lock.execute(LOCK);
            }
            try (var cleanup = connection.createStatement()) {
                cleanup.executeUpdate("DELETE FROM salesforce_budget_lease WHERE expires_at <= now()");
                cleanup.executeUpdate("DELETE FROM salesforce_budget_call WHERE admitted_at < now() - interval '24 hours'");
                cleanup.executeUpdate("DELETE FROM salesforce_budget WHERE utc_day < (now() AT TIME ZONE 'UTC')::date - 1");
            }
            Optional<UUID> token;
            try (var admission = connection.prepareStatement(ADMIT)) {
                admission.setObject(1, UUID.randomUUID());
                try (var rows = admission.executeQuery()) {
                    token = rows.next() ? Optional.of(rows.getObject(1, UUID.class)) : Optional.empty();
                }
            }
            connection.commit();
            return token;
        } catch (SQLException e) {
            throw new StoreUnavailableException("Salesforce capacity could not be reserved", e);
        }
    }

    void release(UUID token) {
        try (Connection connection = database.connection(); var release = connection.prepareStatement(RELEASE)) {
            release.setObject(1, token);
            release.executeUpdate();
        } catch (SQLException e) {
            throw new StoreUnavailableException("Salesforce capacity could not be released", e);
        }
    }
}
