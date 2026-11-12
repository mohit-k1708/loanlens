package com.loanlens.it;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * One real PostgreSQL server for the whole test JVM (no Docker, no local installation needed).
 * Each Spring test context that needs isolated data uses its own database on it.
 */
public final class PostgresTestSupport {

    private static final EmbeddedPostgres SERVER = start();

    private PostgresTestSupport() {
    }

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (IOException ignored) {
                    // best effort on JVM exit
                }
            }));
            return pg;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the embedded PostgreSQL used by the integration tests", e);
        }
    }

    /** JDBC URL of the given database, creating it first if needed. */
    public static synchronized String jdbcUrl(String database) {
        if (!"postgres".equals(database)) {
            try (Connection c = SERVER.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
                boolean exists = s.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + database + "'").next();
                if (!exists) {
                    s.execute("CREATE DATABASE " + database);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not create test database " + database, e);
            }
        }
        return SERVER.getJdbcUrl("postgres", database);
    }
}
