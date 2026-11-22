# ADR-0002: Implementation stack and data store

- **Status:** Accepted; amended 2026-11-21 (canonical-event publication, see §Amendment)
- **Date:** 2026-11-21
- **Approved by:** Fabián, explicit approval, 2026-11-21 (local, UTC-05)
- **Amendment approved by:** Fabián, explicit approval of the canonical-event publication package, 2026-11-21 (America/Bogota, UTC-05; date verified with `date`)
- **Candidate confirmation:** data store — PostgreSQL Flexible Server B1MS — confirmed on 2026-11-21; the stack below is the decision of record that the design and Part B are built on.
- **Delivery refinement:** Service Bus Premium is the private-network target; B1MS and 64 GB are
  starting sizes, not verified production limits. See [Part C](../infra/AZURE_SERVICES.md).

## Context

Part B implements the intake of payment notifications for Quetzal Pagos (Mexico) and Andina Pay
(Colombia). Two choices are demanded by the assessment and cannot be left implicit:

- `README.md`: MuleSoft (Mule 4, DataWeave, MUnit) is the reference stack; Java 21 (Spring Boot 3 or
  Azure Functions) is accepted **if justified in ADR-0002**.
- `TASKS.md`: "If you choose Java for Part B, this is where you justify it."

The scenario removes the usual objection to a Java service in this ecosystem: the platform "can run
integration applications (Mule 4 runtime) and can also front services that run on Azure"
(`SCENARIO.md`:18). A Java service behind the API Manager is therefore sanctioned architecture, not
an exception. The same section still requires every provider-facing API to be published through the
API Manager and internal APIs to require an OIDC token (`SCENARIO.md`:18-19), which constrains the
intake's ingress regardless of stack.

The store is not optional. FR4 needs a unique business key per provider to deduplicate
(`SCENARIO.md`:42); FR5 needs the last applied state to reject a notification that contradicts the
provider's latest real state (`SCENARIO.md`:43); FR7 needs an index operations can query without
reading raw logs (`SCENARIO.md`:45). Volume is ≈15,000 notifications/day, up to 120,000/day at the
month-end peak with 40/s for 15 minutes, and 3% duplicates (`SCENARIO.md`:53). The write model this
ADR sizes is the inbox insert, two outbox inserts, one `payment_state` upsert and four outbox
claim/completion updates (two per row across the two destinations). The upsert is an insert for the
first event of a payment and an update
afterwards, split by the 1.1 events-per-payment assumption, and it is counted once, never folded into
the three inserts. Including the ordering outcome update, that yields ≈152 inserts/s and ≈349
logical row operations/s at the 40/s burst before retries and additional bookkeeping
(`docs/design/SOLUTION_DESIGN.md` §6). This is not a benchmark.

## Options considered

### Stack

#### Option 1 — Mule 4 + DataWeave + MUnit (reference stack)

- Pros: the squad's corporate default; the connector and flow model is what Meridian already
  operates; MUnit is the testing tool the assessment names.
- Cons: Anypoint Platform credentials were not available, and `TASKS.md` itself acknowledges that
  "MuleSoft Enterprise dependencies may require credentials you do not have". Without them the Mule
  application cannot be built and its tests cannot be executed, while the deliverable asks for
  automated tests that prove behaviour and a `practical/README.md` stating how to run them.
  Unexecuted tests cannot be claimed as evidence.
- Verdict: rejected on verifiability, not on merit. Mule remains the corporate default and the design
  stays portable to it if provider-specific logic is isolated behind an adapter.

#### Option 2 — Java 21 behind the API Manager (chosen)

- Pros: builds and tests run locally and in CI with no vendor credentials; the scenario explicitly
  allows fronting Azure-hosted services through the API Manager; the candidate's day-to-day work is
  TypeScript/Node and Python services, so the code can be defended line by line in the live review.
- Cons: diverges from the corporate default; portability depends on keeping provider mapping out of
  the flow logic; Part B has to prove in tests what Mule connectors and MUnit would have provided.
- Verdict: chosen.

### Data store

#### Option 1 — Azure SQL Database, serverless tier

- Pros: scales to a configured maximum during bursts; pay per second of compute; the cheapest option
  on paper when the workload is idle.
- Cons: auto-pause requires zero sessions and zero CPU for the whole auto-pause delay (60 minutes by
  default, 15 minimum), and an outbox worker holds a connection pool and polls continuously, so the
  database never pauses. The 0.5 vCore minimum is then billed 24/7: at $0.521758 per vCore-hour,
  730 hours land at ≈ $190.44 per month for work a fixed tier does for ≈ $14.52.
