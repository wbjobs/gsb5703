package com.gsb.tpc.test;

import com.gsb.tpc.Participant;

/**
 * In-memory participant with injectable failures and artificial latency.
 * Implements the idempotency contract required of real participants:
 * repeated commit/rollback delivery does not change the final state.
 */
final class TestParticipant implements Participant {

    enum State { ACTIVE, PREPARED, COMMITTED, ROLLED_BACK }

    final String name;
    State state = State.ACTIVE;
    String voteToCast = VOTE_YES;

    int commitFailuresRemaining;
    int rollbackFailuresRemaining;

    /** Artificial latency injected into prepare/commit, driven by the fake clock. */
    FakeClock clock;
    long prepareDelayMillis;
    long commitDelayMillis;

    int prepareCount;
    int commitCount;
    int rollbackCount;

    TestParticipant(String name) {
        this.name = name;
    }

    @Override
    public void prepare(String txId) {
        prepareCount++;
        delay(prepareDelayMillis);
        state = State.PREPARED;
    }

    @Override
    public String vote(String txId) {
        return voteToCast;
    }

    @Override
    public void commit(String txId) {
        commitCount++;
        if (commitFailuresRemaining > 0) {
            commitFailuresRemaining--;
            throw new RuntimeException("injected commit failure in " + name);
        }
        delay(commitDelayMillis);
        if (state == State.COMMITTED) {
            return; // idempotent duplicate delivery
        }
        if (state == State.ROLLED_BACK) {
            throw new IllegalStateException(name + ": commit after rollback");
        }
        state = State.COMMITTED;
    }

    @Override
    public void rollback(String txId) {
        rollbackCount++;
        if (rollbackFailuresRemaining > 0) {
            rollbackFailuresRemaining--;
            throw new RuntimeException("injected rollback failure in " + name);
        }
        if (state == State.ROLLED_BACK) {
            return; // idempotent duplicate delivery
        }
        if (state == State.COMMITTED) {
            throw new IllegalStateException(name + ": rollback after commit");
        }
        state = State.ROLLED_BACK;
    }

    private void delay(long millis) {
        if (clock != null && millis > 0) {
            clock.advance(millis);
        }
    }
}
