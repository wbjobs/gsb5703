package com.gsb.tpc;

/**
 * Default {@link Clock} backed by a monotonic nano-time source, suitable
 * for measuring elapsed time in production.
 */
public final class SystemClock implements Clock {
    @Override
    public long nowMillis() {
        return System.nanoTime() / 1000000L;
    }
}
