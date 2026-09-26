package com.gsb.tpc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Two-phase commit coordinator with a durable write-ahead decision log.
 *
 * Protocol:
 *   BEGIN &lt;txId&gt;               - transaction created
 *   DECISION &lt;txId&gt; COMMIT|ABORT - atomic commit/abort point (fsynced)
 *   END &lt;txId&gt;                 - decision delivered to every participant
 *
 * Recovery replays the log: transactions without a decision are presumed
 * aborted; transactions with a decision but no END get the decision
 * re-delivered (participants are idempotent) until all acknowledge.
 *
 * All timeouts are computed from the injected {@link Clock}; this class never
 * reads the wall clock directly.
 */
public final class TpcCoordinator implements Coordinator {

    private static final String DECISION_COMMIT = "COMMIT";
    private static final String DECISION_ABORT = "ABORT";

    private static final long POLL_SLEEP_MILLIS = 1L;

    /** Raised when a participant call does not finish before its deadline. */
    public static final class ParticipantTimeoutException extends RuntimeException {
        public ParticipantTimeoutException(String message) {
            super(message);
        }
    }

    private static final class Tx {
        final String id;
        final List<Participant> participants = new ArrayList<Participant>();
        final Set<Participant> delivered =
                Collections.newSetFromMap(new IdentityHashMap<Participant, Boolean>());
        volatile String decision; // null, DECISION_COMMIT or DECISION_ABORT
        volatile boolean ended;

        Tx(String id) {
            this.id = id;
        }
    }

    private final TransactionLog log;
    private final Clock clock;
    private final long prepareTimeoutMillis;
    private final long commitTimeoutMillis;
    private final ExecutorService executor;
    private final Map<String, Tx> transactions = new LinkedHashMap<String, Tx>();

