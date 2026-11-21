# Practical part — Payment notification intake (MX, CO)

Part B intake for Quetzal Pagos (Mexico) and Andina Pay (Colombia). This is an assessment
deliverable: no real provider, Salesforce org or broker is called.

## Stack and versions

- Java 21 (Temurin 21.0.12.1), Maven 3.9.16.
- JUnit Jupiter 5.11.4 and Jackson databind 2.18.12, both from Maven Central. Jackson is on 2.18.12
  because the image scan flagged 2.18.2 with five high-severity advisories, all fixed by 2.18.11.
- networknt json-schema-validator 2.0.8, **test scope only**: the conformance test validates the
  events this code produces against `docs/api/events/premium-payment.schema.json`. The schema is
  read from the repository, never copied, so a changed contract cannot keep passing unnoticed.
- Plain Java libraries, no framework. `IntakeServer` adapts the endpoint to the JDK's `HttpServer`, and
  the shade plugin builds one runnable jar so the container image needs a JRE and nothing else.

## Build and test

With a local JDK 21 and Maven:

```sh
mvn test
```

The reproducible path used for the evidence in this repository is a container, with the Maven
cache outside the repository:

```sh
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp \
  -v "$PWD/..:/workspace" \
  -v "$HOME/.hermes/cache/scratch/prueba-tecnica-b/m2:/m2" \
  -w /workspace/practical \
  --entrypoint /usr/share/maven/bin/mvn maven:3.9-eclipse-temurin-21 \
  -B -ntp -Dmaven.repo.local=/m2/repository test
```

The repository root is mounted, not just the module, because the tests read the published provider
samples from `reference/providers/` and the event schema from `docs/api/`.

## Project structure

```text
practical/
  pom.xml
  Dockerfile
  src/main/java/com/meridian/payments/intake/
    NotificationIntake.java          authenticity, then event, then durability
    IntakeApplication.java           the process: endpoint on a port, publisher on a loop
    IntakeOutcome.java               ACCEPTED or DUPLICATE, with the event
    WebhookRejectedException.java    not authentic, not read
    http/
      WebhookEndpoint.java           the two published paths and their status codes
      IntakeServer.java              the JDK HttpServer adapter
      HttpRequest.java               path, headers and raw body
      HttpResponse.java              status, headers and body
    auth/
      Provider.java                  provider enum
      WebhookRequest.java            provider + headers + raw body bytes
      SignatureVerifier.java         verification boundary
      SignatureVerification.java     result and contract failure codes
      QuetzalSignatureVerifier.java  Quetzal Pagos 2025-06-01 scheme
      AndinaSignatureVerifier.java   Andina Pay 2.3 scheme
      SecretProvider.java            secrets-store boundary
      InMemorySecretProvider.java    test/local stub
      Hmac.java                      HMAC-SHA256 and constant-time compare
    canonical/
      CanonicalEvent.java            the event record and its JSON form
      CanonicalEventMapper.java      mapping boundary
      CanonicalMappingException.java the codes the error contract declares
      Payloads.java                  field reading rules shared by both mappers
      QuetzalCanonicalEventMapper.java
      AndinaCanonicalEventMapper.java
    store/
      IntakeStore.java               persistence boundary
      OutboxStore.java               outbox drained per destination, with a retry schedule
      InMemoryIntakeStore.java       in-memory inbox and outbox, one transaction
      Acceptance.java                ACCEPTED or DUPLICATE
      OutboxJob.java                 one unit of work per destination
      PendingJob.java                a job with the event it carries
      Destination.java               SALESFORCE and CANONICAL_EVENT
      StoreUnavailableException.java nothing was written
    publish/
      PublisherLoop.java             the polling role: ticks the publisher without a human
      CanonicalEventPublisher.java   drains CANONICAL_EVENT into the topic
      Broker.java                    broker boundary
      RecordingBroker.java           stub that records, and can fail or lose an ack
      CanonicalMessage.java          message id, correlation id and body
      MessageIds.java                the derived, stable message id
      BrokerUnavailableException.java the ack never came
  src/test/java/com/meridian/payments/intake/
    TestSamples.java                 published samples and the schema path
    MutableClock.java                a clock the tests move by hand
    NotificationIntakeTest.java      the whole path with the real pieces
    auth/                            signature tests
    store/InMemoryIntakeStoreTest.java
    publish/CanonicalEventPublisherTest.java
    publish/PublisherLoopTest.java
    http/WebhookEndpointTest.java
    http/IntakeServerTest.java
    canonical/
      CanonicalEventContractTest.java       produced events against the published schema
      QuetzalCanonicalEventMapperTest.java
      AndinaCanonicalEventMapperTest.java
      CanonicalMappingFailureTest.java
      CanonicalEventCarriesNoPayerDataTest.java
```

