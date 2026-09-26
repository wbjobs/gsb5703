package com.gsb.tpc.test;

public final class TestRunner {

    private interface TestBody {
        void run() throws Exception;
    }

    private TestRunner() {
    }

    public static void main(String[] args) {
        int failures = 0;
        failures += run("prepare NO vote rolls back every participant",
                new TestBody() {
                    public void run() {
                        CoordinatorTest.testNoVoteRollsBackEveryone();
                    }
                });
        failures += run("prepare timeout rolls back every participant",
                new TestBody() {
                    public void run() {
                        CoordinatorTest.testPrepareTimeoutRollsBackEveryone();
                    }
                });
        failures += run("commit-phase failure is retried after recovery until all commit",
                new TestBody() {
                    public void run() {
                        CoordinatorTest.testCommitFailureRetriedAfterRecovery();
                    }
                });
        failures += run("crash before decision recovers as abort",
                new TestBody() {
                    public void run() {
                        CoordinatorTest.testCrashBeforeDecisionRecoversAsAbort();
                    }
                });
        failures += run("crash after decision keeps the decision",
                new TestBody() {
                    public void run() {
                        CoordinatorTest.testCrashAfterDecisionKeepsDecision();
                    }
                });
        failures += run("duplicate commit/rollback delivery is idempotent",
                new TestBody() {
                    public void run() {
                        CoordinatorTest.testDuplicateDeliveryIsIdempotent();
                    }
                });

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL TESTS PASSED");
        } else {
            System.out.println(failures + " TEST(S) FAILED");
            System.exit(1);
        }
    }

    private static int run(String name, TestBody body) {
        try {
            body.run();
            System.out.println("[PASS] " + name);
            return 0;
        } catch (Throwable t) {
            System.out.println("[FAIL] " + name + " -> " + t);
            t.printStackTrace(System.out);
            return 1;
        }
    }
}
