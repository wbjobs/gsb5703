# Two-Phase Commit Coordinator (com.gsb.tpc)

Pure JDK 8, standard library only. No Maven, Gradle, JUnit or any
third-party dependency. Build and test with:

    ./run-tests.sh

## API

- `Coordinator` — `begin()`, `enlist(txId, p)`, `commit(txId)`,
  `abort(txId)`, `recover()`
- `Participant` — `prepare(txId)`, `vote(txId)` (`Participant.YES`/`NO`),
  `commit(txId)`, `rollback(txId)`; commit/rollback must be idempotent
- `CommitResult` — `COMMITTED`, `ABORTED`, `COMMIT_IN_PROGRESS`
- `RecoveryReport` — committed / aborted / pending transaction ids
- `Clock` — injected time source; the implementation never calls
  `System.currentTimeMillis` (enforced by `run-tests.sh`)
- `TpcCoordinator` — the implementation
  (`new TpcCoordinator(log, clock, prepareTimeoutMs, commitTimeoutMs)`)
- `FileTransactionLog` — append-only, fsynced decision log

## Protocol

The coordinator writes a write-ahead log (one record per line):

    BEGIN <txId>
    DECISION <txId> COMMIT|ABORT
    END <txId>

- **Phase 1 (prepare):** every participant is asked to `prepare` + `vote`.
  Any `NO`, exception or timeout aborts the whole transaction; the ABORT
  decision is logged and *all* participants — including YES voters — receive
  `rollback`.
- **Commit point:** the `DECISION ... COMMIT` record is fsynced before any
  `commit` is delivered. Once durable, the decision can never change.
- **Phase 2 (commit):** `commit` is delivered to every participant.
  Failures/timeouts are retried; if the commit timeout expires first,
  `commit()` returns `COMMIT_IN_PROGRESS` and a later `commit()`/`recover()`
  keeps retrying until all participants acknowledge (`END` is logged).
- **Crash recovery:** create a new `TpcCoordinator` over the same log,
  re-`enlist` the participants of in-flight transactions, call `recover()`.
  Transactions without a decision are presumed aborted; transactions with a
  durable decision get that decision re-delivered (idempotently) until done.
  Already decided outcomes never change.
- **Idempotency:** after a crash the new coordinator has no delivery memory,
  so participants may receive the same commit/rollback twice; they must (and
  in the tests do) ignore duplicates.

Timeouts are enforced by running participant calls on an executor and
comparing against deadlines computed from the injected `Clock`.