## Implemented: webhook authentication

`com.meridian.payments.intake.auth` verifies authenticity before any parsing:

| Provider | Header | Signed string | Tolerance |
|---|---|---|---|
| Quetzal | `Quetzal-Signature: t=<unix>,v1=<lowercase hex>` | `<t>.<raw body>` | 300 s |
| Andina | `X-Andina-Timestamp` + `X-Andina-Signature` | `<timestamp header value>.<raw body>` | 600 s |

- Both use HMAC-SHA256 and a constant-time comparison (`MessageDigest.isEqual`).
- Quetzal accepts any matching `v1` during secret rotation.
- Andina uses standard padded Base64 and hashes the timestamp header exactly as sent, never a
  normalised UTC form.
- The body is held as raw bytes. Nothing parses or re-serialises the JSON before verification, so
  key order and whitespace cannot invalidate a valid request.
- `Clock` is injected; tests pin it to the published vector times.
- `SecretProvider` is the secret-store boundary. `InMemorySecretProvider` is a test/local stub.
  A missing secret fails closed with a configuration error instead of a 401.
- Authentication log lines carry only provider and failure code: no secret, signature, body,
  policy number or payer data.

## Implemented: canonical event (FR2)

`com.meridian.payments.intake.canonical` turns a verified notification into the event published for
downstream consumers. The mappers are pure: same bytes and same clock instant, same event. They never
read a datastore, a broker or the network, and they never read payer data.

| Canonical field | Quetzal (MX) | Andina (CO) |
|---|---|---|
| `event_id` | `id`, stable across retries | `sha256(transaccion.id` + `transaccion.estado)` |
| `payment_key` | `QZ-MX-<data.charge.id>` | `AN-CO-<transaccion.id>` |
| `amount_minor` | `amount`, already in cents | `valor` × 100, by string arithmetic |
| `currency` | `currency` uppercased (`mxn` → `MXN`) | `moneda` (`COP`) |
| `status` | `charge.succeeded` / `failed` / `refunded` | `PENDIENTE` / `APROBADA` / `RECHAZADA` / `REVERSADA` |
| `status_changed_at` | `paid_at`, `refunded_at`, else `created` | `fecha_transaccion`, offset → UTC |
| `occurred_at` | `created` | `fecha_notificacion`, offset → UTC |
| `refunded_amount_minor` | `amount_refunded`, else null | always null |
| `refund_scope` | FULL, PARTIAL or UNKNOWN by comparison | UNKNOWN on a reversal, else null |
| `correlation_id` | charge id | transaction id |
| `payload_hash` | SHA-256 of the request bytes | SHA-256 of the request bytes |

Decisions worth knowing, each one pinned by a test:

- **The Andina key is the pair, not the triple.** A retry carries a new `notificacion_id` and, in
  principle, could carry a recomputed `fecha_transaccion`; the provider only promises that
  `transaccion.id` + `transaccion.estado` is stable. Samples 02 and 03 are the same state delivered
  twice: they produce the same `event_id` with different `payload_hash` values.
- **Major units never become a double.** `valor` is read as two integer groups and concatenated, so
  `"185000.00"` is `18500000` and `"185000.000"` is rejected instead of rounded.
- **A missing `paid_at` or `refunded_at` falls back to `created`.** Both are nullable in the provider
  contract, and rejecting an authentic notification would lose a real payment over a timestamp that
  is seconds away. `charge.failed` uses `created` by design, because it has no `paid_at`.
- **A reported refund above the charge amount is carried, not corrected**, with `refund_scope`
  `UNKNOWN`: the comparison is not interpretable, and no figure is invented.
- **`null` is never `0` and never a full refund.** A refund event without a reported amount keeps
  the three refund fields null, and `refund_scope` `UNKNOWN`.
- **Payer data is dropped at the adapter.** The event is serialised in the test and scanned for
  emails, names, documents, payment tokens and the provider's per-attempt `notificacion_id`.

## Implemented: durability and deduplication (FR4, NFR3)

`com.meridian.payments.intake.NotificationIntake` runs the three steps in the order the contract
promises: verify the signature, build the event, then write it. Verification comes first so a hostile
body never reaches the JSON reader, and an authentic body is never rejected for a reason that belongs
to the parser.

