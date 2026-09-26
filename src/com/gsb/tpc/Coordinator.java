package com.gsb.tpc;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Two-phase commit coordinator with a durable recovery log.
 *
 * Guarantees:
 * - Prepare phase: any NO vote, participant failure, or timeout aborts the
 *   whole transaction, and every enlisted participant (including those that
 *   voted YES) receives a rollback.
 * - Commit phase: once the COMMIT decision is logged it is final. Delivery
 *   failures are retried, and {@link #recover()} keeps retrying after a
 *   crash until every participant has acknowledged, so no partial commit
 *   is ever left behind.
 * - Crash safety: the decision record is fsync'd before any delivery, and
 *   recovery replays the log without ever reversing a logged decision.
 * - Participants must be idempotent; duplicate commit/rollback delivery is
 *   possible (e.g. when a delivery timed out but was actually applied).
 *
 * All timeout decisions are based on the injected {@link Clock}; this class
 * never reads the system clock.
 */
public final class Coordinator {

    public static final long DEFAULT_PREPARE_TIMEOUT_MILLIS = 5000L;
    public static final long DEFAULT_COMMIT_TIMEOUT_MILLIS = 5000L;

    /** Delivery passes attempted per commit/abort/recover call before giving up until the next call. */
    private static final int MAX_DELIVERY_PASSES = 3;

    private enum Decision { COMMIT, ABORT }

    private static final class TxState {
        final String txId;
        /** Participants registered in this JVM, in enlistment order (index = log index). */
        final List<Participant> participants = new ArrayList<Participant>();
        /** Participants whose decision application has been acknowledged. */
        final Set<Integer> acked = new HashSet<Integer>();
        /** Number of ENLIST records in the log for this transaction. */
        int loggedEnlistCount;
        Decision decision;
        boolean done;

        TxState(String txId) {
            this.txId = txId;
        }
    }

    private final Clock clock;
    private final RecoveryLog log;
    private final long prepareTimeoutMillis;
    private final long commitTimeoutMillis;
    private final Map<String, TxState> states = new LinkedHashMap<String, TxState>();

    public Coordinator(Clock clock, File logDir) {
        this(clock, logDir, DEFAULT_PREPARE_TIMEOUT_MILLIS, DEFAULT_COMMIT_TIMEOUT_MILLIS);
    }

    public Coordinator(Clock clock, File logDir,
                       long prepareTimeoutMillis, long commitTimeoutMillis) {
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        if (logDir == null) {
            throw new NullPointerException("logDir");
        }
        if (prepareTimeoutMillis < 0 || commitTimeoutMillis < 0) {
            throw new IllegalArgumentException("timeouts must be >= 0");
        }
        this.clock = clock;
        this.prepareTimeoutMillis = prepareTimeoutMillis;
        this.commitTimeoutMillis = commitTimeoutMillis;
        try {
            this.log = new RecoveryLog(logDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open recovery log in " + logDir, e);
        }
        replay();
    }

    /** Starts a new transaction and returns its id. */
    public synchronized String begin() {
        String txId = "tx-" + UUID.randomUUID().toString();
        writeLog("BEGIN " + txId);
        states.put(txId, new TxState(txId));
        return txId;
    }

    /**
     * Enlists a participant. After a coordinator restart, call this again
     * for the same transaction (in the original enlistment order) to
     * re-attach participants before {@link #recover()} or further
     * commit/abort calls; re-registration does not duplicate log records.
     */
    public synchronized void enlist(String txId, Participant participant) {
        if (participant == null) {
            throw new NullPointerException("participant");
        }
        TxState st = requireTx(txId);
        if (st.participants.size() < st.loggedEnlistCount) {
            // Re-registration of a participant already present in the log
            // (coordinator restarted); allowed even after the decision so
            // that recovery can finish delivering it.
            st.participants.add(participant);
        } else {
            if (st.decision != null) {
                throw new IllegalStateException("transaction already decided: " + txId);
            }
            int index = st.loggedEnlistCount;
            writeLog("ENLIST " + txId + " " + index);
            st.loggedEnlistCount++;
            st.participants.add(participant);
        }
    }

    /**
     * Runs two-phase commit for the transaction. The returned result
     * reflects the durable decision; it never changes once made.
     */
    public synchronized CommitResult commit(String txId) {
        TxState st = requireTx(txId);
        if (st.decision == Decision.ABORT) {
            deliverRollbacks(st);
            return CommitResult.aborted(txId, isFullyAcked(st));
        }
        if (st.decision == Decision.COMMIT) {
            deliverCommits(st);
            return CommitResult.committed(txId, isFullyAcked(st));
        }

        // Phase 1: prepare.
        boolean allYes = st.participants.size() >= st.loggedEnlistCount;
        for (int i = 0; allYes && i < st.participants.size(); i++) {
            allYes = prepareOne(st.participants.get(i), txId);
        }

        // Decision (durable before any delivery).
        st.decision = allYes ? Decision.COMMIT : Decision.ABORT;
        writeLog("DECISION " + txId + " " + st.decision.name());

        // Phase 2: deliver the decision.
        if (st.decision == Decision.COMMIT) {
            deliverCommits(st);
            return CommitResult.committed(txId, isFullyAcked(st));
        }
        deliverRollbacks(st);
        return CommitResult.aborted(txId, isFullyAcked(st));
    }

    /**
     * Aborts the transaction. Only valid before a COMMIT decision exists;
     * a decided COMMIT can never be reversed.
     */
    public synchronized void abort(String txId) {
        TxState st = requireTx(txId);
        if (st.decision == Decision.COMMIT) {
            throw new IllegalStateException("transaction already committed: " + txId);
        }
        if (st.decision == null) {
            st.decision = Decision.ABORT;
            writeLog("DECISION " + txId + " ABORT");
        }
        deliverRollbacks(st);
    }

    /**
     * Replays unfinished business:
     * - transactions with no logged decision are aborted (the coordinator
     *   crashed before deciding, so the transaction cannot complete);
     * - transactions with a logged decision get the decision re-delivered
     *   to every participant that has not acknowledged yet, retrying until
     *   delivery succeeds. Decisions are never changed.
     *
     * Participants of a restarted coordinator must be re-attached via
     * {@link #enlist(String, Participant)} first; transactions whose
     * participants are missing or still failing stay in
     * {@link RecoveryReport#getPendingTxIds()} and are retried by the next
     * {@code recover()} call.
     */
    public synchronized RecoveryReport recover() {
        List<String> committed = new ArrayList<String>();
        List<String> aborted = new ArrayList<String>();
        List<String> pending = new ArrayList<String>();
        for (TxState st : states.values()) {
            if (!st.done) {
                if (st.decision == null) {
                    st.decision = Decision.ABORT;
                    writeLog("DECISION " + st.txId + " ABORT");
                }
                if (st.decision == Decision.COMMIT) {
                    deliverCommits(st);
                } else {
                    deliverRollbacks(st);
                }
            }
            if (st.done) {
                if (st.decision == Decision.COMMIT) {
                    committed.add(st.txId);
                } else {
                    aborted.add(st.txId);
                }
            } else {
                pending.add(st.txId);
            }
        }
        return new RecoveryReport(committed, aborted, pending);
    }

    /** Releases the log file handle. */
    public synchronized void close() {
        log.close();
    }

    // ------------------------------------------------------------------

    private void replay() {
        List<String> records;
        try {
            records = log.readAll();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read recovery log", e);
        }
        for (String record : records) {
            String[] f = record.split(" ");
            String type = f[0];
            if ("BEGIN".equals(type) && f.length == 2) {
                states.put(f[1], new TxState(f[1]));
            } else if ("ENLIST".equals(type) && f.length == 3) {
                TxState st = states.get(f[1]);
                if (st != null) {
                    st.loggedEnlistCount = Math.max(st.loggedEnlistCount, Integer.parseInt(f[2]) + 1);
                }
            } else if ("DECISION".equals(type) && f.length == 3) {
                TxState st = states.get(f[1]);
                if (st != null) {
                    st.decision = "COMMIT".equals(f[2]) ? Decision.COMMIT : Decision.ABORT;
                }
            } else if ("ACK".equals(type) && f.length == 3) {
                TxState st = states.get(f[1]);
                if (st != null) {
                    st.acked.add(Integer.valueOf(f[2]));
                }
            } else if ("DONE".equals(type) && f.length == 2) {
                TxState st = states.get(f[1]);
                if (st != null) {
                    st.done = true;
                }
            }
        }
    }

    private boolean prepareOne(Participant participant, String txId) {
        long start = clock.nowMillis();
        String vote;
        try {
            participant.prepare(txId);
            vote = participant.vote(txId);
        } catch (Exception e) {
            return false;
        }
        if (clock.nowMillis() - start > prepareTimeoutMillis) {
            return false;
        }
        return Participant.VOTE_YES.equals(vote);
    }

    private void deliverCommits(TxState st) {
        deliver(st, true);
    }

    private void deliverRollbacks(TxState st) {
        deliver(st, false);
    }

    private void deliver(TxState st, boolean commit) {
        for (int pass = 0; pass < MAX_DELIVERY_PASSES && !isFullyAcked(st); pass++) {
            boolean progress = false;
            for (int i = 0; i < st.participants.size(); i++) {
                if (st.acked.contains(Integer.valueOf(i))) {
                    continue;
                }
                if (deliverOne(st.participants.get(i), st.txId, commit)) {
                    st.acked.add(Integer.valueOf(i));
                    writeLog("ACK " + st.txId + " " + i);
                    progress = true;
                }
            }
            if (!progress) {
                break;
            }
        }
        if (isFullyAcked(st) && !st.done) {
            writeLog("DONE " + st.txId);
            st.done = true;
        }
    }

    private boolean deliverOne(Participant participant, String txId, boolean commit) {
        long start = clock.nowMillis();
        try {
            if (commit) {
                participant.commit(txId);
            } else {
                participant.rollback(txId);
            }
        } catch (Exception e) {
            return false;
        }
        // A slow participant may actually have applied the decision; the
        // retry is safe because delivery is idempotent.
        return clock.nowMillis() - start <= commitTimeoutMillis;
    }

    private boolean isFullyAcked(TxState st) {
        return st.acked.size() >= st.loggedEnlistCount;
    }

    private TxState requireTx(String txId) {
        TxState st = states.get(txId);
        if (st == null) {
            throw new IllegalArgumentException("unknown transaction: " + txId);
        }
        return st;
    }

    private void writeLog(String record) {
        try {
            log.append(record);
        } catch (IOException e) {
            throw new UncheckedIOException("recovery log write failed", e);
        }
    }
}
