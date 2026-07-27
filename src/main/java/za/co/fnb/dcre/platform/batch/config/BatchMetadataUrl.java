package za.co.fnb.dcre.platform.batch.config;

/**
 * SCRUM-101: builds the JDBC URL for the Spring Batch metadata connection by adding
 * CockroachDB's {@code multiple_active_portals_enabled} session variable, and only
 * when the target really is CockroachDB.
 *
 * <p><b>Why.</b> Batch 6.0.4's {@code JdbcStepExecutionDao.getLastStepExecution}
 * (line 341) maps its rows inside an open {@code ResultSet} and calls
 * {@code JdbcJobExecutionDao.getJobParameters} (line 450), which prepares a SECOND
 * statement on the SAME connection. PostgreSQL allows the extra portal; CockroachDB
 * rejects it ("unimplemented: multiple active portals is in preview"), Hikari marks
 * the connection broken, and every RESTART of an existing job instance dies.
 *
 * <p><b>Scope.</b> The option is carried by the URL, so it applies to the dedicated
 * Batch-metadata pool only ({@link BatchMetadataPool}); business datasources keep
 * CockroachDB's default single-portal behaviour.
 *
 * <p><b>Encoding.</b> pgjdbc URL-decodes query-parameter values, so the option is
 * written the way the live cluster repro was verified:
 * {@code options=-c%20multiple_active_portals_enabled%3Dtrue}, which reaches the
 * server as {@code -c multiple_active_portals_enabled=true}. An {@code options=}
 * parameter that already exists is extended, never replaced, and a URL that already
 * carries the variable is returned unchanged.
 */
final class BatchMetadataUrl {

    static final String SESSION_VARIABLE = "multiple_active_portals_enabled";
    static final String OPTION = "-c%20" + SESSION_VARIABLE + "%3Dtrue";

    private static final String COCKROACH_BANNER = "CockroachDB";
    private static final String OPTIONS_PARAM = "options=";

    private BatchMetadataUrl() {
    }

    /**
     * @param versionBanner the {@code SELECT version()} banner of the target database,
     *                      or {@code null} when it could not be read
     * @return {@code true} when the banner identifies a CockroachDB server
     */
    static boolean isCockroach(final String versionBanner) {
        return versionBanner != null && versionBanner.startsWith(COCKROACH_BANNER);
    }

    /**
     * @return {@code url} with the multiple-active-portals option added when the banner
     *         says CockroachDB and the URL does not carry it yet; {@code url} unchanged
     *         otherwise (a plain PostgreSQL server would reject the unknown variable)
     */
    static String forBanner(final String url, final String versionBanner) {
        if (url == null || !isCockroach(versionBanner) || url.contains(SESSION_VARIABLE)) {
            return url;
        }
        final int valueStart = optionsValueStart(url);
        if (valueStart < 0) {
            return url + (url.indexOf('?') < 0 ? '?' : '&') + OPTIONS_PARAM + OPTION;
        }
        final int next = url.indexOf('&', valueStart);
        final int valueEnd = next < 0 ? url.length() : next;
        return url.substring(0, valueEnd) + "%20" + OPTION + url.substring(valueEnd);
    }

    /** Index just past an existing {@code options=} parameter name, or -1 when absent. */
    private static int optionsValueStart(final String url) {
        for (int separator = url.indexOf('?'); separator >= 0; separator = url.indexOf('&', separator + 1)) {
            if (url.startsWith(OPTIONS_PARAM, separator + 1)) {
                return separator + 1 + OPTIONS_PARAM.length();
            }
        }
        return -1;
    }
}
