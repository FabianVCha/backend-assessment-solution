package com.meridian.payments.intake.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The connection pool and the migration run, in one place.
 *
 * <p>The schema is applied by the same code that opens the pool, before the first query: a process
 * that cannot migrate refuses to start instead of running against a schema it does not know. Flyway
 * records what it applied and fails on a checksum that changed, so an edited migration is an error
 * rather than silent drift between environments.
 *
 * <p>The pool is bounded and small on purpose. Each replica holds a handful of connections; the
 * database, not the application, decides how many sessions it can carry.
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource dataSource;

    private Database(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static Database open(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setPoolName("intake-store");
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(2_000);

        HikariDataSource dataSource = new HikariDataSource(config);
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
        return new Database(dataSource);
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
