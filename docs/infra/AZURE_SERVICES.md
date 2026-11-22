# Azure services — private application path, explicit cost

This is a **single-region target**, not a live environment. The existing API Manager is the only
public payment API. The Azure backend and its three data services use private connectivity. The
price trade-off is deliberate: **Service Bus Premium for Private Link**, not for demonstrated
throughput. The local Java demo does not implement the Azure/real Salesforce runtime.

## Service decisions

| Service and starting tier | Why it fits | Trade-off, security and reliability |
|---|---|---|
| Existing API Manager, operated by Meridian | Publishes all provider webhooks and enforces OIDC for internal Operations; preserves the exact webhook body and signature headers over HTTPS | No duplicate gateway or assumed gateway SKU. The platform team must provide private routing, DNS and gateway-only access policy. A gateway without private backend connectivity is not suitable. |
| Container Apps, internal workload-profiles environment with a Consumption profile | Hosts the intake and logical publisher and relay roles without an AKS cluster. Start with 0.5 vCPU/1 GiB, one minimum replica and up to two or three | `external: true` app ingress publishes to the **internal** environment IP, not the internet. `external: false` is environment-only and would reject the API Manager. ACR pull uses managed identity. One replica does not guarantee continuous availability; no zone redundancy. HTTP scaling does not measure worker backlog. Functions would favor trigger-driven workers; App Service and AKS add fixed or operational cost. |
| Service Bus Premium, 1 messaging unit | Topic with independent `finance` and `notifications` subscriptions; private endpoint and private DNS with public access disabled | Entra-only, TLS 1.2+, topic-scoped sender, subscription-scoped receivers assigned separately. Seven-day TTL and duplicate window, ten delivery attempts, per-subscription DLQs. Duplicate detection is not exactly-once. A queue cannot fan out similarly. Standard would be cheaper but needs a public endpoint with tightly allowlisted stable egress IPs for every client, not an unreachable private-only setup. Do not silently enable public access to save cost. |
| PostgreSQL Flexible Server, 16, B1MS / 64 GB initially | Unique constraint and transactional inbox/outbox for durable acceptance; relational queries for Operations | Entra-only, private endpoint, separate admin group and runtime SQL grants. Single instance, seven-day backups, no zone or geo redundancy; restore/reconciliation and zero acknowledged-event loss across a regional failure are not proven. CPU credits, IOPS, connection limits and data growth are release gates. Azure SQL serverless would stay active with polling; Cosmos DB constrains transactional uniqueness and query access by partition. |
| Key Vault Standard | Holds provider signing secrets and the Salesforce client secret outside Git, pipeline and app settings | Private endpoint and scoped secrets-reader role, with soft delete and prod purge protection. Populate and rotate through authorized private tooling; do not assume a running replica can refresh without the runtime adapter. App settings or pipeline secrets are not substitutes. |
| ACR Basic | Per-environment private artifact repository (authenticated), with admin user disabled | The endpoint is publicly routable so authorized CI release tooling can reach it. This does not expose app ingress. Private Link and untagged retention policy require a different SKU; keep promoted manifests pinned and manage cleanup explicitly. An external registry would introduce credentials or additional dependencies. |
| Log Analytics + workspace-based Application Insights | Platform logs, tracing and retention; isolate an assessment environment from shared platform logs | 30/30/90-day retention and 1/2/5 GB daily cap. Sampling can reduce trace cost but must not sample loss/freshness counters or audit records. Caps can interrupt ingestion; they are not budgets. Runtime instrumentation and alert rules are separate. Use existing approved platform sinks instead where policy requires it. |
| VNet, private endpoints and DNS | Separate delegated compute and private endpoint subnets, with non-overlapping CIDRs per environment and four linked DNS zones | Three endpoint zones plus the environment default-domain zone and wildcard A record. Peering/private routing does **not** carry DNS by itself; link caller VNets or forward to an approved resolver. ACR and external Salesforce OAuth/REST still need permitted outbound access. No second region in scope. |

Topic defaults: `canonical-events`, 5 GiB max, 1-minute locks, dead-letter expired messages and
filter failures. DLQs need timely operator action because their backlog is not removed by normal TTL.

## Monthly estimate

Planning in USD, East US, 730 h, one always-active replica at 0.5 vCPU / 1 GiB, ~876,600 requests
per busy environment/month. Uses approximate retail meter assumptions, **not a verified current
quote**. No shared subscription free grants are assumed. Validate SKU/region support and rates
before provisioning.

| Line | Assumption | Prod/month |
|---|---|---:|
| Container Apps | $0.000024/vCPU-s, $0.000003/GiB-s, $0.40/M requests | ~$40 |
| Service Bus Premium | 1 MU × 730 h × ~$1/hour planning rate | ~$730 |
| PostgreSQL | $0.017/h B1MS + 64 GB × $0.115/GB-month | ~$20 |
| Key Vault | 100k × $0.03 per 10k operations | ~$0.30 |
| Log Analytics | 5 GB/month × $2.30/GB; extra retention not modeled | ~$12 |
| ACR Basic | 730 h at roughly $5/month, before extra storage | ~$5 |
| Private endpoints | 3 × 730 h × ~$0.01/h, before transfer | ~$22 |
| Private DNS zones | 4 × ~$0.50/month, before query charges | ~$2 |
| **Total** | One full-time environment | **~$830** |

At 1 GB of logs each, dev and test would cost ~$820/month each if always provisioned (~$2,470/month
across all three). Each independently provisioned Premium namespace is the main fixed cost;
provision non-production Azure environments only when needed. Excluded: existing licensing and
gateway, peering/resolver/NAT, egress, archive, backup overage, added replicas/MUs and a larger or
HA database. Standard via stable allowlisted public egress could be much cheaper if security
approves that topology; it is not the topology in these templates.

## Verification and delivery

See [Infrastructure validation and prerequisites](../../infra/README.md) and
[CI/CD gates](../../.github/workflows/README.md). Compilation verifies Bicep and parameter files,
not Azure Policy, connectivity, runtime behavior, load or recovery objectives.
