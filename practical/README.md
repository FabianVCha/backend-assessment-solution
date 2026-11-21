# Practical part — Payment notification intake (MX, CO)

Part B intake for Quetzal Pagos (Mexico) and Andina Pay (Colombia). This is an assessment
deliverable: no real provider, Salesforce org or broker is called.

## Stack and versions

- Java 21 (Temurin 21.0.12.1), Maven 3.9.16.
- JUnit Jupiter 5.11.4 and Jackson databind 2.18.2, both from Maven Central.
- networknt json-schema-validator 2.0.8, **test scope only**: the conformance test validates the
  events this code produces against `docs/api/events/premium-payment.schema.json`. The schema is
  read from the repository, never copied, so a changed contract cannot keep passing unnoticed.
- Plain Java libraries, no framework. The components have no HTTP or dependency injection coupling
  yet.

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
  src/main/java/com/meridian/payments/intake/
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
  src/test/java/com/meridian/payments/intake/
    TestSamples.java                 published vectors, samples and the schema path
    auth/                            signature tests
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

## Tests and evidence

91 JUnit 5 tests.

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

RED/GREEN evidence was captured from the container build:

- authentication: 51 tests, 50 failing before the implementation (assertion failures, no compilation
  error), then 51 passing;
- canonical mapping: 90 tests, 39 failing before the implementation (11 failures and 28 errors, all
  `NOT_IMPLEMENTED`, no compilation error), then 91 passing with `BUILD SUCCESS`.

## Stubbed

- Secrets: in-memory provider; the design reads Azure Key Vault.
- Store (inbox/outbox), broker and Salesforce: interfaces in the design, not implemented yet.

## Known gaps

- No HTTP server: the size limit, the content-type check, the translation of a failure code to
  `400`/`401`/`413`/`503` and the receipt bodies belong to the next lot.
- No inbox/outbox transaction, idempotency enforcement or publication yet.
- `paid_at` and `refunded_at` fall back to `created` when absent. A stricter reading would reject
  those notifications instead; the trade-off is revisited when the store owns the atomicity.
