package za.co.fnb.dcre.platform.batch.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StageIdentityTest {

    @Test
    void prefixesTheStageTokenSoJobLabelsAreUnambiguous() {
        StageIdentity id = StageIdentity.of("crg", "dcre-crg-abc123");
        assertThat(id.serviceName()).isEqualTo("dcre-crg");
    }

    @Test
    void takesTheInstanceIdFromTheHostnameSoEveryExecutionIsItsOwnSeries() {
        StageIdentity id = StageIdentity.of("crg", "dcre-crg-abc123");
        assertThat(id.instanceId()).isEqualTo("dcre-crg-abc123");
    }

    @Test
    void twoExecutionsOfOneStageDoNotShareAnInstanceId() {
        assertThat(StageIdentity.of("crg", "pod-a").instanceId())
                .isNotEqualTo(StageIdentity.of("crg", "pod-b").instanceId());
    }

    @Test
    void aBlankHostnameFallsBackToSomethingUniqueRatherThanToTheServiceName() {
        StageIdentity a = StageIdentity.of("crg", "");
        StageIdentity b = StageIdentity.of("crg", "");
        assertThat(a.instanceId()).isNotEqualTo(b.instanceId());
        assertThat(a.instanceId()).isNotEqualTo("dcre-crg");
    }

    @Test
    void rejectsABlankStageTokenRatherThanPublishingAnUnknownJob() {
        assertThatThrownBy(() -> StageIdentity.of("  ", "pod-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stage token");
    }

    // A per-job token is what this class must never be built from. The seam token is per-JOB:
    // one service constructs it twice with different values and another passes a variable, so
    // deriving service.name from it hands those services two job labels each. The strict pattern
    // turns that silent second label into a startup failure.
    @Test
    void refusesAPerJobShapedTokenSoASecondJobLabelCannotBePublishedQuietly() {
        assertThatThrownBy(() -> StageIdentity.of("mrv-account-reference", "pod-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service.name");
    }

    @Test
    void refusesAStageTokenWithAnInnerSpace() {
        assertThatThrownBy(() -> StageIdentity.of("cr g", "pod-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service.name");
    }

    @Test
    void refusesAnUppercaseStageToken() {
        assertThatThrownBy(() -> StageIdentity.of("CRG", "pod-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service.name");
    }

    @Test
    void refusesAStageTokenContainingASlash() {
        assertThatThrownBy(() -> StageIdentity.of("a/b", "pod-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service.name");
    }

    // The one that mattered most: an inner newline survives trim() and would otherwise put a
    // literal line break inside a Prometheus job label.
    @Test
    void refusesAStageTokenCarryingANewline() {
        assertThatThrownBy(() -> StageIdentity.of("cr\ng", "pod-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service.name");
    }

    // A record's canonical constructor cannot be narrowed, so of() can never be the only door.
    // These two assert the door is guarded rather than merely unadvertised.
    @Test
    void theCanonicalConstructorRefusesAServiceNameThatIsNotAJobLabel() {
        assertThatThrownBy(() -> new StageIdentity("garbage", "pod-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service.name");
    }

    @Test
    void theCanonicalConstructorRefusesABlankInstanceId() {
        assertThatThrownBy(() -> new StageIdentity("dcre-crg", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service.instance.id");
    }
}