`store.IntakeStore` states the contract the design relies on, and `InMemoryIntakeStore` reproduces it
without a database:

- `(provider, event_id)` is unique. A second delivery is `DUPLICATE`: a 2xx with no new event and no
  new outbox job. It is not an error.
- An accepted event gets exactly one `SALESFORCE` job and one `CANONICAL_EVENT` job.
- The event and both jobs commit together. A failure leaves the store exactly as it was, so a 2xx has
  not been earned and the provider's retry is what produces durability.

The in-memory transaction stages into copies and publishes them only after every write would have
succeeded. That is what lets the injected failures prove the atomicity claim: the tests make the
store fail before the transaction and in the middle of it, and assert that nothing survives either
time.

## Implemented: publication to the canonical topic (FR6)

`publish.CanonicalEventPublisher` publishes one batch of the `CANONICAL_EVENT` outbox:

- `MessageId` is `provider + ":" + event_id` when that fits in the 128-character broker limit, and
  `"sha256:" + hex(sha256(provider + "|" + event_id))` otherwise. Hashing rather than truncating,
  because two long identities could otherwise collide and a collision silently drops a payment.
- `CorrelationId` is the canonical `correlation_id`, identical on every retry.
- A job is marked published only after the broker acknowledged it. A lost acknowledgement leaves it
  pending, so the next poll publishes the same event again with the same `MessageId`: publication is
  at-least-once and consumers deduplicate on `(provider, event_id)`.
- The publisher touches that destination only. The `SALESFORCE` job stays for a separate relay, so a
  broker outage cannot delay Salesforce and a Salesforce outage cannot delay publication.

`publish.PublisherLoop` is the polling role the design names (`SOLUTION_DESIGN.md` §1.2, §5):

- a fixed-delay tick that calls the publisher, with its own daemon thread, so publishing does not
  depend on a human or on a request arriving;
- a tick never kills the loop: a broker failure becomes a pending job with a scheduled retry, and
  anything unexpected is logged and the next tick tries again;
- the wait between attempts belongs to the outbox row, as the design places `next_attempt_at` there:
  one second, doubling, capped at sixty. `pending()` returns only the rows that are due, so a broken
  broker cannot turn the loop into a hot spin;
- log lines carry a count only: no provider identifier, no policy number, no payload.

## Implemented: HTTP surface

`http.WebhookEndpoint` is the handler for the two paths the contract publishes, and it decides nothing
the contract has not decided:

| Situation | Answer |
|---|---|
| body over 64 KiB | `413` `PAYLOAD_TOO_LARGE`, before the signature is looked at |
| signature missing, malformed, wrong or stale | `401` with the signature failure code |
| authentic but unusable payload | `400` with `MALFORMED_JSON`, `SCHEMA_VALIDATION_FAILED` or `POLICY_REFERENCE_UNPARSEABLE` |
| store could not commit | `503` `STORE_UNAVAILABLE` with `Retry-After: 5` |
| first acceptance | `200` `{"status":"ACCEPTED"}` |
| duplicate | Quetzal: `200` `{"status":"DUPLICATE"}`; Andina: `204` with no body |
| any other path | `404` |

Two deliberate omissions: no `202` on any path, because the `2xx` is a durability fact and not an
intent; and no content-type gate, because the contract declares a JSON body and its error vocabulary
has no code for an unsupported media type, so inventing one would break the contract it implements.

## Running it

The image is buildable and was exercised. What a container answered, with a body signed the way the
provider signs it:

```sh
docker build -t premium-payment-intake:local practical
docker run --rm -p 8080:8080 \
  -e QUETZAL_WEBHOOK_SECRET=<sandbox vector> \
  -e ANDINA_WEBHOOK_SECRET=<sandbox vector> \
  premium-payment-intake:local
```

- first delivery: `200 {"status":"ACCEPTED"}`
- the same body again: `200 {"status":"DUPLICATE"}`
- a tampered signature: `401 {"error":{"code":"SIGNATURE_INVALID",...}}`

Three adapters are in-memory in this process: the store, the broker and the secret source (the
environment). Everything between them is the tested implementation.

## Tests and evidence

122 JUnit 5 tests.

Authentication (51):

- the seven official vectors (3 Quetzal, 4 Andina) verify byte for byte;
- altered body and parsed/re-serialised JSON are rejected;
- missing, blank, malformed and garbage headers are rejected with the contract failure codes;
- rotation with two `v1` values, any match wins;
- clock boundaries: exactly ±300 s / ±600 s accepted, one second beyond rejected;
- missing secret fails closed;
- a logging capture proves no secret, signature, body or payer value is logged;
- a source scan proves no sandbox secret material exists under `src/main`.

