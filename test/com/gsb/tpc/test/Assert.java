package com.gsb.tpc.test;

final class Assert {
    private Assert() {
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("expected true: " + message);
        }
    }

    static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError("expected false: " + message);
        }
    }

    static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " -> expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void fail(String message) {
        throw new AssertionError(message);
    }
}
