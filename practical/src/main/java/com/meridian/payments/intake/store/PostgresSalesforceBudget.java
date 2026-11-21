package com.meridian.payments.intake.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

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
                SELECT (midnight AT TIME ZONE 'UTC')::date, 1, 1
                FROM clock
                WHERE COALESCE((SELECT calls FROM salesforce_budget
                                WHERE utc_day = (midnight AT TIME ZONE 'UTC')::date), 0)
                    + (SELECT count(*) FROM salesforce_budget_call
                       WHERE admitted_at >= at - interval '24 hours' AND admitted_at < midnight) < 150000
                  AND (SELECT COALESCE(sum(in_flight), 0)
                       FROM salesforce_budget WHERE in_flight > 0) < 25
                ON CONFLICT (utc_day) DO UPDATE SET calls = salesforce_budget.calls + 1,
                                                   in_flight = salesforce_budget.in_flight + 1
                RETURNING utc_day
            ), recorded AS (
                INSERT INTO salesforce_budget_call (admitted_at)
                SELECT at FROM admitted CROSS JOIN clock
            )
            SELECT utc_day FROM admitted
            """;
    private static final String RELEASE = """
            UPDATE salesforce_budget SET in_flight = in_flight - 1 WHERE utc_day = ?
            """;

    private final Database database;

    PostgresSalesforceBudget(Database database) {
        this.database = database;
    }

    Optional<LocalDate> tryAcquire() {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            // A separate statement gives READ COMMITTED a fresh snapshot after waiting for the lock.
            try (var lock = connection.createStatement()) {
                lock.execute(LOCK);
            }
            Optional<LocalDate> day;
            try (var admission = connection.createStatement(); var rows = admission.executeQuery(ADMIT)) {
                day = rows.next() ? Optional.of(rows.getObject(1, LocalDate.class)) : Optional.empty();
            }
            connection.commit();
            return day;
        } catch (SQLException e) {
            throw new StoreUnavailableException("Salesforce capacity could not be reserved", e);
        }
    }

    void release(LocalDate day) {
        // Use the reservation's day, not the current day: the call may have crossed midnight.
        try (Connection connection = database.connection(); var release = connection.prepareStatement(RELEASE)) {
            release.setObject(1, day);
            if (release.executeUpdate() != 1) {
                throw new StoreUnavailableException("the Salesforce reservation day is missing");
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("Salesforce capacity could not be released", e);
        }
    }
}
