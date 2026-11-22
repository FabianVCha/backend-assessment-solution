using '../main.bicep'

// Production: purge protection on, longer retention, three replicas at most.
param environment = 'prod'
param location = 'eastus'
param postgresAdminGroupObjectId = '00000000-0000-0000-0000-000000000000'
param postgresAdminGroupName = 'REPLACE-WITH-STAGE-DB-ADMINS'
param vnetAddressPrefix = '10.22.0.0/16'
param containerAppsSubnetPrefix = '10.22.0.0/23'
param privateEndpointsSubnetPrefix = '10.22.4.0/24'
param logRetentionInDays = 90
param logDailyCapInGb = 5
param keyVaultPurgeProtection = true
param minReplicas = 1
param maxReplicas = 3
param containerCpu = '0.5'
param containerMemory = '1Gi'
param tags = {
  costCenter: 'payments-integration'
  owner: 'integration-squad'
}
