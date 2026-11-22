# CI runs on pull requests; Azure release is disabled by default

## CI gates

| Gate | Evidence and limits |
|---|---|
| Java 21 + PostgreSQL 16 | Unit and integration tests exercise the webhook, durable local store and JSON Schema with synthetic data. No provider, real Salesforce or Azure service calls. |
| Redocly | Lints the checked-in webhook OpenAPI separately from the Java implementation. |
| Pinned Bicep | Compiles the template and all three stage parameter files without login. Does not validate Azure Policy or routing. |
| Gitleaks | Scans checked-out Git history with default rules and exact fictional sandbox values; the Quetzal sample tokens and PR-214 secret are also path-bound in `gitleaks.toml`. No fixture directory or detector is disabled. |
| Trivy | Builds the demo image for CI only and fails on fixable high/critical findings. Unfixed findings are not an indication of a safe image and require review for any real release. |
| actionlint and ShellCheck | Check workflow contracts, expression scopes and deployment script without Azure. |

## CD controls — definition only

`cd.yml` is manual, `main` only, and disabled unless repository variable
`PRODUCTION_RUNTIME_CD_ENABLED=true`. **Leave it unset for this assessment.** It promotes one
prepublished `sha256:<64 lowercase hex>` manifest through dev → test → prod. It does not build,
publish or deploy the demo; the release team must separately attest and mirror the approved
production-capable runtime to the release-only `premium-payment-intake-runtime` repository in
every stage ACR before dispatch.

Before enabling the workflow:

- Complete the [Azure/runtime prerequisites](../../infra/README.md#what-a-future-release-needs).
- Configure GitHub Environments `dev`, `test` and `prod` with `main` deployment restrictions.
  Require reviewers for `test` and `prod` where supported, prevent self-review, and verify controls
  rather than assuming that naming an environment creates approvals. Review previous-stage smoke
  and recovery evidence before promoting; the public workflow runner cannot probe the private app.
- In each environment configure `STAGE_RUNTIME_READY=true` only after sign-off; set
  `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `RESOURCE_GROUP`, `NAME_PREFIX`,
  `DB_ADMIN_GROUP_ID` and `DB_ADMIN_GROUP_NAME` as variables.
- Register a separate Entra deployment identity per stage with an OIDC federated credential
  restricted to the exact GitHub environment subject. Scope to the stage resource group and its
  required ARM deployment and tightly constrained role assignment permissions. Grant ACR manifest
  read; artifact upload and secret/SQL administration belong to separate identities. No client
  secret is stored.

The reusable stage checks prerequisites before login, verifies the digest in ACR and compiles the
stage Bicep into template/parameter JSON. It supplies the same resolved JSON to `what-if` and
`create`, including the digest-pinned image and administrators. The preview is logged, not a
separate post-preview approval; reviewers approve the stage before it runs. The printed hostname
is private. Roll back an app by dispatching the previously approved digest if schema changes
remain compatible; infrastructure or database rollback needs a separate reviewed plan.
