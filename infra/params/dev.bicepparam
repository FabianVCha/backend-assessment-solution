using '../main.bicep'

// Development: smallest retention, no purge protection, one replica, and a public hello-world image so
// the environment can be smoke-tested before the pipeline has ever published one.
param environment = 'dev'
param location = 'eastus'
param namePrefix = 'meridian-payments'
param containerImage = 'mcr.microsoft.com/azuredocs/containerapps-helloworld:latest'
param postgresSkuName = 'Standard_B1ms'
param postgresStorageSizeGb = 40
param serviceBusSku = 'Standard'
param logRetentionInDays = 30
param logDailyCapInGb = 1
param keyVaultPurgeProtection = false
// No private endpoints in dev: the vault and the database keep their firewall, and the environment
// saves the endpoint hours. Test and prod do create them.
param enablePrivateEndpoints = false
param minReplicas = 1
param maxReplicas = 2
param containerCpu = '0.5'
param containerMemory = '1Gi'
param tags = {
  costCenter: 'payments-integration'
  owner: 'integration-squad'
}
