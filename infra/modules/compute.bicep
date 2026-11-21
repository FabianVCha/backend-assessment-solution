// Container Apps: the intake and both worker roles in one deployment, as ADR-0002 allows.
// Consumption plan, internal ingress only (the API Manager is the only way in), one replica minimum
// so a provider webhook is never answered by a cold start.

@description('Prefix shared by every resource in this deployment.')
param namePrefix string

@description('Environment name: dev, test or prod.')
param environment string

@description('Azure region for the environment and the app.')
param location string

@description('Container image. The pipeline replaces the placeholder with the image it built.')
param image string

@description('Subnet delegated to the Container Apps environment.')
param containerAppsSubnetId string

@description('Log Analytics workspace customer id.')
param logAnalyticsCustomerId string

@description('Log Analytics workspace shared key, consumed only by the environment.')
@secure()
param logAnalyticsSharedKey string

@description('Managed identity the app runs as.')
param identityId string

@description('Registry login server.')
param registryLoginServer string

@description('Service Bus fully qualified namespace.')
param serviceBusNamespace string

@description('Topic the publisher sends to.')
param serviceBusTopic string

@description('PostgreSQL host and database.')
param postgresHost string
param postgresDatabase string

@description('Key Vault URI, read through the identity.')
param keyVaultUri string

@description('Application Insights connection string.')
param applicationInsightsConnectionString string

@description('Replica bounds. One minimum keeps the webhook warm; the maximum bounds the month-end burst cost.')
param minReplicas int
param maxReplicas int

@description('CPU and memory per replica. The workload is I/O bound: the signature and the mapping are microseconds.')
param cpuCores string
param memorySize string

param tags object

resource managedEnvironment 'Microsoft.App/managedEnvironments@2023-05-01' = {
  name: '${namePrefix}-${environment}-cae'
  location: location
  tags: tags
  properties: {
    // Internal: the environment has no public endpoint at all. APIM reaches it over the VNet.
    vnetConfiguration: {
      infrastructureSubnetId: containerAppsSubnetId
      internal: true
    }
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logAnalyticsCustomerId
        sharedKey: logAnalyticsSharedKey
      }
    }
    zoneRedundant: false
  }
}

resource app 'Microsoft.App/containerApps@2023-05-01' = {
  name: '${namePrefix}-${environment}-intake'
  location: location
  tags: tags
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identityId}': {}
    }
  }
  properties: {
    managedEnvironmentId: managedEnvironment.id
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: false
        targetPort: 8080
        transport: 'http'
        allowInsecure: false
      }
      registries: [
        {
          server: registryLoginServer
          identity: identityId
        }
      ]
    }
    template: {
      scale: {
        minReplicas: minReplicas
        maxReplicas: maxReplicas
        rules: [
          {
            name: 'http-concurrency'
            http: {
              metadata: {
                concurrentRequests: '50'
              }
            }
          }
        ]
      }
      containers: [
        {
          name: 'intake'
          image: image
          resources: {
            cpu: json(cpuCores)
            memory: memorySize
          }
          env: [
            {
              name: 'SERVICE_BUS_FQDN'
              value: serviceBusNamespace
            }
            {
              name: 'SERVICE_BUS_TOPIC'
              value: serviceBusTopic
            }
            {
              name: 'POSTGRES_HOST'
              value: postgresHost
            }
            {
              name: 'POSTGRES_DATABASE'
              value: postgresDatabase
            }
            {
              name: 'KEY_VAULT_URI'
              value: keyVaultUri
            }
            {
              name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
              value: applicationInsightsConnectionString
            }
          ]
        }
      ]
    }
  }
}

output appId string = app.id
output appName string = app.name
output environmentId string = managedEnvironment.id
