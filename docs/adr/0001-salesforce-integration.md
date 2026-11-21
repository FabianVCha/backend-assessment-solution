# ADR-0001: Integration with Salesforce

- **Status:** Accepted
- **Date:** 2026-11-19
- **Approved by:** the candidate, 2026-11-21
- **Scope note (2026-11-21):** the canonical event is published from a second outbox destination
  (ADR-0002 amendment). This does not change the Salesforce decision recorded here; the relay still
  drains the `SALESFORCE` destination and writes batches exactly as decided below.

## Context

`Premium_Payment__c` must be created or updated for every payment notification, with a status that
follows the provider lifecycle (FR3), exactly once in business terms (FR4), never contradicting the
provider's latest real state (FR5). Salesforce is the system of record for policies and payments, and
its behaviour is fixed by a team we do not own:

| Constraint | Value |
|---|---|
| Monthly availability | ~97%, outages in unplanned windows of 5 to 90 minutes |
| Latency, single-record upsert | p50 300 ms, p95 1.2 s, up to 8 s under load |
| Latency, 200-record collection upsert | p50 1.5 s, p95 4 s |
| Concurrent requests from the integration user | 25 maximum |
| API budget for this integration | 150,000 calls per rolling 24 h |
| Org-wide API limit | Shared with every other integration; exceeding it returns `403 REQUEST_LIMIT_EXCEEDED` to **all** of them |
| Authentication | OAuth 2.0 client credentials, token valid ~2 h |

Provider notification rates (NFR1): 15,000 per day normally, 120,000 per day at the month-end peak,
with bursts of 40/s sustained for 15 minutes. Freshness (NFR2): 95% visible in Salesforce within 5
minutes, 99.5% within 1 hour. Durability (NFR3): nothing that was acknowledged with a `2xx` may be
lost.

The webhook path has its own hard deadline: Andina expects an answer within **5 seconds**, Quetzal
and Austral within **10**. Under load a single-record upsert can take 8 seconds by itself, so a
synchronous write to Salesforce inside the webhook path cannot meet the provider contract.

## Options considered

### Option 1 — Synchronous, one record per call, inside the webhook path

The intake service calls Salesforce before answering the provider.

- Pros: the simplest flow; Salesforce state is current the moment the provider gets `2xx`; no queue,
  no store, no relay.
- Cons: it cannot meet the provider timeouts (p95 1.2 s is fine, but 8 s under load is not, and
  Andina allows 5 s). Every provider retry becomes a Salesforce call, so a slow org multiplies load
  exactly when it is already degraded. At the month-end peak it is 120,000 calls/day, which is 80% of
  the integration budget before any retry, leaving no room for the org-wide shared limit. When
  Salesforce is down, the provider retries for its own window (3 days for Quetzal, 5 attempts in 24 h
  for Andina, 8 in 48 h for Austral) and anything beyond that window is lost forever, which violates
  NFR3. Concurrency is capped at 25 requests, so the intake is coupled to a throughput ceiling it
  does not control.
- Verdict: rejected. It trades durability for simplicity in a scenario where durability is the point.

### Option 2 — Asynchronous, one record per call

Intake stores the event and answers; a relay drains a queue and calls Salesforce once per record.

- Pros: decoupled from the provider timeout; durable once the event is stored; retries are owned by
  the integration rather than by the provider.
- Cons: 120,000 records/day is still 120,000 calls/day, 80% of the budget, and each retry spends
  another call. A Salesforce outage turns into a retry storm against a system that is already
  unhealthy. There is also no room left for the org-wide limit to be shared fairly.
- Verdict: rejected on budget, not on architecture. The decoupling is right; the call shape is wrong.

### Option 3 — Asynchronous, batched, with a transactional outbox (chosen)

Intake persists the canonical event and the pending Salesforce write in the same database
transaction, answers the provider, and a relay drains the outbox using the collection upsert
(`PATCH /composite/sobjects/Premium_Payment__c/Payment_Key__c`, up to **200 records per call**, one
API call regardless of record count), flushing on `min(200 records, 60 seconds)`.

- Pros: 600 to 1,440 calls/day against a 150,000 budget (≤1%); `allOrNone: false` gives one result
  per record, so a single bad payment does not discard the other 199; availability is absorbed in the
  outbox, and a full backlog drains in minutes once Salesforce returns; the 60-second flush keeps the
  5-minute freshness target reachable at the overnight rate of ~0.17 notifications/s.
- Cons: up to 60 seconds of added latency before the write is attempted; a batch mixes countries,
  currencies and even several states of the same payment, so ordering must be resolved **before**
  building the batch, not by Salesforce; per-record results must be bookkept to know what succeeded;
  batching hides freshness regressions unless the flush window is measured as an SLI; the design
  grows one table and one worker.

## Decision

**Approved by the candidate on 2026-11-21.** The seven contract definitions below were reviewed and
accepted point by point on 2026-11-19; the architectural choice is accepted with this ADR.

Adopt **Option 3**: a transactional outbox drained by a batched relay using the collection upsert,
with `allOrNone: false`, flushing on `min(200 records, 60 seconds)`.

The write path is driven by the outbox, never by the webhook. The provider is answered `2xx` only
after the event and its outbox row are committed, and `5xx` when they are not, so the provider's own
retry is the last line of durability rather than the first.

### Contract definitions with Salesforce

These are the definitions the review will look for, because they are what makes "exactly once in
business terms" true on the Salesforce side. The reference material does not state them: they are
derived from FR4 and from the object having two separate identity fields
(`Payment_Key__c` for the upsert, `Last_Event_Id__c` for the event).

