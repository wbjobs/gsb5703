package com.gsb.tpc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Summary of a {@link Coordinator#recover()} pass.
 */
public final class RecoveryReport {

    private final List<String> committedTxIds;
    private final List<String> abortedTxIds;
    private final List<String> pendingTxIds;

    public RecoveryReport(List<String> committedTxIds,
                          List<String> abortedTxIds,
                          List<String> pendingTxIds) {
        this.committedTxIds = Collections.unmodifiableList(new ArrayList<String>(committedTxIds));
        this.abortedTxIds = Collections.unmodifiableList(new ArrayList<String>(abortedTxIds));
        this.pendingTxIds = Collections.unmodifiableList(new ArrayList<String>(pendingTxIds));
    }

    /** Transactions whose final state after recovery is COMMITTED (fully delivered). */
    public List<String> getCommittedTxIds() {
        return committedTxIds;
    }

    /** Transactions whose final state after recovery is ABORTED (fully delivered). */
    public List<String> getAbortedTxIds() {
        return abortedTxIds;
    }

    /**
     * Transactions whose decision is durable but not yet delivered to every
     * participant (e.g. a participant is still down or not re-enlisted).
     * Call {@code recover()} again to keep retrying.
     */
    public List<String> getPendingTxIds() {
        return pendingTxIds;
    }

    public boolean isFullyRecovered() {
        return pendingTxIds.isEmpty();
    }

    @Override
    public String toString() {
        return "RecoveryReport{committed=" + committedTxIds
                + ", aborted=" + abortedTxIds
                + ", pending=" + pendingTxIds + '}';
    }
}
