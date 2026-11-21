# AI usage disclosure

> **Mandatory.** AI assistants are allowed. What we value is how you governed their use.
> If you did not use any AI tool, tick the box below and leave the rest empty.

- [ ] I did not use any AI tool in this assessment.

## Tools

| Tool (name and version, if known) | Where you used it (part and files) | What you used it for |
|---|---|---|
| OpenCode with GPT-6.2 Sol | G2: Salesforce usage guard, replay command, and INC-0412 fixture under `practical/` | Inspected the relay and migrations, drafted code and tests, and ran local verification. |

## Validation

<!-- For each significant use: how did you check the output? (tests you wrote or ran, documentation you checked, reasoning you redid, reviews you did line by line...). -->

For G2, the assistant ran the Java 21 Maven suite with PostgreSQL 16 and reviewed the shared
admission and replay transactions with independent probes. INC-0412 used the fictional fixture
and a simulated client; it did not use a Salesforce org.

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