Canonical mapping (40):

- every one of the seven samples produces an event that the published JSON Schema accepts;
- both providers are asserted field by field, including the UTC conversion and the cents conversion;
- a retry maps to the same `event_id`, and a different state of the same transaction does not;
- refund branches: full, partial, above the charge amount, and a refund with no reported amount;
- failures: malformed JSON, non-object JSON, a missing event id, an unknown charge type, an unknown
  state, an unmappable policy reference, a currency that is not the provider's, three decimals, a
  zero amount, a missing `notificacion_id`, and a mapper handed the other provider's document;
- the serialised event carries no payer data and no provider notification id.

Store and intake (12):

- the first acceptance writes the event and one job per destination;
- a duplicate adds no event and no job, and the same id from another provider is a different event;
- a store that fails before or in the middle of the transaction leaves nothing behind, and the retry
  then succeeds;
- a failed attempt is withheld until its next attempt time, and the wait doubles;
- over the real pipeline: a signed Quetzal notification is accepted, the Andina retry is a duplicate
  while a new state is a new event, an invalid signature and an unmappable reference store nothing,
  authenticity is checked before parsing, and a store failure reaches the caller instead of becoming
  a false 2xx.

Publisher (9):

- a pending event is published with the derived `MessageId` and the canonical correlation id;
- a published job is not published again;
- a long identity becomes a stable hash instead of a truncated id;
- a broker failure withholds the job until its next attempt, and the retry publishes it;
- a lost acknowledgement publishes the same message again, with the same `MessageId`;
- the `SALESFORCE` destination is never touched by the publisher;
- the batch size is respected;
- the loop publishes with nobody calling the publisher, and a broker failure does not stop it.

HTTP surface (8):

- an accepted notification is a `200` with the receipt; a Quetzal duplicate is a `200` `DUPLICATE`;
  an Andina duplicate is a `204` with no body;
- an oversized body is a `413` before the signature is looked at;
- a bad signature is a `401` and a non-retryable error;
- an unmappable reference is a `400` with its own code and no echo of the request;
- a store failure is a `503` with `Retry-After`;
- an unpublished path is a `404`.

HTTP adapter (2):

- a signed provider sample over a real socket is answered `200` and leaves a durable event;
- a `GET` on the webhook path is `405`, not a webhook.

RED/GREEN evidence was captured from the container build for the first two lots:

- authentication: 51 tests, 50 failing before the implementation (assertion failures, no compilation
  error), then 51 passing;
- canonical mapping: 90 tests, 39 failing before the implementation (11 failures and 28 errors, all
  `NOT_IMPLEMENTED`, no compilation error), then 91 passing.

The store, publisher, HTTP and polling-loop lots were written together with their tests, so they have
no red-phase capture; their evidence is the full suite green plus the injected failures above.

## Stubbed

- Secrets: in-memory provider; the design reads Azure Key Vault.
- Store: in-memory implementation behind `IntakeStore` and `OutboxStore`; the design puts PostgreSQL
  behind them, with the same unique constraint, the same transaction boundary and `next_attempt_at`.
- Broker: recording implementation behind `Broker`; the design puts Azure Service Bus behind it.
- Salesforce: not called anywhere. The `SALESFORCE` job is recorded for a relay that is out of scope
  for this part, as the task states.

## Known gaps

- Secrets come from the environment in the runnable image. `SecretProvider` is the boundary the design
  fixes; the Key Vault adapter is the piece still missing, even though the vault and the role
  assignment exist in `infra/`.
- The process runs with the in-memory store and the recording broker, so two replicas would not share
  state. That is a property of the stubs, not of the interfaces.
- No dead-letter rule: a publication that keeps failing retries forever with a sixty-second cap. Part
  A's design puts the dead letter on the broker and on the relay; here the job simply stays pending.
- No claim or lease on the outbox row, which one process does not need and two would.
- No relay to Salesforce: the `SALESFORCE` job is recorded and never processed, so no batching, no
  backoff and no dead letter on that path.
- Content-type is not gated, on purpose: the contract declares a JSON body and its error enum has no
  code for an unsupported media type.
- `paid_at` and `refunded_at` fall back to `created` when absent. A stricter reading would reject
  those notifications instead; the trade-off is revisited when the store owns the atomicity.
