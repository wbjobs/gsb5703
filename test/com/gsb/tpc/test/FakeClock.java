package com.gsb.tpc.test;

import com.gsb.tpc.Clock;

/** Manually advanced clock used to simulate timeouts deterministically. */
final class FakeClock implements Clock {
    private long now;

    @Override
    public long nowMillis() {
        return now;
    }

    void advance(long millis) {
        now += millis;
    }
}
