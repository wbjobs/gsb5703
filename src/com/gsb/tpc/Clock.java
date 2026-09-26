package com.gsb.tpc;

/**
 * Source of time used by the coordinator for all timeout decisions.
 * Injected so tests can control time; the coordinator never reads the
 * system clock directly.
 */
public interface Clock {
    long nowMillis();
}
