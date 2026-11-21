# G2: usage, replay, incident
Branch: `g2c/usage-replay-incident` from `e71938b`. Local delivery only: three ordered work-unit commits.
Runner: Java 21 Maven `test` with PostgreSQL 16 and `INTAKE_IT_JDBC_URL` set.

## Specs

1. S1 — “150,000 calls per rolling day and at most 25 concurrent requests”, shared across replicas. `V5__salesforce_budget.sql`; reserve before each write, release after it, stop the batch on denial with `BUDGET_EXHAUSTED`. Pin admission, both limits, and release on failure against PostgreSQL.
2. S2 — “replay **one** event to **one** destination, with a written justification, without creating a new business event.” `V6__replay.sql`, `ReplayService`, and `ReplayCli`. Pin `DEAD` to `PENDING` / `REPLAY`, missing reason, idempotency, `CLAIMED` refusal, and `STALE_SKIPPED` without overwriting.
3. S3 — “Write `Incident0412IT` (against PostgreSQL, with `SimulatedSalesforce`)” to prove queryable `DEAD` outcomes, unrelated `APPLIED` progress, corrected application, stale replay suppression, and recorded justification and outcome. Update `practical/README.md`.
4. S4 — “Never merge, never force-push, never touch `main`.” No deployment or real external calls. Each of the three commits must test green on its own.

## Tasks

- [x] T1 / S1, S4 — Shared Salesforce usage; inline; risk item 4 (cross-replica admission); `6a2bf42`, 190 passed from exact commit.
- [x] T2 / S2, S4 — Replay with audit and CLI; inline; risk item 4 (claim/reset and duplicate ordering); `f25afb3`, exact tree passed 199.
- [x] T3 / S3, S4 — Incident scenario and evidence; inline; 200 passed on the isolated candidate. The commit that introduces this log is the T3 commit.

## Log

- L1: Read the attached mission and execute it end to end. Do not ask questions: decide, record the reason in the code, run the suite, commit in three units, and finish with the report the mission asks for.
- L2: Baseline `e71938b`; receipt-driven development is disabled by the existing global setting. Replay and incident depend on earlier units, so no parallel writers.
- L3: T1 tests failed before V5 existed; four requested rules and two last-slot races are now covered. Independent PostgreSQL probes verified serialization, previous-day accounting, exceptions, and unchanged remote `RATE_LIMITED` behavior.
- L4: T2 contracts were written first; test compilation failed before `ReplayService` and `ReplayCli` existed. Six PostgreSQL tests then passed. Delivery strategy is three local units as requested; no PR planned.
- L5: Packaged the staged replay code offline; the executable CLI returned `EVENT_NOT_FOUND` and exit 1, and PostgreSQL recorded the same outcome.
- L6: T3 adds an incident regression scenario rather than changing behavior. `Incident0412IT` passed against PostgreSQL with the simulator; the first combined candidate run passed 197 tests.
- L7: Independent replay probes verified the concurrent duplicate, worker-claim, rollback and stale-event paths. Changed-destination key reuse reproduced a false CLI success. The regression failed before the fix, then passed with `KEY_TARGET_CONFLICT` and no audit or queue mutation. Added durable regressions for key contention and rollback; all nine replay tests passed. T2 exceeds the advisory 400-line budget because its CLI, migration, tests and operator documentation belong to the same requested commit.
- L8: The isolated T3 candidate passed 200 tests (158 without PostgreSQL, 42 PostgreSQL integration), no skips. No Azure, live Salesforce or external broker was used.
- L9: The targeted independent recheck on the exact T2 tree confirmed `KEY_TARGET_CONFLICT` exits 1, an identical retry exits 0, and neither retry changes the event, queue, or audit.
