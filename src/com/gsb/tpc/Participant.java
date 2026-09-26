package com.gsb.tpc;

/**
 * A resource participating in a two-phase commit transaction.
 *
 * Implementations MUST be idempotent: the coordinator may deliver the same
 * commit or rollback more than once (e.g. after a crash and recovery), and a
 * duplicate delivery must not change the outcome.
 */
public interface Participant {

    /** Vote returned by {@link #vote(String)} when the participant can commit. */
    String YES = "YES";

    /** Vote returned by {@link #vote(String)} when the participant cannot commit. */
    String NO = "NO";

    /**
     * Phase 1: ask the participant to prepare the transaction. Implementations
     * should make the transaction durable enough to be able to commit later.
     * Throwing any exception is treated as a failed prepare.
     */
    void prepare(String txId);

    /**
     * Phase 1: return the participant's vote for the transaction,
     * {@link #YES} or {@link #NO}. Any value other than {@link #YES} is
     * treated as a negative vote.
     */
    String vote(String txId);

    /**
     * Phase 2: commit the transaction. Must be idempotent: committing an
     * already committed transaction must be a no-op.
     */
    void commit(String txId);

    /**
     * Phase 2 (abort): roll back the transaction. Must be idempotent:
     * rolling back an already rolled-back (or never prepared) transaction
     * must be a no-op.
     */
    void rollback(String txId);
}
