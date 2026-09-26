package com.gsb.tpc;

/**
 * Outcome of {@link Coordinator#commit(String)}.
 */
public enum CommitResult {

    /** Every participant acknowledged the commit; the transaction is finished. */
    COMMITTED,

    /** The transaction was aborted; every participant was (or will be) rolled back. */
    ABORTED,

    /**
     * The decision to commit was made and durably logged, but at least one
     * participant has not acknowledged yet. Delivery will be retried (by a
     * later {@link Coordinator#commit(String)} or {@link Coordinator#recover()}
     * call) until every participant has committed. The decision can never
     * change back to abort.
     */
    COMMIT_IN_PROGRESS
}
