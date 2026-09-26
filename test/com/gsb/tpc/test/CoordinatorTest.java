package com.gsb.tpc.test;

import com.gsb.tpc.CommitResult;
import com.gsb.tpc.Coordinator;
import com.gsb.tpc.Participant;
import com.gsb.tpc.RecoveryReport;

import java.io.File;

final class CoordinatorTest {

    private static final long PREPARE_TIMEOUT = 5000L;
    private static final long COMMIT_TIMEOUT = 5000L;

    private CoordinatorTest() {
    }

    /** A NO vote in prepare must roll back everyone, including YES voters. */
    static void testNoVoteRollsBackEveryone() {
        FakeClock clock = new FakeClock();
        Coordinator coordinator = new Coordinator(clock, freshLogDir("no-vote"),
                PREPARE_TIMEOUT, COMMIT_TIMEOUT);
        String tx = coordinator.begin();
        TestParticipant p1 = new TestParticipant("p1");
        TestParticipant p2 = new TestParticipant("p2");
        TestParticipant p3 = new TestParticipant("p3");
        p2.voteToCast = Participant.VOTE_NO;
        coordinator.enlist(tx, p1);
        coordinator.enlist(tx, p2);
        coordinator.enlist(tx, p3);

        CommitResult result = coordinator.commit(tx);

        Assert.assertEquals(CommitResult.Status.ABORTED, result.getStatus(), "result status");
        Assert.assertEquals(TestParticipant.State.ROLLED_BACK, p1.state, "p1 (YES voter) must be rolled back");
        Assert.assertEquals(TestParticipant.State.ROLLED_BACK, p2.state, "p2 (NO voter) must be rolled back");
        Assert.assertEquals(TestParticipant.State.ROLLED_BACK, p3.state, "p3 must be rolled back");
        Assert.assertEquals(Integer.valueOf(1), Integer.valueOf(p1.rollbackCount), "p1 rollback deliveries");
        Assert.assertEquals(Integer.valueOf(0), Integer.valueOf(p1.commitCount), "p1 commit deliveries");
        Assert.assertEquals(Integer.valueOf(0), Integer.valueOf(p2.commitCount), "p2 commit deliveries");
        Assert.assertEquals(Integer.valueOf(0), Integer.valueOf(p3.commitCount), "p3 commit deliveries");
    }

    /** A prepare timeout counts as a NO vote and aborts the transaction. */
    static void testPrepareTimeoutRollsBackEveryone() {
        FakeClock clock = new FakeClock();
        Coordinator coordinator = new Coordinator(clock, freshLogDir("prepare-timeout"),
                PREPARE_TIMEOUT, COMMIT_TIMEOUT);
        String tx = coordinator.begin();
        TestParticipant fast = new TestParticipant("fast");
        TestParticipant slow = new TestParticipant("slow");
        slow.clock = clock;
        slow.prepareDelayMillis = PREPARE_TIMEOUT + 1;
        coordinator.enlist(tx, fast);
        coordinator.enlist(tx, slow);

        CommitResult result = coordinator.commit(tx);

        Assert.assertEquals(CommitResult.Status.ABORTED, result.getStatus(), "result status");
        Assert.assertEquals(TestParticipant.State.ROLLED_BACK, fast.state, "fast participant rolled back");
        Assert.assertEquals(TestParticipant.State.ROLLED_BACK, slow.state, "slow participant rolled back");
        Assert.assertEquals(Integer.valueOf(0), Integer.valueOf(fast.commitCount), "no commits delivered");
        Assert.assertEquals(Integer.valueOf(0), Integer.valueOf(slow.commitCount), "no commits delivered");
    }

