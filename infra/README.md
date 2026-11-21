# Infrastructure as code

Bicep for the Premium Payment Notification intake: messaging, store, secrets, identity and
observability. **Nothing here has been deployed.** The templates compile and that is all that has been
proven; see [Validation](#validation).

Service choice, tier and cost per resource are in [`docs/infra/AZURE_SERVICES.md`](../docs/infra/AZURE_SERVICES.md).
This file is the operator's view: what gets created, with which parameters, and what is not there yet.

- **Tool:** Bicep, validated with **Bicep CLI 0.49.2** (the version `az bicep` installs at the time of
  writing). No Terraform, no state file to host: the template is compiled and deployed as one unit.
- **Scope:** resource group. Each environment has its own resource group and its own deployment.

## What it creates

| Resource | Name pattern | Notes |
|---|---|---|
| User-assigned managed identity | `meridian-payments-<env>-id` | One identity for the workload: secret read, topic send, image pull, metrics |
| Container registry (Basic) | `meridianpayments<env>acr` | Admin user disabled, 30-day untagged retention |
| Virtual network | `meridian-payments-<env>-vnet` | `snet-containerapps` (/23) and `snet-private-endpoints` (/24) |
| Private DNS zones | `privatelink.*` | PostgreSQL, Service Bus, Key Vault |
| Log Analytics workspace | `meridian-payments-<env>-law` | Retention 30/30/90, daily cap 1/2/5 GB |
| Application Insights | `meridian-payments-<env>-appi` | Workspace-based, 25 % sampling |
| Key Vault | `meridian-payments-<env>-kv` | RBAC only, soft delete, purge protection in prod |
| Service Bus namespace (Standard) | `meridian-payments-<env>-sb` | Local auth off, public access off, TLS 1.2 |
| Topic `canonical-events` + subscriptions | `finance`, `notifications` | Dead letter on expiry and on filter errors, TTL 7 d, duplicate window 7 d |
| PostgreSQL Flexible Server (B1MS) | `meridian-payments-<env>-pg` | PostgreSQL 16, 40 GB, 7-day backup, Entra-only auth |
| Database `payments` | — | The inbox, the state and the outbox live here |
| Container Apps environment + app | `meridian-payments-<env>-cae` / `-intake` | Internal ingress, 0.5 vCPU / 1 GiB, 1–3 replicas |
| Role assignments | — | Service Bus Data Sender on the topic, Key Vault Secrets User, AcrPull, Metrics Publisher |

Role assignments are scoped to the individual resource, never to the resource group or the
subscription: a compromised container cannot read another environment's vault.

## Layout

```text
infra/
  main.bicep                  resource-group scoped entry point
  modules/
    network.bicep             VNet, subnets, private DNS zones
    observability.bicep       workspace, Application Insights, retention and cap
    keyvault.bicep            vault, optional private endpoint
    messaging.bicep           namespace, topic, one subscription per consumer
    data.bicep                PostgreSQL, database, Entra administrator, optional private endpoint
    roles.bicep               least-privilege assignments for the workload identity
    compute.bicep             Container Apps environment and the app
  params/
    dev.bicepparam            no private endpoints, 30-day logs, 1–2 replicas
    test.bicepparam           private endpoints, 30-day logs, 1–3 replicas
    prod.bicepparam           private endpoints, 90-day logs, purge protection, 1–3 replicas
```

### Parameters per environment

Every environment file sets the same keys and differs in the ones that cost money or change a safety
property: `logRetentionInDays`, `logDailyCapInGb`, `keyVaultPurgeProtection`, `enablePrivateEndpoints`,
`minReplicas`, `maxReplicas` and `containerImage`. Nothing else is duplicated, so a diff between two
parameter files is a readable list of what actually differs.

### Parameters per country

There is **one deployment per environment and none per country**, and that is deliberate: the three
countries share one flow, and the country is data, not infrastructure. It appears in the canonical
event (`country`, the `payment_key` prefix, the `policy_number`), which is what keeps adding a fourth
provider from requiring new resources (NFR9).

The parameterization point exists anyway: if a country ever required data residency, the same template
deploys into a second resource group with its own VNet and its own PostgreSQL instance, and no module
changes. That is stated here so the reviewer knows it was considered rather than forgotten.

## Deploying (not done here)

```sh
az deployment group create \
  --resource-group  <resource-group> \
  --template-file   infra/main.bicep \
  --parameters      infra/params/prod.bicepparam \
  --parameters      containerImage=<registry>/premium-payment-intake:<tag>
```

The image parameter is the only value the pipeline overrides; everything else is the environment's
file, reviewable in the diff.

## Validation

What was run, with the exact tool:

- **Bicep CLI 0.49.2**, official release, on 2026-11-21:
  - `bicep build infra/main.bicep` → **no diagnostics**, warnings included.
  - `bicep build-params` for `dev`, `test` and `prod` → **no diagnostics**.
- The same checks run in CI (`.github/workflows/ci.yml`) through `az bicep`, without any Azure
  credential, so a template that stops compiling fails the pull request.

What was **not** run, and therefore is not claimed:

- No `az deployment group what-if`: it needs a subscription and a credential, and this deliverable
  does not touch an Azure tenant.
- No deployment of any kind.
- No evaluation against Azure Policy, and no Defender for Cloud assessment.
- No load test: the B1MS burst claim in `ADR-0002` is an analysis, not a measurement.

## Known gaps

- **ACR is reachable from the public internet.** The image is pulled from GitHub's runners at deploy
  time; a private endpoint would need a self-hosted runner. Trade-off stated, not hidden.
- **No zone redundancy** anywhere. `ADR-0002` already declares the database a single point of failure;
  the Container Apps environment is not zone-redundant either.
- **No alert rules.** Part D owns them; this template creates the sink they will query.
- **No Azure Policy, no budget, no cost anomaly alert.** The daily ingestion cap is the only guard rail
  against a runaway bill.
- **Only the HTTP concurrency scaler.** The publisher shares the deployment, so scaling the webhook
  path scales the loop with it. A separate worker deployment is the next step if the poll cadence ever
  needs its own scale rule.
- **`what-if` only on prod.** Test and dev deploy straight through once their reviewer approves.
