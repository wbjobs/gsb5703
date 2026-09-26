package com.gsb.tpc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-Java test suite (no JUnit). Run by run-tests.sh.
 */
public final class CoordinatorTest {

    private static final long PREPARE_TIMEOUT_MS = 1000L;
    private static final long COMMIT_TIMEOUT_MS = 1000L;

    private static int passed;
    private static int failed;
    private static final List<TpcCoordinator> coordinators = new ArrayList<TpcCoordinator>();

    public static void main(String[] args) throws Exception {
        run("happyPathCommitsAll", new TestCase() {
            public void run() throws Exception { happyPathCommitsAll(); }
        });
        run("prepareVoteNoRollsBackEveryone", new TestCase() {
            public void run() throws Exception { prepareVoteNoRollsBackEveryone(); }
        });
        run("prepareFailureRollsBackEveryone", new TestCase() {
            public void run() throws Exception { prepareFailureRollsBackEveryone(); }
        });
        run("prepareTimeoutRollsBackEveryone", new TestCase() {
            public void run() throws Exception { prepareTimeoutRollsBackEveryone(); }
        });
        run("commitPhaseFailureThenRecoveryCommitsAll", new TestCase() {
            public void run() throws Exception { commitPhaseFailureThenRecoveryCommitsAll(); }
        });
        run("crashBeforeDecisionRecoveryAborts", new TestCase() {
            public void run() throws Exception { crashBeforeDecisionRecoveryAborts(); }
        });
        run("crashAfterDecisionRecoveryCommits", new TestCase() {
            public void run() throws Exception { crashAfterDecisionRecoveryCommits(); }
        });
        run("duplicateCommitDeliveryIsIdempotent", new TestCase() {
            public void run() throws Exception { duplicateCommitDeliveryIsIdempotent(); }
        });
        run("duplicateRollbackDeliveryIsIdempotent", new TestCase() {
            public void run() throws Exception { duplicateRollbackDeliveryIsIdempotent(); }
        });

        for (TpcCoordinator c : coordinators) {
            c.shutdown();
        }
        System.out.println("----------------------------------------");
        System.out.println("passed: " + passed + ", failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("ALL TESTS PASSED");
    }

    // ------------------------------------------------------------ tests

    /** All participants vote YES -> everybody commits. */
    static void happyPathCommitsAll() throws IOException {
        ManualClock clock = new ManualClock();
        TpcCoordinator c = newCoordinator(newLog("happy"), clock);
        TestParticipant p1 = new TestParticipant("p1");
        TestParticipant p2 = new TestParticipant("p2");

        String tx = c.begin();
        c.enlist(tx, p1);
        c.enlist(tx, p2);

        check(c.commit(tx) == CommitResult.COMMITTED, "commit should return COMMITTED");
        check(p1.state == TestParticipant.State.COMMITTED, "p1 should be committed");
        check(p2.state == TestParticipant.State.COMMITTED, "p2 should be committed");
        check(p1.commitTransitions == 1 && p2.commitTransitions == 1,
                "each participant commits exactly once");
        check(p1.rollbackCalls == 0 && p2.rollbackCalls == 0, "no rollback on happy path");
    }

    /** A NO vote in phase 1 aborts the whole transaction, YES voters included. */
    static void prepareVoteNoRollsBackEveryone() throws IOException {
        ManualClock clock = new ManualClock();
        TpcCoordinator c = newCoordinator(newLog("vote-no"), clock);
        TestParticipant p1 = new TestParticipant("p1");
        TestParticipant p2 = new TestParticipant("p2");
        TestParticipant p3 = new TestParticipant("p3");
        p2.voteToReturn = Participant.NO;

        String tx = c.begin();
        c.enlist(tx, p1);
        c.enlist(tx, p2);
        c.enlist(tx, p3);

        check(c.commit(tx) == CommitResult.ABORTED, "commit should return ABORTED");
        check(p1.state == TestParticipant.State.ROLLED_BACK, "YES voter p1 must be rolled back");
        check(p2.state == TestParticipant.State.ROLLED_BACK, "NO voter p2 must be rolled back");
        check(p3.state == TestParticipant.State.ROLLED_BACK, "p3 must be rolled back");
        check(p1.rollbackTransitions == 1, "p1 rolled back exactly once");
        check(p1.commitCalls == 0 && p2.commitCalls == 0 && p3.commitCalls == 0,
                "nobody may receive commit after an abort");
        // Decision is final: a later commit() cannot revive the transaction.
        check(c.commit(tx) == CommitResult.ABORTED, "aborted decision must not change");
    }

    /** A prepare() exception is treated like a NO vote. */
    static void prepareFailureRollsBackEveryone() throws IOException {
        ManualClock clock = new ManualClock();
        TpcCoordinator c = newCoordinator(newLog("prepare-fail"), clock);
        TestParticipant p1 = new TestParticipant("p1");
        TestParticipant p2 = new TestParticipant("p2");
        p2.failPrepare = true;

        String tx = c.begin();
        c.enlist(tx, p1);
        c.enlist(tx, p2);

        check(c.commit(tx) == CommitResult.ABORTED, "commit should return ABORTED");
        check(p1.state == TestParticipant.State.ROLLED_BACK, "p1 must be rolled back");
        check(p2.state == TestParticipant.State.ROLLED_BACK, "p2 must be rolled back");
    }

    /** A participant that answers after the prepare deadline aborts the transaction. */
    static void prepareTimeoutRollsBackEveryone() throws IOException {
        ManualClock clock = new ManualClock();
        TpcCoordinator c = newCoordinator(newLog("prepare-timeout"), clock);
        TestParticipant p1 = new TestParticipant("p1");
        TestParticipant slow = new TestParticipant("slow");
        slow.clock = clock;
        slow.advanceClockOnPrepare = 10 * PREPARE_TIMEOUT_MS; // answers way too late

        String tx = c.begin();
        c.enlist(tx, p1);
        c.enlist(tx, slow);

        check(c.commit(tx) == CommitResult.ABORTED, "slow prepare must abort the transaction");
        check(p1.state == TestParticipant.State.ROLLED_BACK, "p1 must be rolled back");
        check(slow.state == TestParticipant.State.ROLLED_BACK, "slow participant must be rolled back");
    }

    /**
     * Phase 2: a participant fails to commit, the decision is already durable,
     * and a later recovery retries until everybody has committed.
     */
    static void commitPhaseFailureThenRecoveryCommitsAll() throws IOException {
        ManualClock clock = new ManualClock();
        FileTransactionLog log = newLog("commit-retry");
        TpcCoordinator c = newCoordinator(log, clock);
        TestParticipant p1 = new TestParticipant("p1");
        TestParticipant p2 = new TestParticipant("p2");
        TestParticipant p3 = new TestParticipant("p3");
        p3.failCommit = true;

        String tx = c.begin();
        c.enlist(tx, p1);
        c.enlist(tx, p2);
        c.enlist(tx, p3);

        check(c.commit(tx) == CommitResult.COMMIT_IN_PROGRESS,
                "commit should report COMMIT_IN_PROGRESS while p3 is down");
        check(p1.state == TestParticipant.State.COMMITTED, "p1 committed");
        check(p2.state == TestParticipant.State.COMMITTED, "p2 committed");
        check(p3.state == TestParticipant.State.PREPARED, "p3 still prepared, not committed");

        // Participant comes back; recovery retries the delivery.
        p3.failCommit = false;
        RecoveryReport report = c.recover();
        check(report.getCommittedTxIds().contains(tx), "recovery should finish the commit");
        check(report.getPendingTxIds().isEmpty(), "nothing may stay pending");
        check(p3.state == TestParticipant.State.COMMITTED, "p3 committed after recovery");
        check(p1.state == TestParticipant.State.COMMITTED
                && p2.state == TestParticipant.State.COMMITTED
                && p3.state == TestParticipant.State.COMMITTED,
                "all participants must end up committed");
        check(c.commit(tx) == CommitResult.COMMITTED, "transaction is now fully committed");
    }

    /** Coordinator crashes before any decision: recovery presumes abort. */
    static void crashBeforeDecisionRecoveryAborts() throws IOException {
        ManualClock clock = new ManualClock();
        FileTransactionLog log = newLog("crash-before");
        TpcCoordinator c1 = newCoordinator(log, clock);
        TestParticipant p1 = new TestParticipant("p1");
        TestParticipant p2 = new TestParticipant("p2");

        String tx = c1.begin();
        c1.enlist(tx, p1);
        c1.enlist(tx, p2);
        // "Crash": c1 is abandoned before commit() is ever called.

        TpcCoordinator c2 = newCoordinator(log, clock); // same log file
        c2.enlist(tx, p1); // application re-enlists its participants
        c2.enlist(tx, p2);

        RecoveryReport report = c2.recover();
        check(report.getAbortedTxIds().contains(tx), "undecided transaction must be presumed aborted");
        check(p1.state == TestParticipant.State.ROLLED_BACK, "p1 rolled back after recovery");
        check(p2.state == TestParticipant.State.ROLLED_BACK, "p2 rolled back after recovery");
        check(c2.commit(tx) == CommitResult.ABORTED, "abort decision must be final");
    }

    /** Coordinator crashes right after logging the COMMIT decision: recovery completes it. */
    static void crashAfterDecisionRecoveryCommits() throws IOException {
        ManualClock clock = new ManualClock();
        FileTransactionLog log = newLog("crash-after");
        CrashingLog crashingLog = new CrashingLog(log, "DECISION");
        TpcCoordinator c1 = newCoordinator(crashingLog, clock);
        TestParticipant p1 = new TestParticipant("p1");
        TestParticipant p2 = new TestParticipant("p2");

        String tx = c1.begin();
        c1.enlist(tx, p1);
        c1.enlist(tx, p2);

        // c1 dies the moment the COMMIT decision hits the log.
        expectSimulatedCrash(c1, tx);
        check(p1.state == TestParticipant.State.PREPARED, "p1 prepared but not committed");
        check(p2.state == TestParticipant.State.PREPARED, "p2 prepared but not committed");

        TpcCoordinator c2 = newCoordinator(log, clock); // restart over the same log
        c2.enlist(tx, p1);
        c2.enlist(tx, p2);

        RecoveryReport report = c2.recover();
        check(report.getCommittedTxIds().contains(tx),
                "durable COMMIT decision must be completed by recovery");
        check(p1.state == TestParticipant.State.COMMITTED, "p1 committed after recovery");
        check(p2.state == TestParticipant.State.COMMITTED, "p2 committed after recovery");
        check(c2.commit(tx) == CommitResult.COMMITTED, "commit decision must be final");
        expectIllegalState(c2, tx);
    }

    /** Duplicate commit delivery after a crash must not change participant state. */
    static void duplicateCommitDeliveryIsIdempotent() throws IOException {
        ManualClock clock = new ManualClock();
        FileTransactionLog log = newLog("dup-commit");
        TpcCoordinator c1 = newCoordinator(log, clock);
        TestParticipant p1 = new TestParticipant("p1");
        TestParticipant p2 = new TestParticipant("p2");
        TestParticipant p3 = new TestParticipant("p3");
        p3.failCommit = true;

        String tx = c1.begin();
        c1.enlist(tx, p1);
        c1.enlist(tx, p2);
        c1.enlist(tx, p3);
        check(c1.commit(tx) == CommitResult.COMMIT_IN_PROGRESS, "p3 down -> IN_PROGRESS");
        check(p1.commitTransitions == 1 && p2.commitTransitions == 1, "p1/p2 committed once");

        // "Crash" and restart: the new coordinator has no delivery memory and
        // re-delivers commit to everybody, p1 and p2 included.
        p3.failCommit = false;
        TpcCoordinator c2 = newCoordinator(log, clock);
        c2.enlist(tx, p1);
        c2.enlist(tx, p2);
        c2.enlist(tx, p3);
        RecoveryReport report = c2.recover();

        check(report.getCommittedTxIds().contains(tx), "recovery completes the commit");
        check(p1.commitCalls == 2 && p2.commitCalls == 2, "commit was delivered twice");
        check(p1.commitTransitions == 1 && p2.commitTransitions == 1,
                "duplicate commit must not re-apply");
        check(p1.state == TestParticipant.State.COMMITTED
                && p2.state == TestParticipant.State.COMMITTED
                && p3.state == TestParticipant.State.COMMITTED,
                "everyone committed exactly once");
        check(p1.rollbackCalls == 0 && p2.rollbackCalls == 0 && p3.rollbackCalls == 0,
                "no rollback may follow a commit decision");

        // A further recovery is a no-op: the transaction is already ENDed.
        c2.recover();
        check(p1.commitCalls == 2 && p2.commitCalls == 2 && p3.commitCalls == 2,
                "finished transactions are not re-delivered");
    }

    /** Duplicate rollback delivery after a crash must not change participant state. */
    static void duplicateRollbackDeliveryIsIdempotent() throws IOException {
        ManualClock clock = new ManualClock();
        FileTransactionLog log = newLog("dup-rollback");
        TpcCoordinator c1 = newCoordinator(log, clock);
        TestParticipant p1 = new TestParticipant("p1");
        TestParticipant p2 = new TestParticipant("p2");
        TestParticipant p3 = new TestParticipant("p3");
        p2.voteToReturn = Participant.NO;
        // Coordinator "crashes" while rolling back p2, after p1 was rolled back.
        p2.crashOnRollbackOnce = new SimulatedCrash("crash during abort delivery");

        String tx = c1.begin();
        c1.enlist(tx, p1);
        c1.enlist(tx, p2);
        c1.enlist(tx, p3);

        try {
            c1.commit(tx);
            throw new AssertionError("expected SimulatedCrash during abort delivery");
        } catch (SimulatedCrash expected) {
            // c1 is dead; the ABORT decision is durable, p1 already rolled back.
        }
        check(p1.state == TestParticipant.State.ROLLED_BACK, "p1 rolled back before crash");
        check(p1.rollbackCalls == 1, "p1 saw exactly one rollback so far");

        TpcCoordinator c2 = newCoordinator(log, clock);
        c2.enlist(tx, p1);
        c2.enlist(tx, p2);
        c2.enlist(tx, p3);
        RecoveryReport report = c2.recover();

        check(report.getAbortedTxIds().contains(tx), "recovery completes the abort");
        check(p1.rollbackCalls == 2, "rollback was delivered twice to p1");
        check(p1.rollbackTransitions == 1, "duplicate rollback must not re-apply");
        check(p1.state == TestParticipant.State.ROLLED_BACK
                && p2.state == TestParticipant.State.ROLLED_BACK
                && p3.state == TestParticipant.State.ROLLED_BACK,
                "everyone rolled back exactly once");
        check(p1.commitCalls == 0 && p2.commitCalls == 0 && p3.commitCalls == 0,
                "no commit may follow an abort decision");
    }

    // ------------------------------------------------------------ helpers

    private interface TestCase {
        void run() throws Exception;
    }

    private static void run(String name, TestCase test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL " + name + " -> " + t);
            t.printStackTrace(System.out);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static FileTransactionLog newLog(String name) throws IOException {
        Path dir = Files.createTempDirectory("tpc-" + name);
        return new FileTransactionLog(dir.resolve("coordinator.log").toFile());
    }

    private static TpcCoordinator newCoordinator(TransactionLog log, ManualClock clock) {
        TpcCoordinator c = new TpcCoordinator(log, clock, PREPARE_TIMEOUT_MS, COMMIT_TIMEOUT_MS);
        coordinators.add(c);
        return c;
    }

    private static void expectSimulatedCrash(TpcCoordinator c, String tx) {
        try {
            c.commit(tx);
            throw new AssertionError("expected SimulatedCrash");
        } catch (SimulatedCrash expected) {
            // expected: coordinator died right after logging its decision
        }
    }

    private static void expectIllegalState(TpcCoordinator c, String tx) {
        try {
            c.abort(tx);
            throw new AssertionError("expected IllegalStateException: abort after COMMIT decision");
        } catch (IllegalStateException expected) {
            // expected: decisions are final
        }
    }
}
