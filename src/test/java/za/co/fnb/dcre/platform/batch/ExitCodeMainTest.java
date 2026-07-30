package za.co.fnb.dcre.platform.batch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.ResolvableType;

import java.time.Duration;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-34 / config-plane failure classification: a failure BEFORE the runner phase
 * is infrastructure, not a job outcome, and must leave the JVM on the reserved
 * code 78 so AGT can classify it as TECH_CONFIG_FAILED instead of burning the
 * TECH_FAILED orphan budget.
 */
class ExitCodeMainTest {

    private static ApplicationStartedEvent startedEvent() {
        // Context is never touched by the gate; an unrefreshed one keeps the test cheap.
        return new ApplicationStartedEvent(new SpringApplication(), new String[0],
                new AnnotationConfigApplicationContext(), Duration.ZERO);
    }

    @Test
    void reservedCodeIsSysexitsExConfig() {
        assertEquals(78, ExitCodeMain.CONFIG_FAILURE_EXIT_CODE);
    }

    @Test
    void failureBeforeRunnerPhaseTakesTheReservedCode() {
        assertEquals(OptionalInt.of(78),
                ExitCodeMain.exitCodeFor(false, new IllegalStateException("config import not found")));
    }

    @Test
    void failureAfterRunnerPhaseStartedIsPropagatedUnchanged() {
        // Empty = ExitCodeMain rethrows, so a real job failure still dies exactly
        // as it does today (uncaught exception, JVM status 1, TECH_FAILED at AGT).
        assertEquals(OptionalInt.empty(),
                ExitCodeMain.exitCodeFor(true, new IllegalStateException("step blew up")));
    }

    @Test
    void successfulRunIsNeverOverridden() {
        // No failure at all: the SpringApplication.exit code stands (Batch verdict, R-34).
        assertEquals(OptionalInt.empty(), ExitCodeMain.exitCodeFor(true, null));
        assertEquals(OptionalInt.empty(), ExitCodeMain.exitCodeFor(false, null));
    }

    @Test
    void gateStartsClosedAndOpensOnApplicationStartedEvent() {
        final RunnerPhaseGate gate = new RunnerPhaseGate();
        assertFalse(gate.reached(), "gate must be closed until the started event fires");
        gate.onApplicationEvent(startedEvent());
        assertTrue(gate.reached(), "gate must open before callRunners");
    }

    @Test
    void gateEventTypeIsResolvableSoSpringActuallyDispatchesToIt() {
        // A lambda listener would leave this generic unresolvable: Spring then
        // dispatches every event to it and swallows the ClassCastException, so
        // the gate would never open and every job failure would exit 78.
        assertEquals(ApplicationStartedEvent.class,
                ResolvableType.forClass(ApplicationListener.class, RunnerPhaseGate.class)
                        .getGeneric(0).resolve());
    }

    @Test
    void gateRunsBeforeAnyOtherStartedListener() {
        // Highest precedence: the gate records "we got past refresh" even when a
        // sibling started-listener throws, so that failure stays TECH_FAILED.
        assertEquals(Integer.MIN_VALUE, new RunnerPhaseGate().getOrder());
    }
}
