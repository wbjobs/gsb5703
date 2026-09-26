# Two-Phase Commit Coordinator (com.gsb.tpc)

Pure JDK 8, standard library only. No Maven/Gradle/JUnit.

## Layout

- `src/com/gsb/tpc/` — coordinator library
  - `Coordinator` — `begin()`, `enlist(txId, p)`, `commit(txId)`, `abort(txId)`, `recover()`
  - `Participant` — `prepare`, `vote`, `commit`, `rollback` (commit/rollback must be idempotent)
  - `Clock` — injected time source for all timeout decisions (`SystemClock` provided)
  - `CommitResult`, `RecoveryReport` — outcome/report value types
  - `RecoveryLog` — append-only write-ahead log (`coordinator.log` in the given directory)
- `test/com/gsb/tpc/test/` — plain-`main` tests, no frameworks
- `run-tests.sh` — compiles with `javac --release 8` and runs the suite

## Protocol

1. **Prepare**: every enlisted participant is asked to `prepare` + `vote`.
   Any NO vote, exception, or timeout (measured via the injected `Clock`)
   aborts the whole transaction; every participant — including YES voters —
   receives `rollback`.
2. **Decision**: `DECISION <txId> COMMIT|ABORT` is appended to the log and
   fsync'd *before* any delivery. A logged decision never changes.
3. **Delivery**: the decision is delivered to every participant; each
   acknowledgement is logged (`ACK`), then `DONE`. Failures and timeouts
   are retried; `recover()` keeps retrying after a crash until every
   participant has acknowledged, so no partial commit survives.
4. **Recovery**: `recover()` replays the log. Transactions with no logged
   decision are aborted; transactions with a logged decision get it
   re-delivered to unacknowledged participants. After a restart, re-attach
   participants with `enlist(txId, p)` in the original order, then call
   `recover()` (repeatedly, until `RecoveryReport.isFullyRecovered()`).

Duplicate delivery is possible (e.g. a participant applied the decision but
answered too slowly), which is why `Participant.commit`/`rollback` must be
idempotent.

## Run

```sh
./run-tests.sh
```
