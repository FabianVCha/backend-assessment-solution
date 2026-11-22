// Container Apps: the intake and both worker roles in one deployment, as ADR-0002 allows.
// Internal workload-profiles environment with a Consumption profile. The app is reachable from
// routed private networks, not the internet. API Manager connectivity is a platform prerequisite.

@description('Prefix shared by every resource in this deployment.')
param namePrefix string

@description('Environment name: dev, test or prod.')
param environment string

@description('Azure region for the environment and the app.')
param location string

@description('Approved release image pinned by digest. Not the assessment demo.')
param image string

param deployApplication bool
param vnetId string

@description('Subnet delegated to the Container Apps environment.')
param containerAppsSubnetId string

@description('Log Analytics workspace customer id.')
param logAnalyticsCustomerId string

@description('Log Analytics workspace shared key, consumed only by the environment.')
@secure()
param logAnalyticsSharedKey string

@description('Managed identity the app runs as.')
param identityId string
param identityClientId string

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

@description('Starting CPU and memory per replica; validate under load.')
param cpuCores string
param memorySize string

param tags object

resource managedEnvironment 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: '${namePrefix}-${environment}-cae'
  location: location
  tags: tags
  properties: {
    vnetConfiguration: {
      infrastructureSubnetId: containerAppsSubnetId
      internal: true
    }
    workloadProfiles: [
      {
        name: 'Consumption'
        workloadProfileType: 'Consumption'
      }
    ]
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

module ingressDns 'ingress-dns.bicep' = {
  name: 'ingress-dns'
  params: {
    zoneName: managedEnvironment.properties.defaultDomain
    privateIp: managedEnvironment.properties.staticIp
    vnetId: vnetId
    tags: tags
  }
}

resource app 'Microsoft.App/containerApps@2024-03-01' = if (deployApplication) {
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
    environmentId: managedEnvironment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        // "External" is relative to the environment; this publishes on the internal load balancer.
        external: true
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
          probes: [
            {
              type: 'Startup'
              httpGet: {
                path: '/healthz'
                port: 8080
              }
              periodSeconds: 5
              failureThreshold: 24
            }
            {
              type: 'Readiness'
              httpGet: {
                path: '/readyz'
                port: 8080
              }
              periodSeconds: 5
              failureThreshold: 3
            }
            {
              type: 'Liveness'
              httpGet: {
                path: '/healthz'
                port: 8080
              }
              periodSeconds: 15
              failureThreshold: 3
            }
          ]
          env: [
            {
              name: 'AZURE_CLIENT_ID'
              value: identityClientId
            }
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

output appId string = deployApplication ? app!.id : ''
output appName string = deployApplication ? app!.name : ''
output appFqdn string = deployApplication ? app!.properties.configuration.ingress.fqdn : ''
output environmentPrivateIp string = managedEnvironment.properties.staticIp
output environmentDnsZone string = managedEnvironment.properties.defaultDomain
