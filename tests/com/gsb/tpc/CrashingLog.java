package com.gsb.tpc;

import java.util.List;

/**
 * TransactionLog decorator that lets the underlying record become durable
 * and then throws {@link SimulatedCrash}, simulating a coordinator crash at
 * an exact point in the protocol (e.g. right after the decision is logged).
 */
final class CrashingLog implements TransactionLog {

    private final TransactionLog delegate;
    private final String triggerSubstring;
    private boolean armed = true;

    CrashingLog(TransactionLog delegate, String triggerSubstring) {
        this.delegate = delegate;
        this.triggerSubstring = triggerSubstring;
    }

    @Override
    public void append(String record) {
        delegate.append(record); // record is durable before the "crash"
        if (armed && record.contains(triggerSubstring)) {
            armed = false;
            throw new SimulatedCrash("crash after logging: " + record);
        }
    }

    @Override
    public List<String> records() {
        return delegate.records();
    }
}