    /** A participant failing in the commit phase is retried after recovery until all commit. */
    static void testCommitFailureRetriedAfterRecovery() {
        FakeClock clock = new FakeClock();
        File dir = freshLogDir("commit-retry");
        Coordinator c1 = new Coordinator(clock, dir, PREPARE_TIMEOUT, COMMIT_TIMEOUT);
        String tx = c1.begin();
        TestParticipant a = new TestParticipant("a");
        TestParticipant b = new TestParticipant("b");
        b.commitFailuresRemaining = 100; // keeps failing through the crash
        c1.enlist(tx, a);
        c1.enlist(tx, b);

        CommitResult result = c1.commit(tx);

        Assert.assertTrue(result.isCommitted(), "decision is COMMIT");
        Assert.assertFalse(result.isFullyDelivered(), "delivery still pending for b");
        Assert.assertEquals(TestParticipant.State.COMMITTED, a.state, "a committed");
        Assert.assertEquals(TestParticipant.State.PREPARED, b.state, "b not yet committed");

        // Coordinator crashes and restarts over the same log.
        Coordinator c2 = new Coordinator(clock, dir, PREPARE_TIMEOUT, COMMIT_TIMEOUT);
        c2.enlist(tx, a);
        c2.enlist(tx, b);
        b.commitFailuresRemaining = 0; // participant is repaired

        RecoveryReport report = c2.recover();

        Assert.assertTrue(report.getCommittedTxIds().contains(tx), "tx committed after recovery");
        Assert.assertTrue(report.getPendingTxIds().isEmpty(), "nothing pending: " + report);
        Assert.assertEquals(TestParticipant.State.COMMITTED, a.state, "a still committed");
        Assert.assertEquals(TestParticipant.State.COMMITTED, b.state, "b committed by recovery retry");
        Assert.assertEquals(Integer.valueOf(1), Integer.valueOf(a.commitCount),
                "a must not receive the commit twice (ACK was logged)");
    }

    /** Crash before the decision: recovery aborts the transaction for everyone. */
    static void testCrashBeforeDecisionRecoversAsAbort() {
        FakeClock clock = new FakeClock();
        File dir = freshLogDir("crash-before-decision");
        Coordinator c1 = new Coordinator(clock, dir, PREPARE_TIMEOUT, COMMIT_TIMEOUT);
        String tx = c1.begin();
        TestParticipant p1 = new TestParticipant("p1");
        TestParticipant p2 = new TestParticipant("p2");
        c1.enlist(tx, p1);
        c1.enlist(tx, p2);
        // crash before commit() is ever called

        Coordinator c2 = new Coordinator(clock, dir, PREPARE_TIMEOUT, COMMIT_TIMEOUT);
        c2.enlist(tx, p1);
        c2.enlist(tx, p2);
        RecoveryReport report = c2.recover();

        Assert.assertTrue(report.getAbortedTxIds().contains(tx), "undecided tx aborted by recovery");
        Assert.assertEquals(TestParticipant.State.ROLLED_BACK, p1.state, "p1 rolled back");
        Assert.assertEquals(TestParticipant.State.ROLLED_BACK, p2.state, "p2 rolled back");

        // The decision is durable: committing afterwards still yields ABORTED.
        CommitResult result = c2.commit(tx);
        Assert.assertEquals(CommitResult.Status.ABORTED, result.getStatus(), "decision unchanged");
        Assert.assertEquals(TestParticipant.State.ROLLED_BACK, p1.state, "p1 stays rolled back");
        Assert.assertEquals(TestParticipant.State.ROLLED_BACK, p2.state, "p2 stays rolled back");
    }

    /** Crash after the COMMIT decision: recovery completes it and the decision is immutable. */
    static void testCrashAfterDecisionKeepsDecision() {
        FakeClock clock = new FakeClock();
        File dir = freshLogDir("crash-after-decision");
        Coordinator c1 = new Coordinator(clock, dir, PREPARE_TIMEOUT, COMMIT_TIMEOUT);
        String tx = c1.begin();
        TestParticipant a = new TestParticipant("a");
        TestParticipant b = new TestParticipant("b");
        b.commitFailuresRemaining = 100;
        c1.enlist(tx, a);
        c1.enlist(tx, b);
        CommitResult first = c1.commit(tx);
        Assert.assertTrue(first.isCommitted(), "decision COMMIT logged before crash");
        // crash with b's commit undelivered

        Coordinator c2 = new Coordinator(clock, dir, PREPARE_TIMEOUT, COMMIT_TIMEOUT);
        c2.enlist(tx, a);
        c2.enlist(tx, b);
        b.commitFailuresRemaining = 0;
        RecoveryReport report = c2.recover();

        Assert.assertTrue(report.getCommittedTxIds().contains(tx), "tx committed after recovery");
        Assert.assertEquals(TestParticipant.State.COMMITTED, a.state, "a committed");
        Assert.assertEquals(TestParticipant.State.COMMITTED, b.state, "b committed");

        // The logged decision cannot be reversed or changed.
        CommitResult again = c2.commit(tx);
        Assert.assertTrue(again.isCommitted(), "commit stays COMMITTED");
        try {
            c2.abort(tx);
            Assert.fail("abort of a committed transaction must be rejected");
        } catch (IllegalStateException expected) {
            // expected
        }
        Assert.assertEquals(TestParticipant.State.COMMITTED, a.state, "a still committed");
        Assert.assertEquals(TestParticipant.State.COMMITTED, b.state, "b still committed");
        Assert.assertEquals(Integer.valueOf(1), Integer.valueOf(a.commitCount), "no duplicate commit to a");
    }

