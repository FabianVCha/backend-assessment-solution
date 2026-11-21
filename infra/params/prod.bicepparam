using '../main.bicep'

// Production: purge protection on, longer retention, three replicas at most.
// The image placeholder is replaced by the CD workflow before deployment.
param environment = 'prod'
param location = 'eastus'
param namePrefix = 'meridian-payments'
param containerImage = 'meridian-payments-prod.azurecr.io/premium-payment-intake:replace-me'
param postgresSkuName = 'Standard_B1ms'
param postgresStorageSizeGb = 40
param serviceBusSku = 'Standard'
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
