package za.co.fnb.dcre.platform.batch.telemetry;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.util.Map;

/**
 * The parent trace context a stage process inherits from the orchestrator, so one file arrival is
 * ONE trace rather than one trace per pod.
 *
 * <p><strong>Nothing reads this variable for you.</strong> Measured across all 65 jars on this
 * module's resolved runtime classpath, 2026-09-11: the literal {@code traceparent} appears in
 * exactly one class, {@link W3CTraceContextPropagator}, which is CARRIER-based. You hand it headers;
 * it never touches the process environment. The SDK's autoconfigure implementation, which does read
 * environment variables, is not on the classpath at all, only its service-provider interface, and
 * Boot's own {@code OpenTelemetryEnvironmentVariableEnvironmentPostProcessor} maps eight
 * {@code OTEL_*} variables of which this is not one. So before this class existed the variable
 * arrived on every stage pod, was ignored, and each pod started a fresh root trace while both halves
 * of the wiring looked correct in isolation.
 *
 * <p>The propagator is used rather than a hand-rolled split on {@code '-'} because the W3C format has
 * rules a split does not know: version prefixes this library has never seen must be accepted
 * forwards-compatibly, an all-zero trace id or span id is INVALID rather than merely odd, and the
 * flags byte is a bit field. A hand parser gets the happy path right and admits the malformed cases
 * that matter.
 *
 * <p><strong>Absent or malformed means a FRESH trace, never a failure.</strong> A stage that refused
 * to start because a telemetry header was mangled would have turned an observability defect into an
 * outage, and the value arrives from another process over an environment variable, which is exactly
 * the channel that can carry rubbish. It is also a routine, correct case rather than an error: AGT's
 * clock builders launch report windows, the MRG suspension sweep and the HCS holiday sync, none of
 * which any arrival triggered, so none of them carries a parent at all. The propagator already
 * behaves this way, returning the context it was given unchanged, and
 * {@code ParentTraceContextTest} asserts that rather than assuming it.
 */
public final class ParentTraceContext {

    /**
     * The variable AGT sets. It is NOT a name any framework resolves: it matches the W3C header name
     * so a human reading a pod spec recognises it, and this class is the only thing in the fleet
     * that turns it into a context.
     */
    public static final String TRACEPARENT = "TRACEPARENT";

    /** The carrier key the W3C propagator looks for, which is lower case and not the variable name. */
    private static final String HEADER = "traceparent";

    private static final TextMapGetter<Map<String, String>> GETTER =
            new TextMapGetter<>() {

                @Override
                public Iterable<String> keys(final Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(final Map<String, String> carrier, final String key) {
                    return carrier == null ? null : carrier.get(key);
                }
            };

    private ParentTraceContext() {
    }

    /**
     * @param traceparent the raw variable value, which may be null, blank or rubbish
     * @return the arrival's context when the value is a valid W3C trace context, and
     *         {@link Context#root()} otherwise. Never null and never throws.
     */
    public static Context extract(final String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Context.root();
        }
        return W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), Map.of(HEADER, traceparent.trim()), GETTER);
    }
}
