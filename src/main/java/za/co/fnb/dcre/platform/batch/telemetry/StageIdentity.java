package za.co.fnb.dcre.platform.batch.telemetry;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The two labels every other number depends on. Prometheus derives {@code job} from
 * {@code service.name} and {@code instance} from {@code service.instance.id}. A stage that sets the
 * first and not the second writes every execution into ONE series, so each new pod's counters read
 * as a counter reset and rate() extrapolates from zero.
 *
 * <p>The token this identity is built from is the service's own application package leaf. Every
 * Boot application in the fleet sits at {@code za.co.fnb.dcre.<svc>}, so the leaf is one distinct
 * lowercase code per deployable, it is service-level by construction, and it is read from the
 * application class rather than hand-typed, which adds no new copy of the service's own name. It
 * also covers the deployables the orchestrator never launches as Jobs.
 *
 * <p>It is deliberately NOT the token a service hands its seam listener. That token is per-JOB
 * rather than per-service: one service constructs it twice with different values and another passes
 * a variable at the construction site, so deriving {@code service.name} from it would give those
 * services two {@code job} labels each. That is the same defect this class exists to prevent,
 * arriving from the other side.
 *
 * <p>Both components are validated in the canonical constructor and not only in {@link #of}, because
 * a record's canonical constructor cannot be narrowed and so can never be made unreachable: a
 * validation sited only in the factory is structurally unenforceable. The service-name pattern is
 * strict on purpose. A per-job shaped token such as {@code mrv-account-reference} is REFUSED, so
 * wiring one fails the service at startup instead of quietly publishing a second {@code job} label.
 */
public record StageIdentity(String serviceName, String instanceId) {

    private static final Pattern SERVICE_NAME = Pattern.compile("dcre-[a-z]+");

    public StageIdentity {
        if (serviceName == null || !SERVICE_NAME.matcher(serviceName).matches()) {
            throw new IllegalArgumentException("service.name must match dcre-<token>: " + serviceName);
        }
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("service.instance.id is blank");
        }
    }

    /**
     * @param stageToken the service's application package leaf, for example {@code crg}: lowercase
     *                   letters only, exactly one per deployable
     * @param hostname   the runtime's own name, so a series traces back to the process that
     *                   produced it. {@code HOSTNAME} is the pod name under Kubernetes and the
     *                   container id under compose. A blank hostname falls back to a unique local
     *                   id and never to the service name, because a collision there recreates the
     *                   defect this class exists to prevent.
     */
    public static StageIdentity of(final String stageToken, final String hostname) {
        if (stageToken == null || stageToken.isBlank()) {
            throw new IllegalArgumentException(
                    "stage token is blank: a service publishing as 'dcre-unknown' is worse than one "
                    + "publishing nothing, because the series looks healthy and names no stage");
        }
        String instance = (hostname == null || hostname.isBlank())
                ? "local-" + UUID.randomUUID()
                : hostname;
        return new StageIdentity("dcre-" + stageToken.trim(), instance);
    }
}
