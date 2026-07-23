package za.co.fnb.dcre.platform.batch.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 * <p><b>Fail-closed (no masking default).</b> There is deliberately NO default:
 * all 12 services still set only the dead {@code spring.batch.jdbc.table-prefix}
 * today, so a service that imports {@code BatchJdbcConfig} without re-keying
 * must fail loudly at context start rather than silently binding {@code BATCH_}
 * and either dying later on {@code relation "BATCH_JOB_INSTANCE" does not exist}
 * or (worse) cross-writing a shared unprefixed table (R-04 collision). The guard
 * rejects null/blank, the bare {@code BATCH_} canonical default, and any value
 * that does not end in {@code _} (a missing trailing underscore mis-names tables).
 */
@ConfigurationProperties(prefix = "dcre.batch")
public record BatchProperties(String tablePrefix) {

    public BatchProperties {
        if (tablePrefix == null || tablePrefix.isBlank()
                || tablePrefix.equals("BATCH_") || !tablePrefix.endsWith("_")) {
            throw new IllegalStateException(
                    "set dcre.batch.table-prefix to <SVC>_BATCH_ (Boot 4.1 no longer binds "
                            + "spring.batch.jdbc.table-prefix); got: " + tablePrefix);
        }
    }
}
