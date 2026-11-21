package com.meridian.payments.intake.ops;

import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.canonical.CanonicalEvent;
import com.meridian.payments.intake.salesforce.SalesforceClient;
import com.meridian.payments.intake.salesforce.SalesforceRelay;
import com.meridian.payments.intake.store.Database;
import com.meridian.payments.intake.store.Destination;
import com.meridian.payments.intake.store.PostgresIntakeStore;
import com.meridian.payments.intake.store.PostgresSalesforceStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

import static com.meridian.payments.intake.store.Acceptance.ACCEPTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

abstract class ReplayPostgresFixture {

    private static Database database;
    private static PostgresIntakeStore intake;
    static PostgresSalesforceStore salesforceStore;

    @BeforeAll
    static void openDatabase() {
        var env = jdbcEnvironment();
        assumeTrue(!env.get("INTAKE_JDBC_URL").isBlank(),
                "INTAKE_IT_JDBC_URL is not set: the PostgreSQL tests need a database");
        database = Database.open(env.get("INTAKE_JDBC_URL"), env.get("INTAKE_DB_USER"),
                env.get("INTAKE_DB_PASSWORD"));
        intake = new PostgresIntakeStore(database);
        salesforceStore = new PostgresSalesforceStore(database);
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    @BeforeEach
    void emptyTables() throws SQLException {
        execute("TRUNCATE replay_command, outbox_job, payment_event, payment_state, "
                + "salesforce_budget, salesforce_budget_call");
    }

    static Map<String, String> jdbcEnvironment() {
        Map<String, String> env = System.getenv();
        return Map.of("INTAKE_JDBC_URL", env.getOrDefault("INTAKE_IT_JDBC_URL", ""),
                "INTAKE_DB_USER", env.getOrDefault("INTAKE_IT_DB_USER", "intake"),
                "INTAKE_DB_PASSWORD", env.getOrDefault("INTAKE_IT_DB_PASSWORD", ""));
    }

    static ReplayService.Outcome replay(String eventId, String reason, String key) {
        return new ReplayService(database).replay("ANDINA", eventId, "SALESFORCE", reason, "ops-test", key);
    }

    static SalesforceRelay relay(SalesforceClient client) {
        return new SalesforceRelay(salesforceStore, client, 10);
    }

    static void accept(String eventId, String payment, String policy, String status, Instant changedAt) {
        assertEquals(ACCEPTED, intake.accept(new CanonicalEvent(
                eventId, Provider.ANDINA, "CO", "AN-CO-" + payment, payment, policy,
                12_500L, "COP", 2, status, null, null, "REFUNDED".equals(status) ? "UNKNOWN" : null,
                changedAt, changedAt, changedAt, eventId, "0".repeat(64))));
    }

    static String job(String eventId, Destination destination) throws SQLException {
        return scalar("""
                SELECT state || '/' || coalesce(outcome_code, '-') || '/' || attempts
                FROM outbox_job o JOIN payment_event e USING (intake_id)
                WHERE e.event_id = ? AND o.destination = ?
                """, eventId, destination.name());
    }

    static String scalar(String sql, String... parameters) throws SQLException {
        try (var connection = database.connection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    static void execute(String sql) throws SQLException {
        try (var connection = database.connection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
