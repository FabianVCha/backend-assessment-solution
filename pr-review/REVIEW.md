# Review — PR-214

## Verdict

- [ ] Approve
- [ ] Approve with comments
- [x] Request changes

## Summary for the author

@dvaldes, keeping Salesforce out of the webhook request is the right direction, and you’ve separated the controller, signature verifier, and payment service. Before we merge, let’s make the response mean the event is authenticated and durable, remove the credential and payer logging, and make Salesforce writes safe under retries and reordered events. Please keep the nightly import in place until the replacement has passed recovery and coverage-state checks.

## Findings

| # | Severity | File:line | Finding | Why it matters in this scenario | Suggested fix |
|---|---|---|---|---|---|
| 1 | Blocker | `src/main/java/com/meridian/payments/cl/AustralWebhookController.java:41–46` | Missing signatures pass the guard. | Unauthenticated input can reach payment processing and change coverage (FR1). | Reject absent, blank, malformed, duplicate and invalid signature headers before parsing, logging or queueing anything. |
| 2 | Blocker | `src/main/resources/application.yml:19` | A live-looking signing credential is checked into configuration. | Anyone with the usable key can authenticate fraudulent Austral notifications (NFR6). | Remove it before merge. If real or validity is unknown, treat it as exposed, coordinate rotation and check repository and build artifacts. Inject the replacement via Key Vault with least-privilege managed identity; never paste the value in review or logs. |
| 3 | Blocker | `src/main/java/com/meridian/payments/cl/AustralWebhookController.java:52–55`; `src/main/java/com/meridian/payments/cl/AustralPaymentService.java:57–59` | `200` follows an in-memory enqueue, and processing exceptions are swallowed. | A process restart or Salesforce outage can lose an acknowledged capture while Austral stops retrying. A paid customer's policy can remain suspended (NFR3). | Respond only after an atomic durable inbox/outbox commit or confirmed durable enqueue. Fail before acknowledgment if that boundary is unavailable. Give workers bounded backoff, quarantine and audit; the executor is not a recovery mechanism. |
| 4 | Blocker | `src/main/java/com/meridian/payments/cl/AustralWebhookController.java:50`; `src/main/java/com/meridian/payments/cl/AustralNotification.java:15–21` | The DTO's default string representation includes its payer record. | Name, RUT and email reach INFO logs (NFR6). | Log bounded outcome codes and safe correlation data only. Drop payer fields from downstream models and sanitize exception reporting, including errors from Salesforce. Assert no payer fields or headers appear in captured logs. |
| 5 | Blocker | `src/main/java/com/meridian/payments/cl/AustralPaymentService.java:35–57` | Check-then-act deduplication and find-then-create are racy; a DB transaction cannot include the remote write. | Duplicate deliveries can both send before the processed marker commits; a remote success followed by local failure can also be repeated (FR4). | Atomically claim acceptance with a unique `(provider, eventId)` key and use Salesforce upsert by a stable `Payment_Key__c` derived from provider/country/payment ID. Reconcile ambiguous outcomes before sending again. |
| 6 | Blocker | `src/main/java/com/meridian/payments/cl/AustralPaymentService.java:44–54` | Existing payments get an unconditional status update, with no state-change time or last-event guard. | Austral does not guarantee ordering; a late capture can overwrite a refund and reinstate a policy incorrectly (FR5). | Apply business transitions using `payment.occurredAt`, store last applied time/event, and serialize or fence writes per payment. Quarantine conflicting ties. Exercise reversal-before-capture, concurrent delivery and delayed responses. |
| 7 | Major | `src/main/java/com/meridian/payments/cl/AustralSignatureVerifier.java:21–26`; `src/main/java/com/meridian/payments/cl/AustralWebhookController.java:40` | The verifier reconstructs bytes from a decoded String and compares hex using ordinary equality. | Austral signs the exact wire bytes; reconstruction can reject valid notifications and ordinary equality is not a constant-time comparison. | Read bounded raw bytes, validate lowercase hex of exactly 64 characters, compare decoded MAC bytes in constant time, then deserialize. Check both official vectors and a byte change. |
| 8 | Major | `src/main/java/com/meridian/payments/cl/AustralNotification.java:10–13`; `src/main/java/com/meridian/payments/cl/AustralPaymentService.java:47–50,63–69` | A `double` and rounding accept values outside Austral's integer-string CLP contract; unknown event types become `Pending`. | Invalid amounts or unknown states become payment records rather than explicit errors; rounding is not contract validation. | Parse a bounded integer from the amount string without floats or scaling, check CLP and required fields, and reject or quarantine unsupported types. Use explicit contract cases for the documented states. |
| 9 | Major | `src/main/java/com/meridian/payments/cl/AustralPaymentService.java:43–55`; `src/main/java/com/meridian/payments/config/AsyncConfig.java:13–14` | The path makes a lookup plus a write per event and uses an executor with an unbounded queue and no shared API admission. | A slowdown backs up process memory; the Chile lane can consume quota shared with Colombia, Mexico and other org users (NFR5). | Use bounded durable workers, upsert without the lookup, and coordinate rolling-call and in-flight limits across replicas. Honor retryable versus terminal outcomes and verify overload behavior. |
| 10 | Major | `src/main/java/com/meridian/payments/cl/AustralWebhookController.java:49–55`; `src/main/java/com/meridian/payments/cl/AustralPaymentService.java:43–57` | No canonical publication or recoverable per-destination status is shown in this diff. | Salesforce success alone does not serve Finance or Notifications, and on-call cannot reliably reconcile an acknowledged event (FR2, FR6, FR7). | Confirm whether main already owns these capabilities; otherwise commit a canonical event and destination obligations at intake and expose scoped processing outcomes. |
| 11 | Major | `src/test/java/com/meridian/payments/cl/AustralPaymentServiceTest.java:32–49` | The tests assert that the service exists, allow one create for a duplicate, and expect the exception to disappear. | Green CI does not verify stored values, absence of duplicate effects, or durable recovery after failure. | Verify exact money, fields and writes, no Salesforce calls on dedup, and an observable retry/dead outcome. Add HTTP signature tests and database-backed restart, duplicate-race and out-of-order tests; prove the key tests fail against the broken behavior. |

Severity scale:

| Severity | Meaning |
|---|---|
| Blocker | Must be fixed before merge: data loss, security exposure, wrong business outcome for customers, or compliance breach |
| Major | Should be fixed before merge: reliability, correctness in edge cases, or maintainability risk with real impact |
| Minor | Can be fixed in this PR or a follow-up |
| Nit | Style or naming; optional |

## What looks good

- Stable Austral `eventId` is the right provider input for event deduplication. `payment.occurredAt` is already available for ordering, and Salesforce is behind an interface that can be fault-tested.
- This is a static review of the supplied prototype diff against the [Austral spec](../reference/providers/austral-cl/WEBHOOK_SPEC.md) and [Salesforce model](../reference/salesforce/DATA_MODEL.md), not a rerun of the prototype's reported CI. Proposed tests are merge gates, not evidence already collected.