    /** Duplicate commit/rollback delivery (timeout ambiguity, repeated abort/recover) is idempotent. */
    static void testDuplicateDeliveryIsIdempotent() {
        FakeClock clock = new FakeClock();
        File dir = freshLogDir("duplicate-delivery");
        Coordinator coordinator = new Coordinator(clock, dir, PREPARE_TIMEOUT, COMMIT_TIMEOUT);

        // Duplicate commit: the participant applies the commit but is too slow,
        // so the coordinator treats it as failed and delivers it again.
        String tx1 = coordinator.begin();
        TestParticipant slow = new TestParticipant("slow");
        slow.clock = clock;
        slow.commitDelayMillis = COMMIT_TIMEOUT + 1;
        TestParticipant fast = new TestParticipant("fast");
        coordinator.enlist(tx1, slow);
        coordinator.enlist(tx1, fast);

        CommitResult result = coordinator.commit(tx1);
        Assert.assertTrue(result.isCommitted(), "decision is COMMIT");
        Assert.assertFalse(result.isFullyDelivered(), "slow participant not acknowledged");
        Assert.assertEquals(TestParticipant.State.COMMITTED, slow.state,
                "slow participant applied the commit despite the timeout");

        slow.commitDelayMillis = 0;
        RecoveryReport report = coordinator.recover();
        Assert.assertTrue(report.getPendingTxIds().isEmpty(), "nothing pending: " + report);
        Assert.assertEquals(TestParticipant.State.COMMITTED, slow.state,
                "duplicate commit delivery keeps the committed state");
        Assert.assertTrue(slow.commitCount >= 2, "commit was delivered more than once");
        Assert.assertEquals(Integer.valueOf(1), Integer.valueOf(fast.commitCount),
                "acknowledged participant is not re-delivered");

        // Duplicate rollback: repeated abort and recover must not change the state.
        String tx2 = coordinator.begin();
        TestParticipant r1 = new TestParticipant("r1");
        TestParticipant r2 = new TestParticipant("r2");
        coordinator.enlist(tx2, r1);
        coordinator.enlist(tx2, r2);
        coordinator.abort(tx2);
        coordinator.abort(tx2);
        coordinator.recover();

        Assert.assertEquals(TestParticipant.State.ROLLED_BACK, r1.state, "r1 rolled back");
        Assert.assertEquals(TestParticipant.State.ROLLED_BACK, r2.state, "r2 rolled back");
        Assert.assertEquals(Integer.valueOf(1), Integer.valueOf(r1.rollbackCount),
                "acknowledged rollback is not re-delivered");
        Assert.assertEquals(Integer.valueOf(1), Integer.valueOf(r2.rollbackCount),
                "acknowledged rollback is not re-delivered");
        Assert.assertEquals(Integer.valueOf(0), Integer.valueOf(r1.commitCount), "r1 never committed");
        Assert.assertEquals(Integer.valueOf(0), Integer.valueOf(r2.commitCount), "r2 never committed");
    }

    private static File freshLogDir(String name) {
        File dir = new File(new File("build", "test-logs"), name);
        deleteRecursively(dir);
        if (!dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        return dir;
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            throw new IllegalStateException("cannot delete " + file);
        }
    }
}
