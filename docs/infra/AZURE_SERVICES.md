# Azure services — choice, tier and cost

Every resource in `infra/` is here with the reason it exists, what it replaced, the tier that was
chosen and what it costs. The executable form is `infra/main.bicep`; this document is why.

## Method, and what is verified

- Prices were read from the **Azure Retail Prices API** on **2026-11-21** for **eastus**, consumption
  meters. The unit price and the meter name are quoted so anyone can re-check them.
- Two line items (private endpoints and private DNS zones) are **not returned by the API for this
  region today**. They are marked as list price, not as verified, and the estimate says so.
- The estimate is an **order of magnitude with stated assumptions**, not a quote. Nothing was
  deployed, so no invoice exists to compare against.

## The services

### Container Apps (consumption) — compute for the intake and both worker roles

- **Why this one.** The design needs something that answers a webhook at any hour, runs a polling loop
  inside the same process, carries no state and could be one deployment for intake, relay and
  publisher (`ADR-0002`, amendment). Container Apps gives an internal-ingress HTTP app, a VNet
  integration and per-second billing without a cluster to operate. One replica minimum keeps the
  webhook warm; the month-end burst scales it out on concurrent requests.
- **Alternatives discarded.** *App Service*: a fixed plan is billed 24/7 at a size that can absorb the
  burst, which is the wrong shape for ≈0.17 requests/s normal and 1.4/s at the peak, and it does not
  scale on KEDA-style rules. *Azure Functions*: consumption has cold starts and a bounded execution
  time, and the publisher is a long-lived loop, not a trigger. *AKS*: a control plane and a team to
  operate it, for a workload that fits in half a core.
- **Tier.** Consumption, 0.5 vCPU and 1 GiB per replica, `minReplicas 1`, `maxReplicas 3` in test and
  prod. The workload is I/O bound: HMAC and mapping are microseconds, the wait is the database.
- **Reliability.** A replica restart is invisible to the provider: the event is durable in the inbox
  before the 2xx, and the outbox is drained by the next poll. No zone redundancy in the environment.
- **Security.** `internal: true`: the environment has **no public endpoint**. Only the API Manager
  reaches it. It pulls its image, reads its secret and sends to the topic with the same user-assigned
  managed identity, so there is no credential in the app configuration.
- **Cost.** $0.000024 per active vCPU-second, $0.000003 per GiB-second, $0.40 per million requests
  (eastus, 2026-11-21).

### Service Bus Standard — canonical event topic

- **Why this one.** FR6 needs downstream consumers that subscribe on their own. A queue delivers each
  message to **one** consumer; a topic with one subscription per consumer is what the requirement
  describes. Standard is enough: 1 MB messages, topics, duplicate detection and dead letter are all in
  the tier, and the volume is ≈1.76 M messages/month against a 1 MB ceiling per event that we never
  approach.
- **Alternatives discarded.** *Queue*: cannot fan out, which is the whole point of FR6. *Event Grid*:
  an event-notification service, no per-consumer sessions, no dead-letter per subscription in the same
  model. *Premium*: messaging units and isolated capacity for a workload that does not need them.
- **Tier.** Standard namespace, topic `canonical-events`, subscriptions `finance` and `notifications`,
  `deadLetteringOnMessageExpiration`, `maxDeliveryCount 10`, `lockDuration PT1M`, TTL 7 days,
  duplicate detection window 7 days. `disableLocalAuth: true`: SAS keys are off, every sender uses the
  managed identity.
- **Reliability.** Dead letter per subscription, so a consumer that keeps failing parks messages
  instead of blocking the other one. Publication is at-least-once; the duplicate-detection window is an
  optimisation, and the design says consumers still deduplicate on `(provider, event_id)`.
- **Security.** Public network access disabled, private endpoint, TLS 1.2 minimum, RBAC only. The
  workload holds *Service Bus Data Sender* on the topic and nothing else.
- **Cost.** Base unit **$10.00/month**. Messaging operations are tiered in the API at $0.20, $0.50 and
  $0.80 per million; the budget below carries $2.00 as a top-tier allowance above the base unit.

### Azure Database for PostgreSQL, Flexible Server — inbox, state and outbox

