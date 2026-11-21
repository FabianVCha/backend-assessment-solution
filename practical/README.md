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
      CanonicalEvents.java           reads the stored document back, field by field
      CanonicalEventMapper.java      mapping boundary
      CanonicalMappingException.java the codes the error contract declares
      Payloads.java                  field reading rules shared by both mappers
      QuetzalCanonicalEventMapper.java
      AndinaCanonicalEventMapper.java
    store/
      IntakeStore.java               persistence boundary
      OutboxStore.java               outbox drained per destination, with a retry schedule
      PostgresIntakeStore.java       the durable inbox and outbox, one transaction and one constraint
      Database.java                  bounded pool and the migration run, before the first query
      RetrySchedule.java             the retry curve, stated once
      InMemoryIntakeStore.java       in-memory double of the same rules, for the demo
      Acceptance.java                ACCEPTED or DUPLICATE
      OutboxJob.java                 one unit of work per destination
      PendingJob.java                a job with the event it carries and the claim it was handed
      ClaimLostException.java        the lease expired and someone else owns the job
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
    salesforce/
      SalesforceClient.java          the port: what to write, and the nine answers that matter
      SalesforceRelay.java           one batch, classified: applied, terminal, held or retry
      SalesforceRelayLoop.java       the tick that drains the lane without a human
      SimulatedSalesforce.java       scripted destination, so the fault matrix is tested
    store/
      SalesforceStore.java           the lane's endings: applied, terminal, ambiguous
      PostgresSalesforceStore.java   claiming, ordering by the provider clock, three endings
  src/main/resources/db/migration/
    V1__intake.sql                   the inbox, the outbox and their indexes
    V2__claims.sql                   the claim columns, the CLAIMED state and the lease index
    V3__payment_state.sql            the projection keyed by payment, not by notification
    V4__salesforce_lane.sql          DEAD and HELD, the outcome code and the in-flight index
  src/test/java/com/meridian/payments/intake/
    TestSamples.java                 published samples and the schema path
    MutableClock.java                a clock the tests move by hand
    NotificationIntakeTest.java      the whole path with the real pieces
    auth/                            signature tests
    store/InMemoryIntakeStoreTest.java
    store/PostgresIntakeStoreIT.java  the same rules against a real database, skipped without one
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

`store.IntakeStore` states the contract the design relies on. Two implementations satisfy it: the
durable `PostgresIntakeStore` the process runs, and `InMemoryIntakeStore`, which reproduces the same
rules in memory for the demo:

- `(provider, event_id)` is unique. A second delivery is `DUPLICATE`: a 2xx with no new event and no
  new outbox job. It is not an error.
- An accepted event gets exactly one `SALESFORCE` job and one `CANONICAL_EVENT` job.
- The event and both jobs commit together. A failure leaves the store exactly as it was, so a 2xx has
  not been earned and the provider's retry is what produces durability.

The in-memory transaction stages into copies and publishes them only after every write would have
succeeded. That is what lets the injected failures prove the atomicity claim: the tests make the
store fail before the transaction and in the middle of it, and assert that nothing survives either
time.

### The durable store

`store.PostgresIntakeStore` puts the same contract in the database, where the decision stops being the
application's:

- the insert is `INSERT ... ON CONFLICT (provider, event_id) DO NOTHING`. A row count of zero means the
  other delivery committed first, so there is no window between a check and a write. Eight replicas
  sending the same event at the same instant produce one event and two jobs, and the integration test
  asserts exactly that;
- the event and both jobs are one transaction. A trigger that rejects the second job makes the whole
  accept fail, and the test then counts zero events and zero jobs;
- `store.Database` runs Flyway before the first query, so a migration that fails stops the process
  instead of leaving it running against a schema it does not know;
- the publisher reads the stored document back, and a round-trip test uses the provider samples rather
  than a hand-built event. That is not decoration: the first version of the reader treated
  `refunded_amount_semantics` as mandatory, and the real Quetzal sample — where it is null — stopped
  publishing in the running container while the hand-built fixture passed;
- the retry curve exists twice, in `RetrySchedule` and in the SQL that schedules the row. One
  integration test pins them together: one failure schedules a second, the next two, then four;
