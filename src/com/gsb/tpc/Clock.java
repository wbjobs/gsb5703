package com.gsb.tpc;

/**
 * Source of time for the coordinator. Injected so that timeout behaviour is
 * deterministic in tests and the coordinator never reads the wall clock
 * directly. A production implementation typically delegates to the system
 * clock; tests use a manually advanced clock.
 */
public interface Clock {

    /** @return current time in milliseconds (epoch-millis style). */
    long nowMillis();
}