- **Why this one.** The design leans on a unique `(provider, event_id)`, a single transaction that
  writes one inbox row and two outbox rows, and an index on `(destination, status, next_attempt_at,
  outbox_id)`. That is a relational shape, and the store is the durability boundary of NFR3.
- **Alternatives discarded.** *Azure SQL*: serverless 0.5 vCore ≈ $190/month and S0/S1 ≈ $14.52/$29.03,
  priced in `ADR-0002`, rejected on cost and on not being what the team operates either. *Cosmos DB*:
  no unique index across partitions, so deduplication becomes a read-then-write race. *Table
  Storage*: no transactions across rows.
- **Tier.** Flexible Server **B1MS**, PostgreSQL 16, 40 GB, fixed billing, 7-day backups, no zone
  redundancy (as `ADR-0002` states), **Entra-only authentication**: no database password exists.
- **Reliability.** Single instance: it is the single point of failure the ADR already declares. The
  burst-credit model fits the load; a month-end burst that outlasts the credits degrades to baseline,
  which is a known negative consequence, not a discovery.
- **Security.** Public network access disabled, private endpoint, Entra token from the managed identity,
  the intake identity as the only Entra administrator, TLS enforced by the server defaults.
- **Cost.** $0.017/hour ≈ **$12.41/month** + 40 GB × $0.115 ≈ **$4.60/month**.

### Key Vault — secrets

- **Why this one.** The Salesforce client secret has to live somewhere that is not a pipeline variable
  or an app setting, and rotates without a redeploy.
- **Alternatives discarded.** *App configuration with Key Vault references*: still needs the vault.
  *Secrets in the pipeline*: exactly what the CD requirement forbids.
- **Tier.** Standard, **RBAC authorization** (no access policies), soft delete, purge protection on in
  prod only, private endpoint in test and prod.
- **Reliability.** Soft delete plus purge protection means a mistaken deletion is recoverable; the
  secret is read at start-up, so a vault outage during a running replica does not stop traffic.
- **Security.** The workload holds *Key Vault Secrets User* on this vault and nothing else.
- **Cost.** $0.03 per 10,000 operations; ≈100,000 operations/month ≈ **$0.30**.

### Container Registry, Basic — the application image

- **Why this one.** The image has to live somewhere Azure can pull from with the workload identity.
- **Alternatives discarded.** *Docker Hub*: a credential to store and a rate limit to hit. *Premium*:
  geo-replication for one region.
- **Tier.** Basic, admin user disabled, 30-day retention policy on untagged manifests.
- **Security.** **Public network access is enabled**: ACR is the one surface left public, because the
  image is pulled during deployment from GitHub's runners, not from inside the VNet. Moving it behind
  a private endpoint would need a self-hosted runner; that is stated as a gap rather than pretended.
- **Cost.** $0.1666/day ≈ **$5.00/month**, 10 GiB included.

### Log Analytics and Application Insights — observability

- **Why this one.** NFR7 needs a correlation id to be traceable end to end and freshness and loss to
  be reportable per country. One workspace for both the application and the platform logs keeps the
  queries in one place.
- **Alternatives discarded.** *Application Insights classic*: being retired for workspace-based.
  *Third-party APM*: another vendor for a workload that emits counters and traces.
- **Tier.** Pay-as-you-go, retention 30 days in dev and test, **90 in prod**, daily cap 1/2/5 GB,
  sampling at 25 % in Application Insights.
- **Security.** Resource-permission access only (`enableLogAccessUsingOnlyResourcePermissions`), so
  reading logs is a role assignment and not a shared key.
- **Cost.** $2.30 per GB ingested; retention beyond the included 31 days at $0.10 per GB-month.
  Application Insights shares the workspace, so it is not a second bill.

### Virtual network, private endpoints and private DNS

- **Why.** `SOLUTION_DESIGN.md` §1.2 states the intake is not reachable from the internet and that the
  Azure services are reached with managed identity. A private endpoint per data service is how that
  sentence becomes true rather than aspirational.
- **Tier.** One VNet, `snet-containerapps` (/23, the minimum Container Apps accepts) and
  `snet-private-endpoints` (/24); private endpoints for PostgreSQL, Service Bus and Key Vault, each with
  its zone group in `privatelink.*`.