- Verdict: rejected. Serverless billing rewards inactivity and this process has none.

#### Option 2 — Azure SQL Database, Standard S0/S1 (DTU)

- Pros: fixed monthly cost; unique index; the SQL Server model the Salesforce-side team already
  knows.
- Cons: S0 is a 10-DTU tier, and ≈349 logical row operations/s at the burst is not a load it is
  specified for, so throttling is a risk that needs testing. Batching reduces
  Salesforce calls, not the database write rate, so it does not remove the sizing question.
  S1 costs ≈ $29.03 per month.
- Verdict: viable second choice.

#### Option 3 — Azure Database for PostgreSQL, Flexible Server B1MS (chosen)

- Pros: burstable B-series accumulates CPU credits while idle and spends them during the burst, which
  matches a load that is idle most of the day with 15-minute peaks; fixed billing at $0.017 per hour
  ≈ $12.41 per month; unique index and transactions give FR4 and the inbox/outbox commit in the same
  transaction.
- Cons: B-series credits are finite, so sustained load above the baseline degrades to baseline and has
  to be watched; backup and high-availability configuration is more the team's responsibility than in
  the SQL Server defaults; it adds an engine next to SQL Server to operate and to hire for; at six
  countries the SKU grows, which is a Bicep parameter but still a change.
- Verdict: chosen.

#### Option 4 — Cosmos DB

- Pros: elastic write throughput; multi-region distribution.
- Cons: no unique index across partitions, so deduplication becomes a read-then-write race the
  application must resolve; the FR7 query by country and status becomes expensive depending on the
  partition key; complexity paid for scale this workload does not need: ≈1.4 notifications/s on a
  month-end peak day, against ≈0.17/s on a normal day and ≈0.33/s blended over the month.
- Verdict: rejected.

## Decision

1. **Stack:** Java 21, stateless intake and relay, hosted on Azure and published through the API
   Manager. Provider parsing, signature verification and state mapping live in one adapter per
   provider, which is what keeps the design portable to Mule 4 if the organisation imposes it.
   Justification rests on `README.md`:16, `TASKS.md`:24 and `SCENARIO.md`:18.
2. **Messaging:** Azure Service Bus **Premium private topic** with independent `finance` and
   `notifications` subscriptions and dead-letter enabled on each subscription, as drawn in
   `docs/design/SOLUTION_DESIGN.md` §1.2 and §2. The canonical event is published from the `CANONICAL_EVENT`
   outbox destination; the Salesforce relay polls the `SALESFORCE` destination directly. *Amended
   2026-11-21: the original decision was a queue between intake and relay; see the amendment below.*
3. **Data store:** **Azure Database for PostgreSQL, Flexible Server** with B1MS as the initial
   sizing candidate and an explicit SKU and tier parameter for scale-up. Logical schema, keys and
   indexes are in `docs/design/SOLUTION_DESIGN.md` §5:
   `payment_event` with unique `(provider, event_id)`, `payment_state` keyed by `payment_key`,
   `outbox` keyed by `outbox_id` with unique `(payment_event_id, destination)` and an index on
   `(destination, status, next_attempt_at, outbox_id)`, a small `worker_lease` row for single-active
   relay failover, and no partitioning by throughput.
4. The inbox row and its **two** outbox rows are committed in the same transaction before the
   provider is answered `2xx`, so the provider's retry is the last line of durability rather than the
   first (ADR-0001).

### Amendment 2026-11-21 — Canonical-event publication

**Approved by Fabián on 2026-11-21** (America/Bogota, UTC-05; date verified with `date`). This
amendment changes the messaging topology of decision 2 and adds a second outbox destination. It does
not change the Salesforce decision in ADR-0001, the Java 21 stack, the PostgreSQL store or the
acceptance of the original ADR.

#### What changed

- The Service Bus **queue between intake and relay** was replaced by a **Service Bus topic**
  with two independent subscriptions, `finance` and `notifications`. The consumers behind those
  subscriptions are out of implementation scope (FR6).
- The subsequent delivery refinement selects **Premium (1 MU)** to support Private Link. Standard
  would meet the message shape and probably the volume, but requires a public endpoint with
  tightly allowlisted fixed egress for all clients. The higher baseline cost buys private network
  isolation, not a guarantee of throughput. Revisit if network policy allows that alternative.
