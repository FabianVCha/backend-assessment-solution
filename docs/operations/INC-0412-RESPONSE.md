# INC-0412: Recover Colombian payments without regressing policy coverage

Declare SEV-2, protect the shared Salesforce API budget, and stop automatic redelivery of permanent Colombia lookup failures before attempting recovery. Do not replay the DLQ wholesale or change policy status directly.

## First 30 minutes (Colombia time)

| Time | Action and reason |
|---|---|
| **08:12–08:17** | Assign an incident lead, Payments operator, and Salesforce and Colombia Operations contacts. Confirm **37** CO cases. Capture all countries' gateway, signature, queue/DLQ and upsert/error signals. CO main depth **0** with **4,312** dead letters means failures moved, not recovered. |
| **08:17–08:24** | Peek at masked DLQ samples without settling them. Trace a failed capture and reversal plus a successful CO control; compare error codes, attempts and reference shapes. Check MX/CL and `5xx` as controls. Confirm rolling usage (**66%**, about 99,000 calls) and shared org headroom with Salesforce. |
| **08:24–08:32** | Compare the Andina changelog, deployed mapping and policy lookup. Stop retrying the permanent error or quarantine it. If v1 cannot isolate it, pause only CO Salesforce delivery; keep durable intake and MX/CL running and monitor CO queue capacity, retention and age. |
| **08:32–08:42** | Publish scope, containment and the next update time. Give the contact center a reconciliation path, not a reinstatement promise. Agree on a reference resolver and canary with Salesforce and Colombia Operations. |

## Likely cause and how to confirm

Andina 2.3 (Saturday **23:00**) stopped normalizing checkout references. v1 likely passed short or lowercase references unchanged to `Policy_Number__c`, which expects uppercase `POL-CO-` plus nine digits. Samples show `400 INVALID_FIELD` for both shapes. The first failure at **23:41** and continued successful CO upserts fit this hypothesis. The missing-foreign-key error does not implicate the Salesforce cancellation rule or v1 logging deploy; zero signature failures and seven CO `5xx` argue against authentication or availability as the primary cause.

Compare pre/post-release reference shapes and deployed mapping. Check failed and successful lookups read-only; reproduce with known policies and integration permissions in non-production. The **43,120** CO `400`s equal **4,312 × 10**, consistent with v1 immediately abandoning permanent failures up to ten deliveries. Verify per-message attempts before attributing all errors to that loop.

## Repair and safely reprocess v1

1. **Repair resolution.** Normalize only agreed formats; preserve zeros and use verified checkout-to-policy mapping for short references. Require one same-country match; shape alone is not proof. Quarantine unresolved lookups, back off transient or row-lock errors, and stop on quota exhaustion.
2. **Reconcile before writing.** Snapshot a restricted `payments-co/$deadletterqueue` manifest by message/correlation ID. Deduplicate on Andina transaction + state, not delivery ID. Compare provider truth and Salesforce payment key, policy linkage and latest state, including events outside the DLQ. Order by `transaccion.fecha_transaccion` and serialize or fence against live writes. For `TRX-CO-5603318`, approval must not overwrite a later confirmed reversal. Hold conflicts or ambiguous links.
3. **Canary, then drain.** Require second-operator approval. Recover only Salesforce using the original payment key and verified latest fact. Start with a capture/reversal canary; verify upsert, linkage and Salesforce automation with its owners. Throttle under the rolling budget and 25-call concurrency limit, reserve headroom for fresh traffic, and stop on mismatch, quota or recurring errors.
4. **Settle after verification.** Complete original items only after confirmed application, verified supersession/no-op, or durable quarantine handoff. Read back ambiguous writes before retrying. Audit operator, approver, reason, key and outcome; reconcile remaining items and report verified results to Colombia Operations. Only Salesforce automation changes policy status.

## Prevention and what the local test proves

Alert on new dead letters, terminal-error rate, oldest work and country-specific freshness for **all** countries, not only the Mexico pilot. Track provider changes; include 2.3 short, lowercase, ambiguous and unknown-reference fixtures and a capture-then-reversal recovery test. Verify the checkout mapping and use staged rollout, fault classification, and a recovery budget gate.

The [local regression](../../practical/src/test/java/com/meridian/payments/intake/ops/Incident0412IT.java) uses PostgreSQL, well-shaped references and scripted `SimulatedSalesforce` responses, not the v1 Service Bus DLQ or Andina parser. It verifies terminal `DEAD/INVALID_FIELD`, unrelated `APPLIED` progress, a newer corrected refund, and audited replay of an older capture that yields `STALE_SKIPPED` without a new write. It does not verify live normalization or reinstatement. The [current API](../api/operations-v1.md) requeues only an eligible job for one destination without altering the original event. It blocks `HELD` pending reconciliation. Do not force replay or resubmit an old webhook.
