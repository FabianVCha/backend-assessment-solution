using '../main.bicep'

// Test: same shape as prod, cheaper retention, and the image is the one the pipeline publishes.
// The image placeholder is replaced by the CD workflow before deployment.
param environment = 'test'
param location = 'eastus'
param namePrefix = 'meridian-payments'
param containerImage = 'meridian-payments-test.azurecr.io/premium-payment-intake:replace-me'
param postgresSkuName = 'Standard_B1ms'
param postgresStorageSizeGb = 40
param serviceBusSku = 'Standard'
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
