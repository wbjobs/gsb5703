package com.gsb.tpc;

/** Thrown to simulate a coordinator crash (an Error, so it is never swallowed). */
final class SimulatedCrash extends Error {
    SimulatedCrash(String message) {
        super(message);
    }
}