- Intake no longer publishes to the broker. In the same transaction that inserts the inbox row it
  inserts **two outbox jobs**: `SALESFORCE` and `CANONICAL_EVENT`.
- The Salesforce relay polls only the `SALESFORCE` destination. The event publisher polls only the
  `CANONICAL_EVENT` destination and publishes to the topic. Both are logical roles and may run in one
  deployment; nothing requires two deploys.
- Outbox uniqueness is `(payment_event_id, destination)`. The inbox natural key stays
  `(provider, event_id)`, so the same event id from two providers is two different events.
- Each destination has its own claim and lease, attempt counter, backoff and circuit breaker. A
  backlog or a failure in one does not block the other.
- Publication is at-least-once. `MessageId` is a stable function of `(provider, event_id)`, not the
  local inbox row id. No exactly-once delivery or processing is claimed.

#### Why

Publishing the canonical event directly from the intake is a dual write: the database commit and the
broker publish cannot be atomic. A crash between them either loses an event that was already
acknowledged or publishes an event the store does not have. The outbox closes that window and gives
FR6 the same durability guarantee as the Salesforce write (NFR3).

#### Consequences

- Positive: one commit covers both destinations; a broker outage does not fail the provider response;
  a Salesforce outage does not delay publication; retries and dead-letters are per destination.
- Negative: two outbox rows per accepted event increase write amplification and storage (see
  §Cost basis and `docs/design/SOLUTION_DESIGN.md` §6); the publisher is another worker to operate; consumers
  must be idempotent because publication is at-least-once.
- The Salesforce relay no longer consumes from Service Bus. Replaying a broker message into
  Salesforce is an outbox replay, not a queue-forward.

### Cost basis

Prices read from the Azure Retail Prices API (`eastus`, consumption meters) on 2026-11-21:

| Item | Unit price | Monthly |
|---|---|---|
| PostgreSQL Flexible Server B1MS | $0.017 / hour | ≈ $12.41 (730 h) |
| Storage | $0.115 / GB-month | ≈ $7.36 (64 GB) |
| Service Bus Premium, 1 MU | Planning allowance ≈ $1/hour (not a verified quote) | ≈ $730 (730 h) |
| Azure SQL serverless, 0.5 vCore — rejected | $0.521758 / vCore-hour | ≈ $190.44 (730 h) |
| Azure SQL Standard S0 — rejected | $0.4839 / day | ≈ $14.52 (30 days) |
| Azure SQL Standard S1 — rejected | $0.9677 / day | ≈ $29.03 (30 days) |

Storage uses the §6 design model: ≈850,300 unique events/month, ≈7.65 million logical row writes
and ~50 GB over 13 months including modeled WAL/bloat headroom, before audit/replay and backups.
Start at 64 GB; measure actual sizes and growth. Costs and exclusions are in Part C.

## Consequences

- Positive: the local tests need no cloud credentials; the relational store provides the transaction
  and uniqueness needed for FR4 and the state and indexes needed for FR5/FR7. B1MS is inexpensive
  if testing confirms its burst capacity. The broker's private networking dominates cloud cost.
- Negative: B-series credits are finite, so a month-end burst longer than the credit balance degrades
  to baseline exactly when freshness matters; the modeled burst is ≈349 logical row
  operations/s, and this ADR does not prove that B1MS sustains it; a single B1MS instance is a single
  point of failure unless zone redundancy or a replica is added, which this design does not deliver;
  PostgreSQL is an engine the Salesforce-side team does not operate; Mule is the corporate default and
  this ADR diverges from it, so the adapter boundary is a constraint to enforce in review, not a
  nicety; choosing Java means Part B must demonstrate in tests the behaviour a Mule connector would
  have given for free.
- Risks and how they are mitigated: CPU credit balance is an SLI with an alert before the last days
  of the month, because that is when the burst is longest; the 40/s burst is a load-test gate in Part
  C, and if B1MS cannot sustain it the SKU is a parameter, not a commitment; storage is alerted at 75%
  and 85% because no purge runs and the future archive is not implemented; the relay's Salesforce
  concurrency is bounded to 4 of 25 (ADR-0001); the drain rate remains a validation gate. If the
  organisation imposes Mule, the provider adapters are the only code that has
  to be re-hosted; if the store is later moved to SQL Server, the schema, keys and indexes carry over,
  but the claim uses `FOR UPDATE SKIP LOCKED` and the single-active lease, which need SQL Server
  equivalents (`READPAST`/`UPDLOCK` and an application lock) rather than a copy-paste.
