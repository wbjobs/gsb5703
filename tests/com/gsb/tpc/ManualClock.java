package com.gsb.tpc;

/** Test clock: time only moves when the test advances it. */
final class ManualClock implements Clock {

    private long now;

    @Override
    public synchronized long nowMillis() {
        return now;
    }

    synchronized void advance(long millis) {
        now += millis;
    }
}