- the payment projection (`payment_state`) moves inside the same transaction. It keeps the newest status
  by the provider's own timestamp, so a capture that arrives late cannot undo a reversal, and its
  `remote_applied_status` is written by nobody yet: a local insert is not evidence that Salesforce
  applied anything.

### What a crash proves

The property is not "it starts again", it is "the acknowledged payment is still there and still owed".

Measured with the built image against PostgreSQL 16: a signed event was accepted (`200 ACCEPTED`), the
container was killed with `SIGKILL` one second later, and the database held one event, two jobs and one
projection row with zero published. A fresh container on the same database, with no other replica
running, published the pending canonical event on its first tick and logged it. The Salesforce job stayed
pending, because no relay owns it yet.

What that does not prove: a database or region failure. That needs the backup and restore drill, and it
is not claimed here.

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
- the batch is claimed before it is sent, so two replicas polling the same table split the work
  instead of both sending the same message. A lease that expires returns the job to the queue, and the
  worker that outlived it cannot complete someone else's work;
- the wait between attempts belongs to the outbox row, as the design places `next_attempt_at` there:
  one second, doubling, capped at sixty. `pending()` returns only the rows that are due, so a broken
  broker cannot turn the loop into a hot spin;
- log lines carry a count only: no provider identifier, no policy number, no payload.

### Claims and leases

Draining is not reading. `OutboxStore.claim` takes a bounded batch under a lease and hands back a token,
and every completion presents that token:

- in PostgreSQL the claim is `SELECT ... FOR UPDATE SKIP LOCKED` followed by an `UPDATE` inside one
  transaction, so a second worker takes the next rows instead of blocking or duplicating the first;
- `published` and `failed` update the row only where the token matches. A worker whose lease expired
  changes zero rows and gets a `ClaimLostException`: it logs the loss and leaves the job to its owner;
- an expired lease is due again, so a replica that dies mid-publication does not park the payment. The
  next poll — this replica after a restart, or another one — takes it;
- a job read through `pending` has no token and cannot be completed. Reading is not claiming.

The lease is thirty seconds: long enough for one broker call and the commit, short enough that a crash
costs a poll rather than a payment.

## Implemented: HTTP surface

`http.WebhookEndpoint` is the handler for the three paths the contract publishes, and it decides nothing
the contract has not decided:

| Situation | Answer |
|---|---|
| body over 64 KiB | `413` `PAYLOAD_TOO_LARGE`, before the signature is looked at |
| signature missing, malformed, wrong or stale | `401` with the signature failure code |
| authentic but unusable payload | `400` with `MALFORMED_JSON`, `SCHEMA_VALIDATION_FAILED` or `POLICY_REFERENCE_UNPARSEABLE` |
| store could not commit | `503` `STORE_UNAVAILABLE` with `Retry-After: 5` |
| first acceptance | `200` `{"status":"ACCEPTED"}` |
| duplicate | Quetzal and Austral: `200` `{"status":"DUPLICATE"}`; Andina: `204` with no body |
| any other path | `404` |

The paths are `/webhooks/mx/quetzal`, `/webhooks/co/andina` and `/webhooks/cl/austral`. The last one is
a product extension: the original exercise covered Mexico and Colombia, and Chile reuses the same
pipeline with its own adapter — one verifier, one mapper, one route, no fork of the flow.

Two deliberate omissions: no `202` on any path, because the `2xx` is a durability fact and not an
intent; and no content-type gate, because the contract declares a JSON body and its error vocabulary
has no code for an unsupported media type, so inventing one would break the contract it implements.

## Implemented: the Salesforce lane

`SalesforceRelay` drains the `SALESFORCE` destination the canonical publisher deliberately leaves alone, so
a broker outage and a Salesforce outage stay separate failures.

- **Ordering comes from the provider's clock, not from arrival.** Before writing, the relay reads the
  payment projection. An event whose `status_changed_at` is older than the payment's newest fact is skipped
  and marked `STALE_SKIPPED`: a delayed capture cannot replace a reversal that already landed.
