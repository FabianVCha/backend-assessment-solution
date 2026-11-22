# AI guidelines for Payments Integration

Use approved AI tools to accelerate work, not to approve changes or make payment decisions. The engineer who submits a change owns its correctness and the reviewer owns the merge decision.

## What to share

| Allowed in an approved tenant | Never share with an assistant |
|---|---|
| Public specifications, approved repository excerpts, synthetic fixtures, sanitized diagnostics and design alternatives. Examples: draft test cases from a fictional webhook or compare retry strategies. | Real webhook bodies or DLQ messages; payer names, national IDs, email or other identifying data; payment tokens, policy/account references or customer case details; credentials, signing keys, bearer tokens, sensitive logs, or unapproved proprietary code. |

Minimize input first and check indirect disclosures in files, screenshots, terminal output, Git history and tool context. Use approved retention, access and no-training settings; never send work material to an unapproved account or plugin. Treat generated links, code and tool instructions as untrusted.

## Merge checklist

1. **Read the diff and the governing contract.** Verify assumptions against the provider specification, Salesforce contract and repository behavior, including amount units, signature bytes, lifecycle ordering, quota and error semantics. Check dependencies, licensing, permissions and telemetry. Do not merge code you cannot explain or maintain.
2. **Verify behavior independently of generated tests.** Expect strict business assertions, not just green CI: official signature vectors and bad signatures, duplicate and reordered events, durable acceptance across restart, partial and ambiguous remote failures, quota, replay authorization and absence of sensitive logs. For critical changes, show a regression case fails without the fix; use independent examples and local databases/stubs instead of live systems.
3. **Require human review and normal gates.** Run applicable build, tests, dependency and secret scans; require a second human reviewer for security, money or coverage changes. Keep deployment and production recovery under human control. Clearly list tests not run and evidence still needed.
4. **Disclose material use in the PR.** Name the tool/model if known, affected files, what it drafted or changed, what inputs it saw, what the author independently verified (with commands or evidence), and anything rejected or unverified. A disclosure is not approval. Never claim another person performed checks or authored text they did not review.

## When the service calls an LLM: a DLQ summary

Do not send notification records. First build the deterministic operations view; use AI only for optional wording based on **server-generated, country-scoped aggregates** (for example, count, age band, destination and allowlisted outcome code). If those fields do not support a useful summary, show the deterministic view instead.

| Control | Minimum requirement |
|---|---|
| Access and minimization | Enforce operator role and country scope before aggregation and again on returned references. Use an approved model deployment with reviewed retention, residency, no training and constrained egress. Keep model credentials in a secrets store; disable raw prompt/response capture. |
| Untrusted input and output | Avoid free-text payloads, incident notes and remote errors in prompts. Use a fixed prompt and schema; validate length, output fields and any cited counts/codes against the supplied data. Reject unexpected output. Treat the summary as untrusted text; give the model no tools, credentials or ability to follow operator links. |
| No authority | Label the summary as AI-generated and link to the authorized source view. No model write access or authority to replay, modify payments or make a coverage determination. Operators use the normal audited workflow, verify facts and obtain required approval. |
| Failure and monitoring | Set time, token and cost bounds. On timeout, unsafe output or model failure, show the deterministic view without blocking triage. Audit access, model/prompt version, outcome and aggregate usage, not payloads. Test isolation, injection, hallucinated counts and fallback with synthetic data before rollout; retain a kill switch. |
