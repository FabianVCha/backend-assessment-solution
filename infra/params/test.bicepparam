using '../main.bicep'

// Same private network shape as prod; use separate non-overlapping CIDRs for platform connectivity.
param environment = 'test'
param location = 'eastus'
param postgresAdminGroupObjectId = '00000000-0000-0000-0000-000000000000'
param postgresAdminGroupName = 'REPLACE-WITH-STAGE-DB-ADMINS'
param vnetAddressPrefix = '10.21.0.0/16'
param containerAppsSubnetPrefix = '10.21.0.0/23'
param privateEndpointsSubnetPrefix = '10.21.4.0/24'
param logRetentionInDays = 30
param logDailyCapInGb = 2
param keyVaultPurgeProtection = false
param minReplicas = 1
param maxReplicas = 3
param containerCpu = '0.5'
param containerMemory = '1Gi'
param tags = {
  costCenter: 'payments-integration'
  owner: 'integration-squad'
}
