// Service Bus Premium with Private Link: one topic and independent subscriptions.
// The consumers behind those subscriptions are out of scope (FR6); what matters here is that one
// consumer being down or slow cannot affect the other, which is what independent subscriptions buy.

@description('Prefix shared by every resource in this deployment.')
param namePrefix string

@description('Environment name: dev, test or prod.')
param environment string

@description('Azure region for the namespace.')
param location string

param messagingUnits int
param privateEndpointSubnetId string
param privateDnsZoneId string

@description('Consumer subscriptions created on the canonical-event topic.')
param subscriptionNames array

@description('How many deliveries before a message is dead-lettered.')
param maxDeliveryCount int

@description('Lock duration per delivery, ISO 8601. A payment event is processed in seconds.')
param lockDuration string

@description('Message time to live, ISO 8601. Long enough for a consumer outage, short enough to bound storage.')
param timeToLive string

param tags object

resource namespace 'Microsoft.ServiceBus/namespaces@2022-10-01-preview' = {
  name: '${namePrefix}-${environment}-sb'
  location: location
  tags: tags
  sku: {
    name: 'Premium'
    tier: 'Premium'
    capacity: messagingUnits
  }
  properties: {
    // Local auth is disabled: every sender and receiver authenticates with a managed identity.
    disableLocalAuth: true
    minimumTlsVersion: '1.2'
    publicNetworkAccess: 'Disabled'
    zoneRedundant: false
  }
}

resource endpoint 'Microsoft.Network/privateEndpoints@2023-09-01' = {
  name: '${namespace.name}-pe'
  location: location
  tags: tags
  properties: {
    subnet: {
      id: privateEndpointSubnetId
    }
    privateLinkServiceConnections: [
      {
        name: 'servicebus'
        properties: {
          privateLinkServiceId: namespace.id
          groupIds: [
            'namespace'
          ]
        }
      }
    ]
  }
}

resource endpointDns 'Microsoft.Network/privateEndpoints/privateDnsZoneGroups@2023-09-01' = {
  parent: endpoint
  name: 'default'
  properties: {
    privateDnsZoneConfigs: [
      {
        name: 'servicebus'
        properties: {
          privateDnsZoneId: privateDnsZoneId
        }
      }
    ]
  }
}

resource topic 'Microsoft.ServiceBus/namespaces/topics@2022-10-01-preview' = {
  parent: namespace
  name: 'canonical-events'
  properties: {
    maxSizeInMegabytes: 5120
    requiresDuplicateDetection: true
    // The longest window Service Bus allows, which is why the design calls broker deduplication an
    // optimisation and not a guarantee: consumers still deduplicate on (provider, event_id).
    duplicateDetectionHistoryTimeWindow: 'P7D'
    defaultMessageTimeToLive: timeToLive
    status: 'Active'
  }
}

resource subscriptions 'Microsoft.ServiceBus/namespaces/topics/subscriptions@2022-10-01-preview' = [
  for name in subscriptionNames: {
    parent: topic
    name: name
    properties: {
      lockDuration: lockDuration
      maxDeliveryCount: maxDeliveryCount
      deadLetteringOnMessageExpiration: true
      // Dead lettering on filter evaluation errors is on: a message nobody can route must not vanish.
      deadLetteringOnFilterEvaluationExceptions: true
      defaultMessageTimeToLive: timeToLive
      requiresSession: false
    }
  }
]

output namespaceName string = namespace.name
output topicName string = topic.name
output fullyQualifiedNamespace string = '${namespace.name}.servicebus.windows.net'
