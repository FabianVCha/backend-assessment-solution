# AI usage disclosure

> **Mandatory.** AI assistants are allowed. What we value is how you governed their use.
> If you did not use any AI tool, tick the box below and leave the rest empty.

- [ ] I did not use any AI tool in this assessment.

## Existing disclosure (Fabián)

The G2 and G3a entries below are preserved from the existing repository. They describe prior
work and are not new attestations.

## Tools

| Tool (name and version, if known) | Where you used it (part and files) | What you used it for |
|---|---|---|
| OpenCode with GPT-6.2 Sol | G2: Salesforce usage guard, replay command, and INC-0412 fixture under `practical/` | Inspected the relay and migrations, drafted code and tests, and ran local verification. |
| OpenCode with GPT-6.2 Sol | G3a: local Operations API, PostgreSQL migrations, tests and API documentation | Mapped the existing server and replay seams, wrote contract tests before the implementation, and ran local verification. |

## Validation

<!-- For each significant use: how did you check the output? (tests you wrote or ran, documentation you checked, reasoning you redid, reviews you did line by line...). -->

For G2, the assistant ran the Java 21 Maven suite with PostgreSQL 16 and reviewed the shared
admission and replay transactions with independent probes. INC-0412 used the fictional fixture
and a simulated client; it did not use a Salesforce org.

G3a used a local PostgreSQL 16 database and real HTTP requests to verify signed-token rejection,
role and country isolation, and the committed replay audit. The local token issuer and test
destinations were fixtures, not production services.

## What I discarded or corrected

<!-- Suggestions you rejected or fixed, and why. Concrete examples are more useful than general statements. -->

- A UTC counter alone would reset the allowance at midnight. The implementation counts the
  previous day's unexpired admissions as well.
- Replay does not modify the original event or bypass ordering. The incident test checks for
  `STALE_SKIPPED` and no additional client call.

## Data I shared with AI tools

<!-- What did you paste or upload into AI tools? Confirm that no secrets or personal data were shared. -->

G2 used repository source, fictional assessment examples, and local PostgreSQL test output.
No real Salesforce credentials or customer data were used.
G3a also used only repository source, test fixtures and local outputs.

## Local delivery addendum (AI-assisted)

OpenCode with GPT-6.2 Sol assisted this local delivery. It drafted the operations plan and
INC-0412 response (`docs/operations/`), reviewed the supplied prototype diff (`pr-review/REVIEW.md`),
and drafted the proposed squad guidelines (`docs/ai/`). The local delivery also includes
AI-assisted design and infrastructure documentation, relay/replay safety changes and tests,
and the terminal operations console. The console uses the existing Operations API with a local
sample identity; it does not call Salesforce or Azure.

**Checks performed for this documentation:** The assistant cross-checked the incident timeline,
lookup failures and counts against the supplied incident, Andina changelog and Salesforce
reference. Review locations were checked against the new-file lines in the supplied diff and
findings against the Austral and Salesforce contracts. `mvn -o -B -Dtest=Incident0412IT test`
passed (1 test, 0 skipped) with Java 21 and a disposable PostgreSQL 16.16 database using
`SimulatedSalesforce`. This was a local regression check, not a PR-214 CI rerun, a v1
incident exercise, or evidence about live coverage or Azure alerts.

**Checks performed for the integrated local delivery:** The assistant ran Java 21
`mvn -B -ntp clean verify` with disposable PostgreSQL 16.16 (215 passing, none skipped),
ran 21 terminal-client tests, built and ran the demo in OrbStack and exercised signed
synthetic notifications, country and role enforcement, and single-destination replay
through the terminal and the live local API. A V8-to-V9 fixture kept applied state and
recent call history intact while correcting the observed projection. The test data and
ops identity were synthetic. The later full-screen dashboard was also exercised in a
pseudo-terminal against the same live local API, including confirmed aggregate export and,
in the updated launcher, color, inline search and opening a result.
Azure deployments, production-sized load, backup restore
and live Salesforce reconciliation were not performed.

**Corrections and limits:** The incident plan rejects bulk or forced replay and does not infer
a policy match from format alone. The SLOs, incident interventions, review test requests and LLM
controls are proposals, not completed production checks. The documentation work used repository
source, fictional assessment material and local test output, not production credentials,
customer data or real payment-system access. This addendum attributes the assistant's work;
it does not claim personal execution, verification or endorsement by Fabián. The submitting
engineer must review the resulting artifacts and own the submission decision.
