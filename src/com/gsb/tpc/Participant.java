package com.gsb.tpc;

/**
 * A resource participating in two-phase commit.
 *
 * Implementations MUST be idempotent: the coordinator may deliver the same
 * commit or rollback more than once (e.g. after a crash or a timeout where
 * the outcome was uncertain), and repeated delivery must not change the
 * final state.
 */
public interface Participant {
    String VOTE_YES = "YES";
    String VOTE_NO = "NO";

    /** Ask the participant to prepare the transaction. */
    void prepare(String txId) throws Exception;

    /** Collect the participant's vote: {@link #VOTE_YES} or {@link #VOTE_NO}. */
    String vote(String txId) throws Exception;

    /** Apply the commit. Must be idempotent for repeated calls. */
    void commit(String txId) throws Exception;

    /** Apply the rollback. Must be idempotent for repeated calls. */
    void rollback(String txId) throws Exception;
}
