// Premium payment notification intake — Part C infrastructure.
//
// Deploy with `az deployment group create` (the CD workflow does exactly that, one environment at a
// time). Nothing here is deployed by this repository: the pipeline stages are defined, not executed.
//
// Service choices and their costs are justified in docs/infra/AZURE_SERVICES.md; this file is the
// executable form of that document.

targetScope = 'resourceGroup'

@description('Environment name. It is part of every resource name on purpose: dev, test and prod live in separate resource groups.')
@allowed([
  'dev'
  'test'
  'prod'
])
param environment string

@description('Azure region for every resource.')
param location string = resourceGroup().location

@description('Prefix for every resource name.')
param namePrefix string = 'meridian-payments'

@description('PostgreSQL compute size; B1MS is a load-test starting point.')
param postgresSkuName string = 'Standard_B1ms'

@allowed([
  'Burstable'
  'GeneralPurpose'
  'MemoryOptimized'
])
param postgresSkuTier string = 'Burstable'

@description('PostgreSQL initial storage in GB, at a supported disk tier. Re-size after measuring actual row sizes and I/O.')
param postgresStorageSizeGb int = 64

@description('Object ID of a separate Entra database administrator group used to bootstrap application roles.')
param postgresAdminGroupObjectId string

@description('Display name of the Entra database administrator group.')
param postgresAdminGroupName string

@description('PostgreSQL backup retention in days.')
param postgresBackupRetentionDays int = 7

@description('Service Bus Premium messaging units. Premium is required for the private endpoint.')
@allowed([
  1
  2
  4
  8
  16
])
param serviceBusMessagingUnits int = 1

@description('Consumer subscriptions created on the canonical-event topic.')
param consumerSubscriptions array = [
  'finance'
  'notifications'
]

@description('Deliveries before a message is dead-lettered.')
param serviceBusMaxDeliveryCount int = 10

@description('Per-delivery lock, ISO 8601.')
param serviceBusLockDuration string = 'PT1M'

@description('Message time to live, ISO 8601.')
param serviceBusMessageTtl string = 'P7D'

@description('Log Analytics retention. Prod keeps 90 days; dev and test keep 30.')
param logRetentionInDays int = 30

@description('Daily ingestion cap in GB. A cap is cheaper than discovering a log loop on the invoice.')
param logDailyCapInGb int = 1

@description('Key Vault purge protection. On in prod, where an accidental purge would be irreversible anyway.')
param keyVaultPurgeProtection bool = false

@description('Key Vault soft-delete retention in days.')
param keyVaultSoftDeleteRetentionInDays int = 7

@description('Deploy the intake app only after registering an approved runtime image and completing stage prerequisites.')
param deployApplication bool = false

@description('Fully qualified release image reference pinned by manifest digest; blank for foundation-only deployment.')
param containerImage string = ''

@description('Replica bounds.')
param minReplicas int = 1
param maxReplicas int = 3

@description('CPU and memory per replica.')
param containerCpu string = '0.5'
param containerMemory string = '1Gi'

@description('VNet address plan.')
param vnetAddressPrefix string = '10.20.0.0/16'
param containerAppsSubnetPrefix string = '10.20.0.0/23'
param privateEndpointsSubnetPrefix string = '10.20.4.0/24'

param tags object = {}

var resourceTags = union(tags, {
  environment: environment
  workload: 'premium-payment-intake'
  managedBy: 'bicep'
})

// The workload identity. One identity for the app: it reads a secret, sends to the topic, pulls its
// image and publishes metrics, and nothing else.
resource identity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: '${namePrefix}-${environment}-id'
  location: location
  tags: resourceTags
}

// Registry for the application image. Basic is enough for one image per environment per week.
resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: replace('${namePrefix}${environment}acr', '-', '')
  location: location
  tags: resourceTags
  sku: {
    name: 'Basic'
  }
  properties: {
    adminUserEnabled: false
    // Registry admission is enforced by Entra RBAC. Basic does not support Private Link.
    publicNetworkAccess: 'Enabled'
  }
}

module network 'modules/network.bicep' = {
  name: 'network'
  params: {
    namePrefix: namePrefix
    environment: environment
    location: location
    vnetAddressPrefix: vnetAddressPrefix
    containerAppsSubnetPrefix: containerAppsSubnetPrefix
    privateEndpointsSubnetPrefix: privateEndpointsSubnetPrefix
    tags: resourceTags
  }
}