- **One writer per payment.** The claim excludes any payment that already has a write in flight, so two
  replicas do not push two facts for one payment at the same moment; within a batch the writes are serial.
  The residual window — two claims starting in the same instant — is real and stated rather than papered
  over: only the destination can close it completely, and that needs an agreement with Salesforce.
- **Every answer is classified, never thrown:**

| Salesforce answer | What it means | What the relay does |
|---|---|---|
| applied | written | `PUBLISHED` / `APPLIED`; the projection records the applied status |
| partial batch | some items landed | retried, `PARTIAL` |
| `INVALID_FIELD` | wrong field or value | `DEAD` / `INVALID_FIELD`; a human corrects it |
| `UNABLE_TO_LOCK_ROW` | a `400` that means "later" | retried with the same 1 s → 60 s curve |
| `INVALID_SESSION_ID` | session expired | one refresh inside the client, then retry |
| `403 REQUEST_LIMIT_EXCEEDED` | daily budget gone | the lane stops and the rest go back to pending |
| `5xx` | remote failure | bounded retry |
| timeout | outcome unknown | bounded retry, `TIMEOUT` |
| applied but response lost | outcome unknown and possibly landed | `HELD` / `LOST_RESPONSE`, never retried |

`HELD` exists for the last row and for nothing else. Retrying it would duplicate a fact; marking it done
would claim a success nobody observed. It is the one state the relay refuses to resolve by itself.

The client is `SimulatedSalesforce`: the fault matrix is scripted per test and no real Salesforce call is
made anywhere in this repository. The port is the seam where a real client goes.

## Running it

Two profiles. The durable one needs nothing but Docker:

```sh
docker compose up --build
```

That starts PostgreSQL 16 and the intake on `127.0.0.1:8080`; the migrations run at startup. Against
it, with a body signed the way the provider signs it:

- first delivery: `200 {"status":"ACCEPTED"}`, and the database holds one `payment_event` and two
  `outbox_job` rows;
- the same body again: `200 {"status":"DUPLICATE"}`, and the counts do not change;
- a tampered signature: `401 {"error":{"code":"SIGNATURE_INVALID",...}}`;
- ten seconds later the `CANONICAL_EVENT` job is `PUBLISHED` and the `SALESFORCE` job is still
  `PENDING`: the two lanes drain independently.

The sandbox vectors and the signature scheme are in `../reference/providers/*/WEBHOOK_SPEC.md`.

The in-memory profile exists for the demo and has to be asked for by name:

```sh
docker run --rm -p 8080:8080 \
  -e INTAKE_ALLOW_IN_MEMORY_STORE=true \
  -e QUETZAL_WEBHOOK_SECRET=<sandbox vector> \
  -e ANDINA_WEBHOOK_SECRET=<sandbox vector> \
  premium-payment-intake:local
```

Without `INTAKE_JDBC_URL` and without that flag the process refuses to start. A deployment that forgot
the database must fail loudly, not accept payments into a store that loses them on restart.

The broker and the secret source are still adapters: the recording broker and the environment. The
store is no longer one of them.

## Tests and evidence

184 JUnit 5 tests: 158 need nothing but a JVM, and 26 more run against a real PostgreSQL when
`INTAKE_IT_JDBC_URL` is set (CI sets it, so they are a gate and not a local habit).

Authentication (51):

- the seven official vectors (3 Quetzal, 4 Andina) verify byte for byte;
- altered body and parsed/re-serialised JSON are rejected;
- missing, blank, malformed and garbage headers are rejected with the contract failure codes;
- rotation with two `v1` values, any match wins;
- clock boundaries: exactly ±300 s / ±600 s accepted, one second beyond rejected;
- missing secret fails closed;
- a logging capture proves no secret, signature, body or payer value is logged;
- a source scan proves no sandbox secret material exists under `src/main`.

Canonical mapping (43):

- every one of the seven samples produces an event that the published JSON Schema accepts;
- both providers are asserted field by field, including the UTC conversion and the cents conversion;
- a retry maps to the same `event_id`, and a different state of the same transaction does not;
- refund branches: full, partial, above the charge amount, and a refund with no reported amount;
- failures: malformed JSON, non-object JSON, a missing event id, an unknown charge type, an unknown
  state, an unmappable policy reference, a currency that is not the provider's, three decimals, a
  zero amount, a missing `notificacion_id`, and a mapper handed the other provider's document;
