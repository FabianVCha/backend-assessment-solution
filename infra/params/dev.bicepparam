using '../main.bicep'

// Compile-time example. Set the database administrator group before provisioning a foundation.
param environment = 'dev'
param location = 'eastus'
param postgresAdminGroupObjectId = '00000000-0000-0000-0000-000000000000'
param postgresAdminGroupName = 'REPLACE-WITH-STAGE-DB-ADMINS'
param logRetentionInDays = 30
param logDailyCapInGb = 1
param keyVaultPurgeProtection = false
param minReplicas = 1
param maxReplicas = 2
param containerCpu = '0.5'
param containerMemory = '1Gi'
param tags = {
  costCenter: 'payments-integration'
  owner: 'integration-squad'
}
