# Operate premium payment notifications

Start with the affected country and destination. A healthy canonical topic does not mean Salesforce recorded the payment; an empty main queue does not mean the backlog is clear.

## Service objectives

Apply the same objectives independently to **MX / Quetzal**, **CO / Andina**, and **CL / Austral**. Report three country series, not just a combined average.

| SLI | Objective per country | Measurement |
|---|---|---|
| Salesforce freshness (NFR2) | ≥95% within 5 minutes and ≥99.5% within 1 hour, over rolling 30 days | Cohort distinct, valid payment state changes requiring a Salesforce update by the earliest authenticated provider notification time for that event. Count timely Salesforce visibility using the confirmed upsert time, with sampled read-back to validate that proxy. An unresolved or failed write is a miss when its deadline expires. Deduplicate retries; separately report superseded events that correctly needed no write. Flag missing or implausible timestamps and show first-edge-receipt-to-visibility separately; do not silently substitute it for end-to-end freshness. |
| Accepted notification loss (NFR3) | **Zero** lost among acknowledged notifications; no error budget | Reconcile every gateway 2xx against a committed event (or its existing event for a duplicate) and the expected durable destination obligations. Reconcile each outcome through completion, explicit supersession, or tracked unresolved work. Pending, dead-lettered, and held work is not lost if durable and recoverable, but can still breach freshness. Unmatched acknowledgements beyond telemetry lag are potential loss until explained. |

Keep outage and quota time in the freshness numbers. A Salesforce outage can exhaust the SLO despite durable buffering; track provider delivery lag, dependency latency, backlog age and drain rate separately to explain the miss.

## Trace and diagnose

At the gateway, generate an opaque receipt correlation ID and start the trace; do not trust incoming correlation headers as identifiers of record. Preserve the original accepted event's correlation ID across the intake record, outbox, broker properties, Salesforce relay and replay audit. Link subsequent delivery traces to that accepted event; use a separate attempt or replay ID, not a new business event ID. Keep the Salesforce request ID as a join point when available, and give downstream consumers the correlation ID in message metadata. For async traces, link spans across the enqueue/dequeue boundary instead of assuming a single synchronous request.

- **Logs:** Structured country, provider, opaque correlation/event IDs, destination, sanitized outcome code, attempt, and duration. Put transaction and payment-key lookup in the authorized operations read model, not in metric labels. Log neither bodies (raw or canonical), payer names, national IDs, emails, policy references, payment tokens, secrets, authorization/signature headers, nor raw Salesforce errors or exception bodies. Disable request-body/header capture at the gateway and in tracing. Restrict and expire logs and audits.
- **Traces and metrics:** Trace intake/authentication, durable commit, publish, and destination attempts. Retain failure traces and keep complete counters and reconciliation records independent of trace sampling. Measure requests/2xx/rejections, duplicates, commit failures, freshness buckets, queue/outbox age and depth, `DEAD`/`HELD`, retry rates, Salesforce response classes, rolling API consumption and in-flight calls. Use country, provider, destination and bounded outcome codes as dimensions, never record IDs.
- **Operator workflow:** Use the [Operations API](../api/operations-v1.md) or [local terminal console](../../ops-console/README.md) to find a notification by provider/event ID or payment key. Compare its canonical and Salesforce jobs, then check backlog age for that country. The read model lists accepted events, not rejected webhook attempts.

## Initial alert thresholds and ownership

These are proposed production thresholds. Apply them to all three countries and test routing before rollout.

| Trigger | Action |
|---|---|
| Any 2xx still unmatched after 5 minutes of telemetry grace, or reconciliation itself missing for 10 minutes | Page Payments Integration immediately; involve Data/Platform to verify durable evidence. |
| Over a 15-minute window with ≥20 due payments, >5% miss 5 minutes, or over a 1-hour window with ≥20 due, >0.5% miss 1 hour | Page Payments Integration. At low traffic, page on any 1-hour miss. Page sooner if oldest eligible pending Salesforce work exceeds 5 minutes for 5 minutes. |
| Any new `DEAD` or `HELD` result | Send a country-scoped triage alert; page if ≥5 new in 5 minutes or the oldest unresolved item is ≥15 minutes old. Include Salesforce on lookup/validation clusters or ambiguous remote writes. |
| Rolling Salesforce calls ≥70% of 150,000 | Warn Payments/Salesforce owners. At ≥85%, on `REQUEST_LIMIT_EXCEEDED`, or when all 25 slots stay busy for 5 minutes with growing backlog, page Payments and coordinate with Salesforce; reduce or pause recovery traffic. |

Payments Integration owns incident coordination and recovery; Salesforce owns policy data, payment-side automation and shared-usage confirmation; Platform owns gateway, broker, database and alert delivery. Country Operations validates customer impact without handling raw payloads.

**Evidence boundary:** The local read model provides scoped jobs, outcomes and queue age; the terminal console reads it using a sample identity. The full correlation, SLO reconciliation and paging above are production designs, not demonstrated Azure monitoring or Salesforce visibility measurements.