- **Dev exception.** Dev runs with `enablePrivateEndpoints = false` and relies on the services'
  firewall: it saves ≈$23/month and dev is not where payment data lives.
- **Cost.** **$0.01/hour per endpoint** ≈ $7.30/month each × 3 ≈ **$21.90** (list price, not returned
  by the API for this region), plus **≈$0.50/month per private DNS zone** × 3 ≈ **$1.50** (same caveat).

### What is deliberately not here

- **API Manager**: Meridian's existing platform. The contract assumes it terminates TLS, applies rate
  limits and forwards to the internal app; deploying a second gateway would duplicate it.
- **Salesforce**: not ours, reached over OAuth 2.0 with the secret in Key Vault.
- **WAF, Front Door, zone redundancy, ACR private endpoint, alert rules**: listed as gaps in
  `infra/README.md`, not silently omitted. Alerts belong to Part D.

## Monthly estimate

Assumptions: one region, 730 hours, one replica always on at 0.5 vCPU / 1 GiB, ≈876,600 accepted
notifications per average month (design §6), 5 GB of logs per month in prod and 1 GB in dev, 100,000
Key Vault operations.

| Line | Unit price (eastus, 2026-11-21) | Prod / month | Dev / month |
|---|---|---|---|
| Container Apps, vCPU (0.5 × 730 h, minus the 180k free vCPU-s) | $0.000024 / vCPU-s | $27.22 | $27.22 |
| Container Apps, memory (1 GiB × 730 h, minus the 360k free GiB-s) | $0.000003 / GiB-s | $6.80 | $6.80 |
| Container Apps, requests (≈1 M, inside the 2 M free grant) | $0.40 / M | $0.00 | $0.00 |
| Service Bus Standard base unit | $10.00 / month | $10.00 | $10.00 |
| Service Bus messaging operations (allowance) | $0.20–$0.80 / M | $2.00 | $2.00 |
| PostgreSQL B1MS, 730 h | $0.017 / hour | $12.41 | $12.41 |
| PostgreSQL storage, 40 GB | $0.115 / GB-month | $4.60 | $4.60 |
| Key Vault operations, ≈100k | $0.03 / 10k | $0.30 | $0.30 |
| Log Analytics ingestion | $2.30 / GB | $11.50 | $2.30 |
| Container Registry Basic | $0.1666 / day | $5.00 | $5.00 |
| Private endpoints × 3 (list price, unverified) | $0.01 / hour | $21.90 | — |
| Private DNS zones × 3 (list price, unverified) | $0.50 / zone-month | $1.50 | — |
| **Total** | | **≈ $103** | **≈ $71** |

Test carries the prod shape minus the longer retention: **≈ $98**. The three environments together
are **≈ $272/month**.

Two numbers are honest estimates rather than verified quotes: the private endpoint and private DNS
line items, and the Service Bus operations allowance. Everything else comes from a meter the API
returned today.

## CI gates, and why each one earns its minute

| Gate | What it catches | Why it is worth the time |
|---|---|---|
| `mvn test` (Java 21) | A behaviour regression in the intake, the mapper, the store or the publisher | It is the only proof that FR1/FR2/FR4/NFR3 still hold; the schema conformance test fails the build if the event drifts from the contract |
| Redocly lint on the OpenAPI | A contract that stops being a valid 3.1 document | The contract is a deliverable; a broken one is worse than a missing one because the providers are told to trust it |
| `bicep build` + `build-params` | A template that does not compile, or a parameter file that does not type-check | Catches the failure before a deployment attempt, without needing an Azure credential |
| gitleaks | A secret in a diff | The repository is public; a leaked credential cannot be un-leaked by a later commit |
| Trivy on the image | A critical CVE in the base image | The service is internet-facing through the API Manager; the scan is the cheapest place to notice |

## Validation of the infrastructure itself

`bicep build` and `bicep build-params` pass for `main.bicep` and the three parameter files with
**Bicep CLI 0.49.2**, with no diagnostics. That is compile-time validation only: no deployment was
attempted, no `what-if` was run (it needs a subscription and a credential), and the templates have not
been evaluated against Azure Policy.