1. **`Payment_Key__c` identifies the payment, not the event**, and it is immutable:
   `QZ-MX-<data.charge.id>`, `AN-CO-<transaccion.id>`, `AU-CL-<payment.paymentId>`. Changing the
   formula later would create a second record for an existing payment instead of updating it.
2. **One record per provider payment, not per customer attempt.** A customer retrying a payment gets
   a new provider identifier and therefore a new record; a failed attempt stays `Failed`.
3. **Partial refunds are not written.** Quetzal can send `charge.refunded` with `amount_refunded`
   lower than the charge amount, and `Premium_Payment__c` has no field to represent it. Writing
   `Refunded` would make the Salesforce flow recalculate `Overdue_Amount__c` as if the whole payment
   had been reversed and could suspend a customer who paid most of it. A partial refund is published in
   the canonical event as `REFUNDED` with `refund_scope = PARTIAL` and its `refunded_amount_minor` when
   the provider reports one (`docs/design/SOLUTION_DESIGN.md` §3.1–§3.3); its Salesforce job is parked
   as `DEAD_LETTERED` with reason `PARTIAL_REFUND_REQUIRES_REVIEW`, goes to a manual review queue with
   an owner, and stays untouched in Salesforce until a `Refunded_Amount__c` field exists. `UNKNOWN`
   is not a synonym for `PARTIAL`: Andina and Austral report a payment-level reversal without a
   refunded amount or extent, so their event carries `refund_scope = UNKNOWN` while the status
   mapping still records the payment as `Refunded`; only a provider-reported partial triggers the
   hold above.
4. **Unknown policy is a permanent data defect.** `400 INVALID_FIELD` ("Foreign key external ID not
   found") is not retried; it goes to the dead-letter queue with reason `POLICY_NOT_FOUND` and is
   replayed once the policy exists.
5. **Andina's reference is validated, not trusted.** The provider documents `transaccion.referencia`
   as "the reference entered at checkout, which Meridian pre-fills", so it can be edited. It must
   match `POL-CO-\d{9}` before use; otherwise `POLICY_REFERENCE_INVALID` to the dead-letter queue.
   Quetzal sends a deterministic merchant reference and Austral an explicit policy number, so this
   defence is Andina-specific.
6. **`Provider__c` is a closed picklist of three values.** A fourth provider needs a Salesforce
   configuration change, owned by the Salesforce team, tracked as an onboarding step. The flow is
   provider-agnostic in code, not in the Salesforce schema, and the design says so instead of
   claiming otherwise.
7. **`Status_Changed_At__c` and `Last_Event_Id__c` are written by the integration**, from the
   provider's state-change time and event identifier. They make an audit answerable without opening a
   log: they show which event produced the current status and when the provider said it happened.

### Retry and failure taxonomy

One retry policy for every error would either bury data defects or burn the shared budget, so the
relay classifies first:

| Failure class | Handling | Attempts consumed |
|---|---|---|
| `401 INVALID_SESSION_ID` | Refresh the cached token once, retry immediately | No |
| `400 UNABLE_TO_LOCK_ROW` | Retry with exponential backoff (1, 2, 4 … capped at 300 s) | Yes |
| `5xx`, timeout | Same backoff curve, up to 12 attempts (~1 h), then dead-letter | Yes |
| `400 INVALID_FIELD` / `REQUIRED_FIELD_MISSING` | Dead-letter immediately, no retry | No |
| `403 REQUEST_LIMIT_EXCEEDED` | Open the circuit breaker, release the batch untouched, stop draining | No |

The circuit breaker reopens after a 15-minute cooldown with a single probe request, and a usage
guard pauses draining at 90% of the per-integration budget and alerts at 70%. Pausing is ugly;
triggering the org-wide `403` is worse, because it takes down integrations that are not ours.

### Capacity and recovery

| Metric | Value |
|---|---|
| Peak notifications per day | 120,000 |
| Records per collection call | up to 200 |
| Calls per day, theoretical minimum | 600 |
| Calls per day, worst case (one flush per minute) | 1,440 |
| Budget utilisation | ≤1% of 150,000 |
| Concurrent requests used | 4 of 25 |
| Backlog after a full 22-hour outage | ~110,000 records |
| Drain time at 4 concurrent calls, p50 1.5 s | ~3.5 minutes |

The 97% availability budget (~21.9 hours per month) fits inside the 5% lateness allowance of NFR2
(~36.5 hours), so an outage breaches the 5-minute target without breaching the monthly objective, as
long as the backlog drains and the lateness is attributed to the Salesforce SLO instead of hidden.

## Consequences

- Positive: the provider contract is met independently of Salesforce health; the API budget is used
  at ~1% and retries cannot turn a Salesforce incident into a self-inflicted outage; a bad record
  fails alone; a full outage recovers in minutes; every failure ends in a named destination instead
  of a log line.
- Negative: up to 60 seconds of added latency before the write is attempted, which is visible to the
  contact center; the relay is new code and new state to operate, with its own failure modes (stuck
  claims, relay down while the outbox grows); `allOrNone: false` requires per-record bookkeeping or
  successes are silently lost; partial refunds remain a manual process until Salesforce adds a field;
  a fourth provider is blocked on a configuration change outside the team.
- Risks and how they are mitigated: a relay that dies while the outbox grows is detected by backlog
  age, not by error rate (alert on the age of the oldest unsent row); ordering must be applied when
  building the batch, because two states of the same payment inside one call would let Salesforce
  apply them in array order, so the relay groups by `payment_key` and sends the winning status last;
  the 60-second window is an SLI, not an implementation detail, because a regression there breaks
  NFR2 silently.
