# ADR-0019: No concurrent ingestion runs across instances, with a PostgreSQL session advisory lock

- **Status:** Accepted
- **Date:** 2026-11-06
- **Related spec:** [specs/004-persistence-and-pipeline](../../specs/004-persistence-and-pipeline/spec.md)
- **Refines:** [ADR-0006](0006-scheduled-pre-ingestion.md) (lifts its "single instance only" limitation)

## Context

ADR-0006 forbids overlapping runs: they would race on the same day snapshots and double the load on the providers. A
JVM-local guard holds only for one instance. With two replicas, both schedulers tick and both accept `POST`. The
database is the only component the instances share. A run can last up to the 10 min run deadline, and the process
can die mid-run.

## Decision

We will take a **session-level `pg_try_advisory_lock(<constant key>)`** before a run starts:

- The lock is held on a **dedicated JDBC connection** that the run keeps for its whole duration. A session lock
  belongs to the connection, not to a transaction or a thread. Borrowing pooled connections would release it, or
  leak it to an unrelated borrower, as soon as the connection went back to the pool. The connection is closed (lock
  released) in a `finally` when the run ends, whatever the outcome.
- **The lock is checked before each day is published:** a cheap query on the lock connection that also confirms
  the lock is still listed in `pg_locks` for that backend. If the connection was lost (network blip, database
  restart, the backend was terminated), PostgreSQL has already released the lock, and another instance may be
  running. The run then publishes nothing more and is stored as `FAILED` (lock lost).
- If the process dies, PostgreSQL ends the session and releases the lock: no expiry to tune and no manual cleanup.
  The next run that acquires the lock marks any `RUNNING` row left behind as `ABORTED`: if nobody holds the lock,
  that run is dead.
- A failed `try` returns at once. A scheduled tick is skipped, and `POST` answers `409` with the id of the
  `RUNNING` row.
- Behind a port (`IngestionRunLock`), so the use case stays framework-free and unit tests use an in-memory lock.

## Alternatives considered

1. **In-memory guard (`AtomicBoolean` / `ReentrantLock`)** — correct for one instance only; ADR-0006 listed this as a
   limitation. It is still useful as a fast path, but it isn't sufficient.
2. **Lease row** (`UPDATE run_lock SET owner = :me, expires_at = now() + :ttl WHERE expires_at < now()`) — works
   through any connection, but needs an expiry: too short and a slow run loses it while running, too long and a crash
   blocks runs until it expires. It would also need a heartbeat. The advisory lock gets both from the session
   lifetime.
3. **ShedLock** — a well-known library built on a lease table (alternative 2) with annotations on `@Scheduled`
   methods. It doesn't cover the `POST` path's need to answer 409 with the running id, and it adds a dependency for
   about 40 lines of code.
4. **Transaction-level advisory lock (`pg_try_advisory_xact_lock`)** — would require one transaction open for the
   whole run, across days that must commit independently (ADR-0017). Rejected.

## Consequences

- **Positive:** correct across instances, released automatically by crashes, no new dependency, and no clock or TTL.
- **Negative / trade-offs:** a run holds one pooled connection for up to 10 min (the pool is sized for it). Advisory
  locks are PostgreSQL-specific, so they live in the persistence adapter. With a transaction-pooling proxy
  (PgBouncer in transaction mode), session locks don't work; the database would have to be reached directly or in
  session mode. A lost lock is detected at the next day boundary, not instantly, so at most the day being written
  can overlap with another instance's run. That day is still atomic (ADR-0017).
- **Follow-ups:** `SOLUTION.md` removes the single-instance limitation and records the PgBouncer caveat.
