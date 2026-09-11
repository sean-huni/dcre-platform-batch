package za.co.fnb.dcre.platform.batch.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Telemetry config ({@code dcre.telemetry}) for the stage services, and the ONE place the
 * {@code dcre.telemetry.*} key names are written. The environment post-processor reads them from
 * here rather than repeating the literals, because it runs before any binding exists and would
 * otherwise be a second home for the same strings.
 *
 * @param enabled marker that activates telemetry. Deliberately NOT keyed on the endpoint: the
 *                orchestrator exports shared variables to every stage pod and relaxed binding
 *                canonicalises them, which is how the exchange autoconfig once activated fleet-wide
 *                and crashed non-writer services at startup (2026-07-14).
 * @param stage   OPTIONAL override for the stage token. Left unset, which is the normal case, the
 *                token is the service's own application package leaf, read from the class annotated
 *                {@code @SpringBootApplication}: every Boot application in the fleet sits at
 *                {@code za.co.fnb.dcre.<svc>}, so the leaf is one distinct lowercase code per
 *                deployable and no service has to restate its own name in config.
 *                <p>It is deliberately NOT the token a service hands its seam listener. That token
 *                is per-JOB rather than per-service: one service constructs it twice with different
 *                values and another passes a variable at the construction site, so two of the
 *                services would publish two {@code job} labels each. Setting a per-job shaped value
 *                here fails startup rather than publishing it, because {@code StageIdentity}
 *                refuses anything outside {@code dcre-[a-z]+}.
 */
@ConfigurationProperties(prefix = TelemetryProperties.PREFIX)
public record TelemetryProperties(boolean enabled, String stage) {

    public static final String PREFIX = "dcre.telemetry";

    /** The activation marker. Resolved in Java, never deferred to a placeholder: see the post-processor. */
    public static final String ENABLED = PREFIX + ".enabled";

    /** The explicit stage override, read before any binding exists. */
    public static final String STAGE = PREFIX + ".stage";
}
