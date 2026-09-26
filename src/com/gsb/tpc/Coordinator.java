package com.gsb.tpc;

/**
 * Two-phase commit coordinator.
 *
 * Typical usage:
 * <pre>
 *   String txId = coordinator.begin();
 *   coordinator.enlist(txId, participantA);
 *   coordinator.enlist(txId, participantB);
 *   CommitResult result = coordinator.commit(txId);
 * </pre>
 *
 * After a crash, create a new coordinator over the same recovery log,
 * re-enlist the participants of any in-flight transactions and call
 * {@link #recover()}. Decisions that were durably logged before the crash
 * are replayed and never change.
 */
public interface Coordinator {

    /** Begin a new transaction and return its id. */
    String begin();

    /**
     * Enlist a participant in a transaction. Also used after a restart to
     * re-attach participants to a transaction recovered from the log.
     */
    void enlist(String txId, Participant p);

    /**
     * Run the two-phase commit protocol for the transaction.
     *
     * Phase 1: every enlisted participant is asked to prepare and vote. Any
     * negative vote, failure or timeout aborts the whole transaction and
     * every participant (including those that voted YES) is rolled back.
     *
     * Phase 2: once all votes are YES the COMMIT decision is written to the
     * recovery log (this is the atomic commit point) and then delivered to
     * all participants. Participants that fail or time out are retried;
     * if they cannot all be reached before the commit timeout the method
     * returns {@link CommitResult#COMMIT_IN_PROGRESS} and delivery continues
     * via {@link #recover()} until every participant has committed.
     */
    CommitResult commit(String txId);

    /**
     * Abort a transaction that has no durable COMMIT decision yet and roll
     * back all of its participants. Aborting an already aborted transaction
     * is a no-op. Aborting a transaction that was decided COMMIT throws
     * {@link IllegalStateException} because decisions are final.
     */
    void abort(String txId);

    /**
     * Replay the recovery log and drive every unfinished transaction to
     * completion: transactions without a decision are aborted (presumed
     * abort), transactions with a durable decision have that decision
     * re-delivered (idempotently) until all participants acknowledge.
     */
    RecoveryReport recover();
}
