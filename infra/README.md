# Azure infrastructure — templates only

**Do not deploy for this assessment.** `main.bicep` is resource-group scoped. The checked-in
`params/{dev,test,prod}.bicepparam` files default to **foundation only** and carry an intentionally
invalid example database admin group; they do not deploy the Java demo. See
[service choices and cost](../docs/infra/AZURE_SERVICES.md) and
[the gated delivery definition](../.github/workflows/README.md).

## What is defined

| Module | Resources and boundaries |
|---|---|
| `network` | Non-overlapping stage VNets by default, delegated Container Apps subnet, endpoint subnet and private-link DNS zones |
| `compute`, `ingress-dns` | Internal workload-profiles environment with Consumption profile and linked wildcard DNS. An app is conditional on `deployApplication`; app-level `external: true` makes it reachable through the internal IP only. |
| `messaging` | Premium (1 MU), private endpoint, topic and independent subscriptions with DLQs |
| `data` | Entra-only Flexible Server, database, separate admin group and private endpoint. B1MS/64 GB are initial sizing, not benchmarks. |
| `keyvault` | Standard vault with RBAC, private endpoint and prod purge protection |
| `observability` | Workspace and Application Insights; 30/30/90-day retention, 1/2/5 GB daily caps |
| `roles` and `main` | Workload managed identity, Basic ACR and scoped topic send, vault read, image pull and metrics roles. SQL role grants are separate. |

Separate resource groups, identifiers, releases and secrets per stage. Set an available, compact
`namePrefix`: ACR, PostgreSQL, Service Bus and Key Vault names must be globally available. Verify
VNet CIDRs against the platform network before connecting it. One deployment per environment
serves all three countries; provider adapters, secrets and access rules vary by country, not
these resources. For a residency requirement, review and isolate the affected data, region,
retention and operational access before duplicating the topology.

## What a future release needs

1. Provision the foundation separately with the stage's final name prefix, address plan and valid
   Entra database admin group. In a routed private session, use separate admin/migrator and runtime
   SQL roles; apply migrations and grant only the application DML it needs. Do not make the workload
   a PostgreSQL administrator. Load Key Vault secrets with a separately authorized identity.
2. Connect API Manager to the stage VNet by approved private routing (platform VNet integration,
   peering or equivalent). Grant it DNS resolution of the exported `intakeDnsZone` (zone link or
   conditional forwarding through a resolver), route to `intakePrivateIp` and use the deployed
   `intakePrivateFqdn` as the HTTPS host/SNI. Restrict the reachable private ingress sources to
   the gateway at the agreed private-network boundary, preserve signed bytes and prove reachability
   from the gateway plus rejection from unauthorized sources. The template does not configure the
   enterprise gateway, peerings, resolver or gateway-only access policy.
3. Provide a production image with Entra PostgreSQL authentication, Key Vault secret loading and
   rotation, Service Bus managed-identity send, real Salesforce OAuth/collection upsert, validated
   ordering/retry semantics, `/healthz` process health and `/readyz` intake readiness on 8080,
   and production Operations authentication. Do not gate acknowledgment readiness on Salesforce
   or the broker: those are asynchronous dependencies.
   Template URI/host/topic settings are the target image contract, not settings the current
   local demo understands. Verify permitted Salesforce egress, private data connections and
   subscriber network/roles. Test load, restore/data loss, and unhealthy dependency cases first.
4. Mirror the approved manifest digest into the three bootstrapped registries and configure the
   GitHub release gates. The workflow cannot build a production artifact from this repository.

Single region and no zone redundancy are explicit gaps. A restore can require reconciliation and
does not prove zero data loss. Budget, alert provisioning, consumer roles and platform policies
also remain prerequisites. ACR is publicly routable and requires Entra authentication.

## Validation

Offline with Bicep CLI **0.49.2**:

```sh
bicep build infra/main.bicep --stdout > /dev/null
for stage in dev test prod; do
  bicep build-params "infra/params/$stage.bicepparam" --stdout > /dev/null
done
```

CI also compiles with the pinned Bicep version. No Azure login, Policy evaluation, ARM what-if,
deployment, network test or cloud load test was performed. The optional application and all
private networking still need an authorized team's verification.