- the serialised event carries no payer data and no provider notification id;
- the stored document round-trips back to the same event for a captured charge (where the refund
  semantics are null), a refunded charge, and a Colombian reversal.

PostgreSQL store (17, skipped without a database):

- one accept writes one event and two jobs, counted in the tables;
- a second delivery is `DUPLICATE` and the counts do not move;
- the same event id under another provider is another event;
- eight replicas delivering the same event at once commit exactly one event and two jobs;
- a trigger that rejects the second job leaves zero events and zero jobs, and the accept fails;
- the canonical publisher drains its lane and leaves the Salesforce job pending;
- what the publisher reads equals what was accepted;
- the scheduled retry follows `RetrySchedule`: one second, then two, then four;
- the schema in the database was applied by a recorded migration;
- two workers claim disjoint jobs and a third finds the lane empty;
- an expired lease is reclaimable, and the worker that lost it gets `ClaimLostException`;
- a job read without a claim cannot be completed;
- two publisher replicas over the same table send each event once;
- the projection keeps the newest status by the provider clock, a late capture does not replace a newer
  reversal, a duplicate is counted once, and a rolled-back accept leaves no projection row.

Salesforce lane (9, against a database and a scripted destination):

- an applied write marks the job `APPLIED` and records the applied status on the projection;
- an invalid field is `DEAD` and is never sent again;
- a locked row is rescheduled with the same 1 s → 60 s curve;
- a rate limit stops the lane and puts the rest back to pending;
- a lost response is `HELD`, the projection claims nothing, and it is never retried;
- a timeout is retried instead of being called ambiguous;
- an older event is skipped and the newer one applied, with one write to the destination;
- a payment with a write in flight is not claimed by another worker;
- a completion with a stale token changes nothing.

Store and intake (16):

- the first acceptance writes the event and one job per destination;
- a duplicate adds no event and no job, and the same id from another provider is a different event;
- a store that fails before or in the middle of the transaction leaves nothing behind, and the retry
  then succeeds;
- a failed attempt is withheld until its next attempt time, and the wait doubles;
- a claimed job is not offered twice while the lease lives, an expired lease comes back with a new
  token, and a stale token cannot complete the job;
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
- Store: PostgreSQL, in the process. `InMemoryIntakeStore` survives as the named demo profile, not as
  the default path.
- Broker: recording implementation behind `Broker`; the design puts Azure Service Bus behind it.
- Salesforce: not called anywhere. The `SALESFORCE` job is recorded for a relay that is out of scope
  for this part, as the task states.

## Known gaps

- Secrets come from the environment in the runnable image. `SecretProvider` is the boundary the design
  fixes; the Key Vault adapter is the piece still missing, even though the vault and the role
  assignment exist in `infra/`.
- The process runs with the recording broker, so a published message goes to a log line. The store is
  durable; the next hop is not yet.
- The pool is one size for the whole process (eight connections). Per-role pools belong with the
  process split into roles.
- No dead-letter rule: a publication that keeps failing retries forever with a sixty-second cap. Part
  A's design puts the dead letter on the broker and on the relay; here the job simply stays pending.
- The crash evidence is one container killed and restarted against the same database. A database
  failover, a region failure and a restore from backup are not tested, and nothing here claims them.
- No relay against a real Salesforce: the client is a simulator with a scripted fault matrix. The port is
  the seam, and the real client needs the sandbox contract and its session handling.
- No shared usage guard yet: the relay classifies `403 REQUEST_LIMIT_EXCEEDED` and stops the lane, but
  nothing counts calls against the 150,000/day budget before making them.
- Content-type is not gated, on purpose: the contract declares a JSON body and its error enum has no
  code for an unsupported media type.
- `paid_at` and `refunded_at` fall back to `created` when absent. A stricter reading would reject
  those notifications instead; the trade-off is revisited when the store owns the atomicity.