module observability 'modules/observability.bicep' = {
  name: 'observability'
  params: {
    namePrefix: namePrefix
    environment: environment
    location: location
    retentionInDays: logRetentionInDays
    dailyCapInGb: logDailyCapInGb
    tags: resourceTags
  }
}

module keyVault 'modules/keyvault.bicep' = {
  name: 'keyvault'
  params: {
    namePrefix: namePrefix
    environment: environment
    location: location
    softDeleteRetentionInDays: keyVaultSoftDeleteRetentionInDays
    purgeProtectionEnabled: keyVaultPurgeProtection
    privateEndpointSubnetId: network.outputs.privateEndpointsSubnetId
    privateDnsZoneId: network.outputs.vaultDnsZoneId
    tags: resourceTags
  }
}

module messaging 'modules/messaging.bicep' = {
  name: 'messaging'
  params: {
    namePrefix: namePrefix
    environment: environment
    location: location
    messagingUnits: serviceBusMessagingUnits
    privateEndpointSubnetId: network.outputs.privateEndpointsSubnetId
    privateDnsZoneId: network.outputs.serviceBusDnsZoneId
    subscriptionNames: consumerSubscriptions
    maxDeliveryCount: serviceBusMaxDeliveryCount
    lockDuration: serviceBusLockDuration
    timeToLive: serviceBusMessageTtl
    tags: resourceTags
  }
}

module data 'modules/data.bicep' = {
  name: 'data'
  params: {
    namePrefix: namePrefix
    environment: environment
    location: location
    skuName: postgresSkuName
    skuTier: postgresSkuTier
    storageSizeGb: postgresStorageSizeGb
    backupRetentionDays: postgresBackupRetentionDays
    administratorObjectId: postgresAdminGroupObjectId
    administratorLogin: postgresAdminGroupName
    privateEndpointSubnetId: network.outputs.privateEndpointsSubnetId
    privateDnsZoneId: network.outputs.postgresDnsZoneId
    tags: resourceTags
  }
}

module roles 'modules/roles.bicep' = {
  name: 'roles'
  params: {
    principalId: identity.properties.principalId
    serviceBusNamespaceName: messaging.outputs.namespaceName
    serviceBusTopicName: messaging.outputs.topicName
    keyVaultId: keyVault.outputs.id
    containerRegistryId: registry.id
    applicationInsightsId: observability.outputs.applicationInsightsId
  }
}

module compute 'modules/compute.bicep' = {
  name: 'compute'
  params: {
    namePrefix: namePrefix
    environment: environment
    location: location
    image: containerImage
    deployApplication: deployApplication
    vnetId: network.outputs.vnetId
    containerAppsSubnetId: network.outputs.containerAppsSubnetId
    logAnalyticsCustomerId: observability.outputs.workspaceCustomerId
    logAnalyticsSharedKey: observability.outputs.workspaceSharedKey
    identityId: identity.id
    identityClientId: identity.properties.clientId
    registryLoginServer: registry.properties.loginServer
    serviceBusNamespace: messaging.outputs.fullyQualifiedNamespace
    serviceBusTopic: messaging.outputs.topicName
    postgresHost: data.outputs.fqdn
    postgresDatabase: data.outputs.databaseName
    keyVaultUri: keyVault.outputs.uri
    applicationInsightsConnectionString: observability.outputs.applicationInsightsConnectionString
    minReplicas: minReplicas
    maxReplicas: maxReplicas
    cpuCores: containerCpu
    memorySize: containerMemory
    tags: resourceTags
  }
  dependsOn: [
    roles
  ]
}

output intakeAppName string = compute.outputs.appName
output intakeAppId string = compute.outputs.appId
output intakePrivateFqdn string = compute.outputs.appFqdn
output intakePrivateIp string = compute.outputs.environmentPrivateIp
output intakeDnsZone string = compute.outputs.environmentDnsZone
output registryLoginServer string = registry.properties.loginServer
output registryName string = registry.name
output identityClientId string = identity.properties.clientId
output identityPrincipalId string = identity.properties.principalId
output serviceBusNamespace string = messaging.outputs.fullyQualifiedNamespace
output serviceBusTopic string = messaging.outputs.topicName
output postgresHost string = data.outputs.fqdn
output keyVaultName string = keyVault.outputs.name
output applicationInsightsId string = observability.outputs.applicationInsightsId
