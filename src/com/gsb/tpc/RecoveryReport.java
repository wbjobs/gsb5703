package com.gsb.tpc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of {@link Coordinator#recover()}: the fate of every transaction
 * found in the recovery log.
 */
public final class RecoveryReport {

    private final List<String> committedTxIds;
    private final List<String> abortedTxIds;
    private final List<String> pendingTxIds;

    public RecoveryReport(List<String> committedTxIds,
                          List<String> abortedTxIds,
                          List<String> pendingTxIds) {
        this.committedTxIds = immutableCopy(committedTxIds);
        this.abortedTxIds = immutableCopy(abortedTxIds);
        this.pendingTxIds = immutableCopy(pendingTxIds);
    }

    private static List<String> immutableCopy(List<String> in) {
        return Collections.unmodifiableList(new ArrayList<String>(in));
    }

    /** Transactions whose final state is "committed" (delivery finished). */
    public List<String> getCommittedTxIds() {
        return committedTxIds;
    }

    /** Transactions whose final state is "aborted" (rollback finished). */
    public List<String> getAbortedTxIds() {
        return abortedTxIds;
    }

    /**
     * Transactions with a durable decision whose delivery could not be
     * completed yet (participants missing or still failing). A later
     * {@link Coordinator#recover()} call will retry them; their decision
     * is final and will not change.
     */
    public List<String> getPendingTxIds() {
        return pendingTxIds;
    }

    @Override
    public String toString() {
        return "RecoveryReport{committed=" + committedTxIds
                + ", aborted=" + abortedTxIds
                + ", pending=" + pendingTxIds + '}';
    }
}
