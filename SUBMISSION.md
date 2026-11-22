# Submission

> Candidate handoff: the factual implementation summary is ready for review. The candidate must
> provide their own identity, timing and final submission details before sending.

## Candidate

| Field | Value |
|---|---|
| Candidate ID (provided by your recruiter) | fabian.vasquez.a0952@gmail.com |
| Final commit SHA | f7151b5270fae7d7398f091ab55407176a203a97 |
| Date and time of submission (with time zone) | 2026-11-21 21:31:49 -05 (-05:00) |

## Time spent

| Part | Approximate time |
|---|---|
| A. Integration and data design | Candidate to complete |
| B. Integration component | Candidate to complete |
| C. Azure IaC and CI/CD | Candidate to complete |
| D. Operations | Candidate to complete |
| E. Code review | Candidate to complete |
| F. Responsible AI | Candidate to complete |
| **Total** | Candidate to complete |

## Stack

- Practical part (Part B): Java 21, Maven, JUnit 5, a PostgreSQL 16 local profile, and external-service stubs.
- Infrastructure as code: Azure Bicep.
- Pipeline: GitHub Actions; deployment definitions only, not exercised against Azure.
- Optional product extension: terminal console over the country-scoped operations API; Python 3 standard library.

## Where to start reading

1. [Architecture and capacity](docs/design/SOLUTION_DESIGN.md) — end-to-end design, trade-offs and estimates.
2. [Practical implementation and evidence](practical/README.md) — provider adapters, transactional acceptance and regressions.
3. [Local demonstration](LOCAL_DEMO.md) — Docker Compose, fictional signed events and the optional console.

## Known gaps and what I would do next

- The broker and Salesforce are simulated locally. Production approval requires authorized verification of private access, identity, publish acknowledgements, Salesforce reconciliation and ordering semantics.
- The operations profile uses local HS256 tokens, not a production identity provider. The console is optional and cannot bypass the API's permissions.
- A sustained production-sized workload, database restore and Azure rollout were not executed. Do not infer SLA compliance from the local test suite.
- See [AI disclosure](AI_USAGE.md) for provenance; the candidate should review and update it to reflect their own work and validation before sending.
