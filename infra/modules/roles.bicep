// Least-privilege role assignments for the workload identity.
// Each one is scoped to the single resource the workload touches, never to the subscription or the
// resource group: a compromised container should not be able to read the other environments.

@description('Object id of the managed identity that runs the workload.')
param principalId string

@description('Service Bus namespace and topic the publisher sends to.')
param serviceBusNamespaceName string
param serviceBusTopicName string

@description('Key Vault that holds the Salesforce secret.')
param keyVaultId string

@description('Container registry that holds the application image.')
param containerRegistryId string

@description('Application Insights instance the workload publishes metrics to.')
param applicationInsightsId string

// Well-known role definition ids. They are stable across tenants and are documented by Microsoft.
// Not secrets: a GUID that looks like an API key to a scanner. Each line carries gitleaks:allow.
var serviceBusDataSender = '69a216fc-b8fb-44d8-bc22-1f3c2cd27a39' // gitleaks:allow
var keyVaultSecretsUser = '4633458b-17de-408a-b874-0445c86b69e6' // gitleaks:allow
var acrPull = '7f951dda-4ed3-4680-a7ca-43fe172d538d' // gitleaks:allow
var monitoringMetricsPublisher = '3913510d-42f4-4e42-8a64-420c390055eb' // gitleaks:allow

resource sendToTopic 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(topic.id, principalId, serviceBusDataSender)
  scope: topic
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', serviceBusDataSender)
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}

resource readSecrets 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(keyVaultId, principalId, keyVaultSecretsUser)
  scope: vault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', keyVaultSecretsUser)
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}

resource pullImage 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(containerRegistryId, principalId, acrPull)
  scope: registry
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', acrPull)
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}

resource publishMetrics 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(applicationInsightsId, principalId, monitoringMetricsPublisher)
  scope: insights
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', monitoringMetricsPublisher)
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}

// The scopes above are declared here as existing resources: a role assignment needs a scope, and the
// scope is a resource that already exists by the time this module runs.
resource serviceBus 'Microsoft.ServiceBus/namespaces@2022-10-01-preview' existing = {
  name: serviceBusNamespaceName
}

resource topic 'Microsoft.ServiceBus/namespaces/topics@2022-10-01-preview' existing = {
  parent: serviceBus
  name: serviceBusTopicName
}

resource vault 'Microsoft.KeyVault/vaults@2023-07-01' existing = {
  name: last(split(keyVaultId, '/'))
}

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' existing = {
  name: last(split(containerRegistryId, '/'))
}

resource insights 'Microsoft.Insights/components@2020-02-02' existing = {
  name: last(split(applicationInsightsId, '/'))
}
