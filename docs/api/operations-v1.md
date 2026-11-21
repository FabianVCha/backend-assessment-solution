# Operations API v1

Query accepted notifications, inspect delivery outcomes, or ask the existing replay service to
requeue one destination. This is a local profile on the intake server; it does not change any
webhook path or contract.

## Enable the local profile

Run the intake with PostgreSQL (`INTAKE_JDBC_URL`, `INTAKE_DB_USER`, `INTAKE_DB_PASSWORD`) and
set all three of these environment variables. With none set, the operations router is not
registered. Partial or invalid configuration fails startup rather than exposing an unauthenticated
API.

| Variable | Value |
|---|---|
| `INTAKE_OPS_ISSUER` | Expected `iss`, e.g. `meridian-local` |
| `INTAKE_OPS_AUDIENCE` | Expected `aud`, e.g. `intake-ops` |
| `INTAKE_OPS_HS256_KEY_BASE64` | Standard base64 of a random key with at least 32 bytes; keep it out of Git and logs |

A local signer produces an HS256 bearer token with `iss`, `aud` (string or array), `exp` (Unix seconds,
use a short lifetime), nonblank `sub`, and string arrays `roles` and `countries`. Example claims:
`{"iss":"meridian-local","aud":"intake-ops","sub":"operator-demo","roles":["read"],"countries":["CO"]}`
plus a future `exp`. Export the resulting token as `OPS_TOKEN` without printing it:

```sh
curl -fsS -H "Authorization: Bearer $OPS_TOKEN" \
  'http://127.0.0.1:8080/ops/v1/notifications?country=CO&limit=10'
```

The `read` and `replay` roles are independent. Countries are `CO`, `MX`, and `CL`. The token is the
only source of roles, country scope and requester. A known but out-of-scope `country` filter
returns `403` even if no records exist there; an out-of-scope detail or replay target returns the
same `404` as a missing target.

## Endpoints

| Method and path | Role | Response |
|---|---|---|
| `GET /ops/v1/notifications` | `read` | `items` of notification summaries, and `next_cursor` (string or `null`) |
| `GET /ops/v1/notifications/{provider}/{eventId}` | `read` | `event`, `jobs`, `payment_projection`, and `timeline` |
| `GET /ops/v1/overview` | `read` | Aggregates for the caller's countries, optionally narrowed by `country` |
| `POST /ops/v1/replays` | `replay` | `{"outcome":"REQUEUED"}` once the reset and audit have committed |

**Search.** `provider` (`ANDINA`, `QUETZAL`, `AUSTRAL`), `country`, `event_id`, and `payment_key`
are exact optional filters. `limit` defaults to 25 and accepts 1–100. For the next page, send
the returned opaque `cursor` with the same filters. Pages are ordered by receipt time descending
with a stable tie-breaker; they are not a frozen snapshot. Each page reapplies authorization.

**Detail.** Each summary contains provider, country, event ID, payment key, status and timestamps.
Detail adds both destination jobs with state, attempts, processing time, next schedule where pending,
and nullable `outcome_code`. Timeline types are `RECEIVED`, `CANONICAL_PUBLISHED`, `SALESFORCE_APPLIED`,
`STALE_SKIP`, `PENDING_RETRY`, `DEAD` and `HELD`. `event_time` is the provider's occurrence time;
`processing_time` is when the service received the event or recorded the job transition. For older
rows without a stored transition time it can be `null`, never inferred from a retry deadline.
This is a snapshot of current milestones, not an append-only attempt history. Replaying a job
can replace its visible milestone. Responses omit the raw payload, payer fields and remote errors.

**Overview.** `accepted` is the number of unique events. `duplicates` counts duplicate deliveries
recorded since V7; earlier duplicate deliveries were never stored. `unresolved_work` counts all
non-published jobs including `dead` and `held`, which are also separate counts.
`oldest_pending_age_seconds` is based on original receipt among pending or claimed jobs, or zero
if none. `last_update_at` is the latest visible receipt, duplicate or job transition, or `null` if
empty. An empty result is `200` with zero counts; a failed query is `503`, not a zero report.

**Replay.** Send `Content-Type: application/json` and exactly these fields:

```json
{
  "provider": "ANDINA",
  "event_id": "<stored event id>",
  "destination": "SALESFORCE",
  "reason": "INC-0412: policy reference corrected",
  "idempotency_key": "<unique operator decision key>"
}
```

`destination` is `SALESFORCE` or `CANONICAL_EVENT`. Use nonblank fields; event ID, reason and key
are limited to 512, 1000 and 128 characters respectively. Do not put payer data in the reason.
HTTP keys are scoped to the target's stored country and separate from legacy CLI keys. Reusing the
key for the same target and destination returns the original outcome without repeating the reset;
changing the target or destination within the same scope returns `409 KEY_TARGET_CONFLICT`.
A claimed job returns `409 JOB_CLAIMED`. Missing or out-of-scope targets do not leave an audit row.
`REQUEUED` does not mean the destination applied the event. Reconcile `HELD` jobs before replay.

All errors use `{"error":{"code":"…"}}`. Invalid input is `400 INVALID_REQUEST`; missing or invalid
identity is `401 UNAUTHENTICATED`; insufficient scope or role is `403 FORBIDDEN`. Unsupported replay
content type is `415`; replay JSON larger than 8 KiB is `413`.

## Evidence and limits

- **Implemented locally:** JDK router, local token validation, PostgreSQL queries and audit, and
  replay through the existing transaction. `OperationsApiIT` exercises the HTTP contract against
  PostgreSQL. Run `mvn test` from `practical/` with the database test variables set.
- **Simulated:** broker and Salesforce delivery. No real provider, broker, or Salesforce calls.
- **Designed only:** production OIDC integration through `OpsIdentity`. Local HS256 is not an
  OIDC implementation. Keep it on a trusted local boundary.
- **Unvalidated:** production identity, TLS, scale and load performance, historical duplicate
  totals, and a full attempt history.
