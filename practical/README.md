# Practical part — Payment notification intake (MX, CO)

Part B intake for Quetzal Pagos (Mexico) and Andina Pay (Colombia). This is an assessment
deliverable: no real provider, Salesforce org or broker is called.

## Stack and versions

- Java 21 (Temurin 21.0.12.1), Maven 3.9.16.
- JUnit Jupiter 5.11.4 and Jackson databind 2.18.2, both from Maven Central.
- Plain Java libraries, no framework. The authentication component has no HTTP or dependency
  injection coupling yet.

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

## Project structure

```text
practical/
  pom.xml
  src/main/java/com/meridian/payments/intake/auth/
    Provider.java                  provider enum
    WebhookRequest.java            provider + headers + raw body bytes
    SignatureVerifier.java         verification boundary
    SignatureVerification.java     result and contract failure codes
    QuetzalSignatureVerifier.java  Quetzal Pagos 2025-06-01 scheme
    AndinaSignatureVerifier.java   Andina Pay 2.3 scheme
    SecretProvider.java            secrets-store boundary
    InMemorySecretProvider.java    test/local stub
    Hmac.java                      HMAC-SHA256 and constant-time compare
  src/test/java/com/meridian/payments/intake/auth/
    Fixtures.java                  official vectors and raw sample loader
    OfficialTestVectorsTest.java   the seven published vectors
    QuetzalSignatureVerifierTest.java
    AndinaSignatureVerifierTest.java
    SensitiveDataNeverLoggedTest.java
    ProductionSourcesCarryNoSecretsTest.java
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

## Tests and evidence

51 JUnit 5 tests:

- the seven official vectors (3 Quetzal, 4 Andina) verify byte for byte;
- altered body and parsed/re-serialised JSON are rejected;
- missing, blank, malformed and garbage headers are rejected with the contract failure codes;
- rotation with two `v1` values, any match wins;
- clock boundaries: exactly ±300 s / ±600 s accepted, one second beyond rejected;
- missing secret fails closed;
- a logging capture proves no secret, signature, body or payer value is logged;
- a source scan proves no sandbox secret material exists under `src/main`.

RED/GREEN evidence was captured from the container build: 51 tests, 50 failing on the red phase
(feature not implemented, no compilation error), then 51 passing.

## Stubbed

- Secrets: in-memory provider; the design reads Azure Key Vault.
- Store (inbox/outbox), broker and Salesforce: interfaces in the design, not implemented in this
  lot.

## Known gaps

- No HTTP server and no JSON payload mapping yet; both arrive in the next lot.
- No inbox/outbox transaction, idempotency or canonical event publication yet.
- Request size limits, content-type checks and provider response bodies belong to the HTTP layer,
  not this component.
