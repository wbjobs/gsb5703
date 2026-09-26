package com.gsb.tpc;

/**
 * In-memory participant with idempotent commit/rollback and knobs to
 * simulate failures, timeouts and crashes.
 */
final class TestParticipant implements Participant {

    enum State { NONE, PREPARED, COMMITTED, ROLLED_BACK }

    final String name;
    State state = State.NONE;

    String voteToReturn = Participant.YES;
    boolean failPrepare;
    boolean failCommit;
    Error crashOnPrepare;       // thrown from prepare() to simulate a coordinator crash
    Error crashOnRollbackOnce;  // thrown once from rollback() to simulate a crash mid-abort
    long advanceClockOnPrepare; // simulates a slow participant by moving the test clock
    ManualClock clock;

    int prepareCalls;
    int commitCalls;
    int rollbackCalls;
    int commitTransitions;    // actual NONE/PREPARED -> COMMITTED transitions
    int rollbackTransitions;  // actual * -> ROLLED_BACK transitions

    TestParticipant(String name) {
        this.name = name;
    }

    @Override
    public void prepare(String txId) {
        prepareCalls++;
        if (crashOnPrepare != null) {
            throw crashOnPrepare;
        }
        if (failPrepare) {
            throw new RuntimeException(name + ": prepare failed");
        }
        if (advanceClockOnPrepare > 0 && clock != null) {
            clock.advance(advanceClockOnPrepare);
        }
        state = State.PREPARED;
    }

    @Override
    public String vote(String txId) {
        return voteToReturn;
    }

    @Override
    public void commit(String txId) {
        commitCalls++;
        if (failCommit) {
            throw new RuntimeException(name + ": commit failed");
        }
        if (state == State.COMMITTED) {
            return; // idempotent duplicate delivery
        }
        if (state == State.ROLLED_BACK) {
            throw new IllegalStateException(name + ": commit after rollback");
        }
        state = State.COMMITTED;
        commitTransitions++;
    }

    @Override
    public void rollback(String txId) {
        rollbackCalls++;
        if (crashOnRollbackOnce != null) {
            Error e = crashOnRollbackOnce;
            crashOnRollbackOnce = null;
            throw e;
        }
        if (state == State.ROLLED_BACK) {
            return; // idempotent duplicate delivery
        }
        if (state == State.COMMITTED) {
            throw new IllegalStateException(name + ": rollback after commit");
        }
        state = State.ROLLED_BACK;
        rollbackTransitions++;
    }

    @Override
    public String toString() {
        return name + "{" + state + '}';
    }
}
