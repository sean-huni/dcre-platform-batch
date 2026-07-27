package za.co.fnb.dcre.platform.batch.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-101 Red -> Green: pins the JDBC-URL rewrite that carries the CockroachDB
 * {@code multiple_active_portals_enabled} session variable onto the Spring Batch
 * metadata connection only.
 *
 * <p>Red baseline (class absent): does not compile. The behaviour under test is
 * pure, so it is asserted without a container: the four shapes a fleet URL can
 * take (bare, already-parameterised, already carrying an {@code options=}
 * parameter, non-CockroachDB) plus idempotency on re-application.
 */
class BatchMetadataUrlTest {

    private static final String CRDB = "CockroachDB CCL v26.2.3 (x86_64-pc-linux-gnu, built 2026/05/12)";
    private static final String PG = "PostgreSQL 18.0 (Debian 18.0-1.pgdg13+3) on x86_64-pc-linux-gnu";
    private static final String OPT = "options=-c%20multiple_active_portals_enabled%3Dtrue";

    @Test
    void appendsOptionWithQuestionMarkWhenUrlHasNoQueryString() {
        assertThat(BatchMetadataUrl.forBanner("jdbc:postgresql://crdb:26257/dcre_col", CRDB))
                .isEqualTo("jdbc:postgresql://crdb:26257/dcre_col?" + OPT);
    }

    @Test
    void appendsOptionWithAmpersandWhenUrlAlreadyHasQueryString() {
        assertThat(BatchMetadataUrl.forBanner("jdbc:postgresql://crdb:26257/dcre_col?sslmode=disable", CRDB))
                .isEqualTo("jdbc:postgresql://crdb:26257/dcre_col?sslmode=disable&" + OPT);
    }

    @Test
    void preservesAnExistingOptionsParameterInsteadOfClobberingIt() {
        assertThat(BatchMetadataUrl.forBanner(
                "jdbc:postgresql://crdb:26257/dcre_col?options=-c%20statement_timeout%3D5s", CRDB))
                .isEqualTo("jdbc:postgresql://crdb:26257/dcre_col"
                        + "?options=-c%20statement_timeout%3D5s%20-c%20multiple_active_portals_enabled%3Dtrue");
    }

    @Test
    void preservesAnExistingOptionsParameterThatIsNotTheLastParameter() {
        assertThat(BatchMetadataUrl.forBanner(
                "jdbc:postgresql://crdb:26257/dcre_col?options=-c%20statement_timeout%3D5s&sslmode=disable", CRDB))
                .isEqualTo("jdbc:postgresql://crdb:26257/dcre_col"
                        + "?options=-c%20statement_timeout%3D5s%20-c%20multiple_active_portals_enabled%3Dtrue"
                        + "&sslmode=disable");
    }

    @Test
    void leavesANonCockroachUrlUntouched() {
        final String url = "jdbc:postgresql://pg:5432/dcre_col?sslmode=disable";
        assertThat(BatchMetadataUrl.forBanner(url, PG)).isEqualTo(url);
    }

    @Test
    void leavesTheUrlUntouchedWhenTheVersionBannerIsUnavailable() {
        final String url = "jdbc:postgresql://crdb:26257/dcre_col";
        assertThat(BatchMetadataUrl.forBanner(url, null)).isEqualTo(url);
    }

    @Test
    void isIdempotentWhenTheSessionVariableIsAlreadyPresent() {
        final String url = "jdbc:postgresql://crdb:26257/dcre_col?" + OPT;
        assertThat(BatchMetadataUrl.forBanner(url, CRDB)).isEqualTo(url);
    }

    @Test
    void doesNotMistakeAParameterMerelyEndingInOptionsForTheOptionsParameter() {
        assertThat(BatchMetadataUrl.forBanner("jdbc:postgresql://crdb:26257/dcre_col?myoptions=-c%20x%3D1", CRDB))
                .isEqualTo("jdbc:postgresql://crdb:26257/dcre_col?myoptions=-c%20x%3D1&" + OPT);
    }
}