    /**
     * @param log                  durable recovery log (shared across restarts)
     * @param clock                time source for all timeout decisions
     * @param prepareTimeoutMillis total budget for the prepare/vote phase
     * @param commitTimeoutMillis  budget for one delivery round of a decision
     */
    public TpcCoordinator(TransactionLog log, Clock clock,
                          long prepareTimeoutMillis, long commitTimeoutMillis) {
        if (log == null || clock == null) {
            throw new NullPointerException("log and clock are required");
        }
        this.log = log;
        this.clock = clock;
        this.prepareTimeoutMillis = prepareTimeoutMillis;
        this.commitTimeoutMillis = commitTimeoutMillis;
        this.executor = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tpc-coordinator");
                t.setDaemon(true);
                return t;
            }
        });
        replay();
    }

    // ------------------------------------------------------------------ log

    private void replay() {
        for (String record : log.records()) {
            String[] parts = record.split(" ");
            if (parts.length < 2) {
                continue;
            }
            if ("BEGIN".equals(parts[0])) {
                transactions.put(parts[1], new Tx(parts[1]));
            } else if ("DECISION".equals(parts[0]) && parts.length >= 3) {
                Tx tx = transactions.get(parts[1]);
                if (tx != null) {
                    tx.decision = parts[2];
                }
            } else if ("END".equals(parts[0])) {
                Tx tx = transactions.get(parts[1]);
                if (tx != null) {
                    tx.ended = true;
                }
            }
        }
    }

    // -------------------------------------------------------------- public

    @Override
    public synchronized String begin() {
        String txId = "tx-" + UUID.randomUUID().toString();
        log.append("BEGIN " + txId);
        transactions.put(txId, new Tx(txId));
        return txId;
    }

    @Override
    public synchronized void enlist(String txId, Participant p) {
        if (p == null) {
            throw new NullPointerException("participant");
        }
        Tx tx = requireTx(txId);
        if (tx.ended) {
            throw new IllegalStateException("transaction " + txId + " is already finished");
        }
        if (!tx.participants.contains(p)) {
            tx.participants.add(p);
        }
    }

    @Override
    public CommitResult commit(String txId) {
        Tx tx;
        synchronized (this) {
            tx = requireTx(txId);
        }
        if (tx.decision == null) {
            preparePhase(tx);
        }
        if (DECISION_ABORT.equals(tx.decision)) {
            deliverDecision(tx, false);
            return CommitResult.ABORTED;
        }
        boolean finished = deliverDecision(tx, true);
        return finished ? CommitResult.COMMITTED : CommitResult.COMMIT_IN_PROGRESS;
    }

    @Override
    public void abort(String txId) {
        Tx tx;
        synchronized (this) {
            tx = requireTx(txId);
        }
        if (DECISION_COMMIT.equals(tx.decision)) {
            throw new IllegalStateException(
                    "transaction " + txId + " was already decided COMMIT; decisions are final");
        }
        if (tx.decision == null) {
            decide(tx, DECISION_ABORT);
        }
        deliverDecision(tx, false);
    }

    @Override
    public RecoveryReport recover() {
        List<String> committed = new ArrayList<String>();
        List<String> aborted = new ArrayList<String>();
        List<String> pending = new ArrayList<String>();
        List<Tx> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<Tx>(transactions.values());
        }
        for (Tx tx : snapshot) {
            if (tx.decision == null) {
                // Presumed abort: the coordinator crashed before deciding.
                decide(tx, DECISION_ABORT);
            }
            if (!tx.ended) {
                boolean isCommit = DECISION_COMMIT.equals(tx.decision);
                if (isCommit && currentParticipants(tx).isEmpty()) {
                    // Nobody re-enlisted yet; cannot deliver, retry next recover().
                    pending.add(tx.id);
                    continue;
                }
                if (!deliverDecision(tx, isCommit)) {
                    pending.add(tx.id);
                    continue;
                }
            }
            if (DECISION_COMMIT.equals(tx.decision)) {
                committed.add(tx.id);
            } else {
                aborted.add(tx.id);
            }
        }
        return new RecoveryReport(committed, aborted, pending);
    }

    /** Stop the internal timeout executor. The coordinator must not be used afterwards. */
    public void shutdown() {
        executor.shutdownNow();
    }

    // -------------------------------------------------------------- phases

    private void preparePhase(Tx tx) {
        long deadline = clock.nowMillis() + prepareTimeoutMillis;
        for (Participant p : currentParticipants(tx)) {
            String vote = prepareAndVote(tx.id, p, deadline);
            if (!Participant.YES.equals(vote)) {
                // Any negative vote, failure or timeout aborts the whole
                // transaction; every participant (YES voters included) is
                // rolled back below.
                decide(tx, DECISION_ABORT);
                return;
            }
        }
        // Atomic commit point: once this record is durable the transaction
        // will commit, no matter what happens afterwards.
        decide(tx, DECISION_COMMIT);
    }

    private String prepareAndVote(final String txId, final Participant p, long deadlineMillis) {
        final String[] vote = new String[1];
        try {
            runWithTimeout(new Runnable() {
                @Override
                public void run() {
                    p.prepare(txId);
                    vote[0] = p.vote(txId);
                }
            }, deadlineMillis);
        } catch (Exception e) {
            return null;
        }
        return vote[0];
    }

    /**
     * Deliver the current decision to every participant. Returns true when
     * all participants acknowledged and the END record has been written.
     * Retries while progress is made and the deadline has not passed;
     * delivery is idempotent so re-delivery after a crash is safe.
     */
    private boolean deliverDecision(Tx tx, boolean isCommit) {
        final String txId = tx.id;
        long deadline = clock.nowMillis() + commitTimeoutMillis;
        while (!allDelivered(tx) && clock.nowMillis() <= deadline) {
            boolean progress = false;
            for (final Participant p : currentParticipants(tx)) {
                if (isDelivered(tx, p)) {
                    continue;
                }
                try {
                    runWithTimeout(new Runnable() {
                        @Override
                        public void run() {
                            if (isCommit) {
                                p.commit(txId);
                            } else {
                                p.rollback(txId);
                            }
                        }
                    }, deadline);
                    markDelivered(tx, p);
                    progress = true;
                } catch (Exception e) {
                    // Participant failed or timed out; retried in the next
                    // round, by the next commit()/recover() call, or after
                    // a crash by the recovering coordinator.
                }
            }
            if (!progress) {
                break;
            }
        }
        if (allDelivered(tx)) {
            synchronized (this) {
                if (!tx.ended) {
                    log.append("END " + tx.id);
                    tx.ended = true;
                }
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ helpers

    private void decide(Tx tx, String decision) {
        synchronized (this) {
            if (tx.decision != null) {
                if (!tx.decision.equals(decision)) {
                    throw new IllegalStateException(
                            "transaction " + tx.id + " already decided " + tx.decision);
                }
                return;
            }
            log.append("DECISION " + tx.id + " " + decision);
            tx.decision = decision;
        }
    }

    private void runWithTimeout(Runnable task, long deadlineMillis) {
        Future<?> future = executor.submit(task);
        try {
            while (!future.isDone()) {
                if (clock.nowMillis() > deadlineMillis) {
                    future.cancel(true);
                    throw new ParticipantTimeoutException("participant call timed out");
                }
                Thread.sleep(POLL_SLEEP_MILLIS);
            }
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting for participant", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        }
        if (clock.nowMillis() > deadlineMillis) {
            throw new ParticipantTimeoutException("participant call finished too late");
        }
    }

    private Tx requireTx(String txId) {
        Tx tx = transactions.get(txId);
        if (tx == null) {
            throw new IllegalArgumentException("unknown transaction " + txId);
        }
        return tx;
    }

    private List<Participant> currentParticipants(Tx tx) {
        synchronized (this) {
            return new ArrayList<Participant>(tx.participants);
        }
    }

    private boolean isDelivered(Tx tx, Participant p) {
        synchronized (this) {
            return tx.delivered.contains(p);
        }
    }

    private void markDelivered(Tx tx, Participant p) {
        synchronized (this) {
            tx.delivered.add(p);
        }
    }

    private boolean allDelivered(Tx tx) {
        synchronized (this) {
            return tx.delivered.containsAll(tx.participants);
        }
    }

}
