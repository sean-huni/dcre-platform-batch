package za.co.fnb.dcre.platform.batch.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * SCRUM-84: per-service Spring Batch metadata table prefix, bound from
 * {@code dcre.batch.table-prefix}. Every consuming service sets it to its own
 * {@code <SVC>_BATCH_} value (e.g. {@code CRR_BATCH_}), so a single shared
 * {@link za.co.fnb.dcre.platform.batch.config.BatchJdbcConfig} writes each
 * service's own prefixed tables.
 *
 * <p>Re-homes the now-dead {@code spring.batch.jdbc.table-prefix}: Boot 4.1 /
 * Batch 6 stopped binding {@code spring.batch.jdbc.*}, so the prefix must be
 * carried by live config that {@code BatchJdbcConfig} reads directly.
 *
 * <p>The {@code BATCH_} default is the Spring Batch canonical prefix and only
 * applies if a service forgets to override; the batch DAOs then fail loudly
 * against missing {@code BATCH_*} tables rather than writing the wrong schema.
 */
@ConfigurationProperties(prefix = "dcre.batch")
public record BatchProperties(@DefaultValue("BATCH_") String tablePrefix) {

    public BatchProperties {
        if (tablePrefix == null || tablePrefix.isBlank()) {
            tablePrefix = "BATCH_";
        }
    }
}
