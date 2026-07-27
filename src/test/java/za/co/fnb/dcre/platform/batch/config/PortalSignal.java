package za.co.fnb.dcre.platform.batch.config;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.transaction.TransactionSystemException;

/**
 * Finds CockroachDB's multiple-active-portals refusal inside a thrown exception graph.
 *
 * <p>It has to search rather than read the cause chain, because Spring hides it: when the
 * nested read blows up, the connection is already broken, so the rollback that follows
 * fails too and {@code TransactionAspectSupport} throws the ROLLBACK failure
 * ({@code TransactionSystemException: JDBC rollback failed}, cause
 * {@code SQLException: Connection is closed}) with the real cause parked on
 * {@link TransactionSystemException#getApplicationException()}. Asserting on the rollback
 * symptom is asserting on transaction plumbing; the CockroachDB SQLSTATE is the defect.
 */
final class PortalSignal {

    /** SQLSTATE feature_not_supported, what CockroachDB answers a second portal with. */
    static final String SQL_STATE = "0A000";

    private PortalSignal() {
    }

    /** The first {@code 0A000} SQLException anywhere in the graph; empty if there is none. */
    static Optional<SQLException> find(final Throwable thrown) {
        return flatten(thrown).stream()
                .filter(SQLException.class::isInstance)
                .map(SQLException.class::cast)
                .filter(sql -> SQL_STATE.equals(sql.getSQLState()))
                .findFirst();
    }

    /** Every message in the graph, so an assertion can name the SQL that tripped it. */
    static String messages(final Throwable thrown) {
        return flatten(thrown).stream().map(Throwable::getMessage).collect(Collectors.joining("\n"));
    }

    private static List<Throwable> flatten(final Throwable thrown) {
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        final Deque<Throwable> pending = new ArrayDeque<>(List.of(thrown));
        final List<Throwable> found = new ArrayList<>();
        while (!pending.isEmpty()) {
            final Throwable current = pending.poll();
            if (!seen.add(current)) {
                continue;
            }
            found.add(current);
            addIfPresent(pending, current.getCause());
            Collections.addAll(pending, current.getSuppressed());
            if (current instanceof TransactionSystemException transactional) {
                addIfPresent(pending, transactional.getApplicationException());
            }
            if (current instanceof SQLException sql) {
                addIfPresent(pending, sql.getNextException());
            }
        }
        return found;
    }

    private static void addIfPresent(final Deque<Throwable> pending, final @Nullable Throwable candidate) {
        if (candidate != null) {
            pending.add(candidate);
        }
    }
}
