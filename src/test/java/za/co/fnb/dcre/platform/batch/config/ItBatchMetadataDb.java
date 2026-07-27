package za.co.fnb.dcre.platform.batch.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;

import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The single CockroachDB container carrying the {@code IT_BATCH_} metadata schema,
 * shared by every batch-metadata IT in this JVM. One container per test class is what
 * fills the Docker VM, so the container and the schema are owned here and started once
 * by this class's initializer.
 */
final class ItBatchMetadataDb {

    static final String TABLE_PREFIX = "IT_BATCH_";

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
        applySchema();
    }

    private ItBatchMetadataDb() {
    }

    /** Forces this class's initializer, so the container is up before a test needs it. */
    static void ensureStarted() {
        CRDB.getJdbcUrl();
    }

    static String url() {
        return CRDB.getJdbcUrl();
    }

    static String username() {
        return CRDB.getUsername();
    }

    static String password() {
        return CRDB.getPassword();
    }

    private static void applySchema() {
        try (InputStream in = ItBatchMetadataDb.class.getResourceAsStream("/batch-metadata-it.sql");
             Connection c = DriverManager.getConnection(CRDB.getJdbcUrl(), CRDB.getUsername(), CRDB.getPassword())) {
            final String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (final String raw : script.split(";")) {
                final String stmt = Arrays.stream(raw.split("\n"))
                        .filter(line -> !line.trim().startsWith("--"))
                        .reduce("", (a, b) -> a + "\n" + b).trim();
                if (!stmt.isEmpty()) {
                    try (Statement s = c.createStatement()) {
                        s.execute(stmt);
                    }
                }
            }
        } catch (final Exception e) {
            throw new IllegalStateException("failed to apply the %s schema".formatted(TABLE_PREFIX), e);
        }
    }
}
