#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

case "${STAGE:-}" in dev|test|prod) ;; *) fail 'Invalid deployment stage.' ;; esac
[[ "${STAGE_READY:-}" == true ]] || fail 'Stage runtime prerequisites have not been approved.'
[[ "${RELEASE_DIGEST:-}" =~ ^sha256:[0-9a-f]{64}$ ]] || fail 'A release manifest digest is required.'
[[ "${NAME_PREFIX:-}" =~ ^[a-z][a-z0-9]*(-[a-z0-9]+)*$ && "${#NAME_PREFIX}" -ge 3 && "${#NAME_PREFIX}" -le 14 ]] ||
  fail 'Invalid stage name prefix (3–14 lowercase letters, digits or single internal hyphens).'
[[ "${DB_ADMIN_GROUP_ID:-}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ &&
   "$DB_ADMIN_GROUP_ID" != 00000000-0000-0000-0000-000000000000 ]] || fail 'A real Entra database administrator group ID is required.'
: "${RESOURCE_GROUP:?}" "${DB_ADMIN_GROUP_NAME:?}" "${AZURE_CLIENT_ID:?}" "${AZURE_TENANT_ID:?}" "${AZURE_SUBSCRIPTION_ID:?}"
[[ "$DB_ADMIN_GROUP_NAME" != REPLACE-WITH-STAGE-DB-ADMINS ]] || fail 'A real database administrator group name is required.'

case "${1:-}" in
  check) exit 0 ;;
  apply) ;;
  *) fail 'Expected check or apply.' ;;
esac

registry="${NAME_PREFIX//-/}${STAGE}acr"
repo='premium-payment-intake-runtime'
registry_server=$(az acr show --name "$registry" --resource-group "$RESOURCE_GROUP" --query loginServer -o tsv)
[[ -n "$registry_server" ]] || fail 'Stage registry has not been bootstrapped.'
az acr manifest show-metadata --registry "$registry" --name "$repo@$RELEASE_DIGEST" --only-show-errors -o none

workdir=$(mktemp -d)
trap 'rm -r "$workdir"' EXIT
az bicep build --file infra/main.bicep --outfile "$workdir/template.json"
az bicep build-params --file "infra/params/$STAGE.bicepparam" --outfile "$workdir/base-parameters.json"
jq --arg prefix "$NAME_PREFIX" --arg id "$DB_ADMIN_GROUP_ID" --arg admin "$DB_ADMIN_GROUP_NAME" \
  --arg image "$registry_server/$repo@$RELEASE_DIGEST" \
  '.parameters += {
    namePrefix: {value: $prefix},
    postgresAdminGroupObjectId: {value: $id},
    postgresAdminGroupName: {value: $admin},
    deployApplication: {value: true},
    containerImage: {value: $image}
  }' "$workdir/base-parameters.json" > "$workdir/release-parameters.json"

args=(--name "payments-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
      --resource-group "$RESOURCE_GROUP" --template-file "$workdir/template.json"
      --parameters "@$workdir/release-parameters.json" --mode Incremental)
az deployment group what-if "${args[@]}"
az deployment group create "${args[@]}" --query 'properties.outputs.intakePrivateFqdn.value' -o tsv
