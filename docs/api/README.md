# API contracts — Premium Payment Notifications

Contracts only. No code ships from this directory.

| File | What it is |
|---|---|
| `webhooks.openapi.yaml` | OpenAPI 3.1 intake contract: Quetzal Pagos (MX), Andina Pay (CO) and Austral Pagos (CL) |
| `events/premium-payment.schema.json` | JSON Schema 2020-12 of the canonical event |
| [`operations-v1.md`](operations-v1.md) | Local operator API: scoped reads, overview, and audited replay |

`webhooks.openapi.yaml` is at **1.1.0**. The canonical event stays at `schema_version 1.0`: the schema
already declared `CL`, `CLP` and `AUSTRAL` because the design covered three countries, so Chile needed
no event change at all. 1.1.0 adds one path and one request schema; the two existing paths are byte for
byte what 1.0.0 published.

## Versioning and backward compatibility

- `info.version` (OpenAPI) and `schema_version` (event) are separate: the first versions the HTTP
  surface, the second versions the payload. Neither implies the other.
- Additive only within a major: a new optional field, a new provider path, a new error code a client
  is not required to recognise. Consumers must ignore unknown error codes and unknown optional
  fields. Removing or renaming a field, changing a type, tightening a pattern or changing what a
  value means is a major bump, and the old contract stays published until both providers or both
  consumers have migrated.
- The event is closed (`additionalProperties: false`) because the intake is its only producer: an
  accidental field is a test failure, not silent drift. The provider request schemas are open
  (`additionalProperties: true`) for the opposite reason: an additive provider change must not
  become a permanent `400` on a real payment.
- `schema_version` is pinned by the document (`const: "1.0"`). A consumer switches on it before
  validating, so an unknown version is a routing decision, never a validation error.

## Provider contracts (as published, not as we would like them)

- **Signatures cover the raw body.** Quetzal: `Quetzal-Signature: t=<unix>,v1=<hex>`, HMAC-SHA256 of
  `<t>.<raw bytes>`, 300 s tolerance, two `v1` values during rotation, any match wins. Andina:
  `X-Andina-Timestamp` (ISO-8601 with offset) plus `X-Andina-Signature`, base64 HMAC-SHA256 of
  `<timestamp>.<raw bytes>`, 600 s tolerance. Austral: `X-Austral-Signature`, lowercase hex HMAC-SHA256
  of the body alone and **no timestamp at all**, so there is no skew window; the event id is the replay
  key and the inbox's unique `(provider, event_id)` is what answers a repeat. All three are verified
  before parsing, in constant time.
- **Never re-serialise before verifying.** Key order, whitespace and a trailing newline change the
  digest. Buffer once, verify that buffer, parse that buffer.
- **Success statuses differ.** Quetzal accepts any `2xx` within 10 s; Andina accepts only `200` or
  `204` within 5 s; Austral accepts any `2xx` within 10 s. No path returns `202`: a `2xx` here means the
  inbox row and both outbox jobs committed, not that work was queued for later judgement.
- **Currencies are not converted and exponents are not assumed.** Quetzal and Andina send major units
  and the adapter scales by the currency's exponent; Austral's CLP has no minor unit, so `45990` is
  carried as `amount_minor 45990` with `currency_exponent 0`. A shared "multiply by a hundred" would
  report that payment as 4,599,000 and the schema would accept it, because the schema cannot know which
  currency the provider reported.
- **Duplicates.** Quetzal retries the same `id`. Andina sends a **new `notificacion_id` on every
  attempt**, so its identity is `transaccion.id` + `estado` and nothing else: the provider states
  that this pair identifies the business event and does not change between attempts, while
  `fecha_transaccion` is never promised to be stable.
  Deduplication is the unique insert on `(provider, event_id)`; a duplicate is acknowledged with no
  outbox job and no republication.

## Event semantics

- **Published, not applied.** Publication is at-least-once and does not confirm a Salesforce write or
  a consumer read. Consumers deduplicate on `(provider, event_id)` and may see the same event twice.
- **Original and refund are separate events.** `amount_minor` is always the original payment amount;
  `refunded_amount_minor` is the provider-reported refund, `null` when none is reported.
- **Never summed, never zero-filled.** `null` is not `0`. Quetzal's figure is
  `PROVIDER_REPORTED_UNSPECIFIED` — the provider does not say whether it is cumulative — so it is
  carried as reported and never accumulated. Andina and Austral report a payment-level reversal with
  no amount, so `refund_scope` is `UNKNOWN` and `refunded_amount_minor` is `null`. No financial total
  or remaining balance can be derived from these events; that reconciliation lives in Finance.
- **A reported figure above the charge is `UNKNOWN`, never corrected.** If a provider reports a
  refunded amount greater than the payment, the comparison is not interpretable: `refund_scope` is
  `UNKNOWN`, the figure is carried as reported and nothing is derived from it. The schema accepts
  `UNKNOWN` together with an amount for exactly this case.
- **Ordering.** `status_changed_at` is the only field an ordering rule may use. `received_at` is our
  clock, filled at intake, for freshness only; a retry can arrive hours late with an old state time.
- **No payer data.** Names, documents and emails travel on the wire and are dropped at the adapter.
  The canonical event and its logs carry none. Payment, policy and provider identifiers stay
  sensitive: never in logs, metrics or traces, and never in a provider response body.

## Part B constraints

- External dependencies are stubs behind interfaces: the store (same unique constraint and the same
  transaction boundary), the broker (publish recorded, never called) and the secrets store
  (provider secrets read through a provider, never literals in code or configuration).
- This is an assessment deliverable, not production. No real provider, Salesforce org or broker is
  called; there is no authentication of the internal hop, no load test and no signed SLA behind the
  published numbers. Secrets in the repository are sandbox test vectors from `reference/`.
