# ADR-0004 — Durable intake: one transaction, one constraint, claimed work

- **Status:** Accepted
- **Date:** 2026-11-21
- **Supersedes:** the in-memory store as the default path (it survives as a demo profile)

## Context

The intake acknowledges a provider only after the notification is durable, and owes two independent
pieces of work per accepted event: publish the canonical event, and write to Salesforce. A process that
dies between the acknowledgement and the work must not lose the payment, and two replicas receiving the
same notification must not both accept it.

The first implementation kept this contract in memory behind `IntakeStore`. The boundary was right and
the guarantee was not: a restart lost every accepted event, and two replicas had two separate inboxes.

## Decision

1. **PostgreSQL is the operational system of record** for receipt and processing. The inbox row and both
   outbox jobs are written in one transaction, and the unique constraint on `(provider, event_id)`
   decides duplicates. Acceptance inserts with `ON CONFLICT DO NOTHING`; a zero row count means another
   delivery committed first. No code path reads before it writes. G3a counts the duplicate delivery
   against that accepted row without changing its canonical fact, jobs or projection.
2. **Migrations run before the first query.** Flyway applies `db/migration` and records what it applied;
   a process that cannot migrate refuses to start.
3. **Publication is claimed, not read.** A worker takes a bounded batch under a 30-second lease with
   `FOR UPDATE SKIP LOCKED`, and every completion presents the claim token. A completion that matches no
   token changes nothing and is reported as `ClaimLostException`.
4. **The payment projection moves with the event.** `payment_state` is keyed by `payment_key` and keeps
   the newest status *by the provider's status timestamp*, not by arrival, so a delayed capture cannot
   undo a newer reversal. `remote_applied_status` is written only by the Salesforce relay; nothing here
   infers that the remote system applied anything.

## Alternatives and why not

- **Kafka or Service Bus as the inbox.** A broker acknowledges before the database knows the payment
  exists, so the "durable before 2xx" property depends on a broker transaction the provider cannot see.
  The outbox keeps the acknowledgement tied to one local commit.
- **Read-then-insert with a retry on conflict.** Two replicas can both read "absent" and both attempt the
  insert; the loser then has to unwind work it may already have published. The constraint decides first.
- **A distributed lock per event.** It adds a lease to the deduplication path and still leaves the
  "accepted but not written" window. The constraint has no window.
- **`SELECT ... FOR UPDATE` without `SKIP LOCKED`.** Two workers queue on the same rows instead of taking
  different ones; throughput collapses exactly when the backlog is the problem.
- **Claiming by deleting the row.** Loses the audit trail and makes an expired lease indistinguishable
  from work that never existed.
- **Claims without tokens.** An expired worker finishing late marks someone else's job done, and the
  payment stops moving silently. That is the failure this design is built to make impossible.

## Consequences

- One extra dependency (driver, pool, migration runner) and one schema to keep migrated.
- The retry curve exists twice: `RetrySchedule` in Java and a SQL expression in the migration. They are
  pinned by an integration test that asserts 1 s, 2 s, 4 s from the database; a future change must move
  both or fail that test.
- Publication is still at-least-once. `MessageId` is stable per event so consumers can collapse a
  duplicate, and the system never claims transport exactly-once.
- **Dedupe retention:** the inbox keeps every event for now. The providers' replay windows are 300 s and
  600 s, and a notification can be replayed days later after an incident, so the retention must outlive
  the longest supported replay — the intention is 90 days with a purge job, and the number is a
  placeholder until an operations owner commits to it. Deleting rows earlier than the replay window
  would turn a legitimate provider retry into a second accepted payment, which is the one failure this
  table exists to prevent.
- The pool is one size for the whole process. Per-role pools belong with splitting the process into
  intake, publisher and relay roles.

## Evidence

- 146 tests, 17 of them against PostgreSQL 16, including eight concurrent duplicates committing one
  event, a trigger that rejects the second job leaving zero rows, an expired lease being reclaimed, and
  the projection keeping a reversal against a late capture.
- Two replicas, one database, the same signed event to both at once: `ACCEPTED` and `DUPLICATE`, one
  event, two jobs, one publication.
- A container killed with `SIGKILL` immediately after `200 ACCEPTED` left one event, two jobs and one
  projection row; a fresh container on the same database published the pending job without any manual
  step.

## What would change this decision

- A destination that cannot tolerate duplicate delivery: that needs consumer-side deduplication or an
  idempotency key agreed with the destination, not a stronger claim here.
- A volume that makes a single primary the bottleneck: then the inbox partitions by provider, and the
  uniqueness rule must be proven to hold across partitions before partitioning is adopted.
- A regulatory retention shorter than the replay window: then the tombstone strategy replaces row
  retention, and the dedupe key survives the payload.
