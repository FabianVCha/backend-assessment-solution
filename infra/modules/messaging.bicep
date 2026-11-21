// Service Bus Standard: one topic, one subscription per consumer, dead letter enabled on each.
// The consumers behind those subscriptions are out of scope (FR6); what matters here is that one
// consumer being down or slow cannot affect the other, which is what independent subscriptions buy.

@description('Prefix shared by every resource in this deployment.')
param namePrefix string

@description('Environment name: dev, test or prod.')
param environment string

@description('Azure region for the namespace.')
param location string

@description('Premium is offered as a parameter for a future throughput review; Part A prices Standard.')
@allowed([
  'Standard'
  'Premium'
])
param skuName string

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
    name: skuName
    tier: skuName
    // Standard has no messaging units; Premium would need them.
    capacity: skuName == 'Premium' ? 1 : 0
  }
  properties: {
    // Local auth is disabled: every sender and receiver authenticates with a managed identity.
    disableLocalAuth: true
    minimumTlsVersion: '1.2'
    publicNetworkAccess: 'Disabled'
    zoneRedundant: false
  }
}

resource topic 'Microsoft.ServiceBus/namespaces/topics@2022-10-01-preview' = {
  parent: namespace
  name: 'canonical-events'
  properties: {
    maxSizeInMegabytes: 1024
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

output namespaceId string = namespace.id
output topicId string = topic.id
output topicName string = topic.name
output fullyQualifiedNamespace string = '${namespace.name}.servicebus.windows.net'
