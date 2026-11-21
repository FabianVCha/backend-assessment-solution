// PostgreSQL Flexible Server: the inbox, the payment state and the outbox.
// Entra-only authentication on purpose: no database password exists to store, rotate or leak, and the
// intake reaches it with the same managed identity it uses everywhere else.

@description('Prefix shared by every resource in this deployment.')
param namePrefix string

@description('Environment name: dev, test or prod.')
param environment string

@description('Azure region for the server.')
param location string

@description('VM size. B1MS is the one priced in ADR-0002 and the one the burst analysis assumes.')
param skuName string

@description('Storage in GB. 40 GB covers the 13-month hot window with 30% overhead (design §6.4).')
param storageSizeGb int

@description('Backup retention in days. Seven is the cheapest point that still allows a same-week restore.')
param backupRetentionDays int

@description('Object id of the managed identity that becomes the Entra administrator.')
param administratorObjectId string

@description('Display name of that identity, used as the Entra admin login.')
param administratorLogin string

@description('Private endpoint subnet, or empty for a public server restricted by firewall rules.')
param privateEndpointSubnetId string

@description('Private DNS zone for privatelink.postgres.database.azure.com, or empty to skip the endpoint.')
param privateDnsZoneId string

param tags object

resource server 'Microsoft.DBforPostgreSQL/flexibleServers@2023-12-01-preview' = {
  name: '${namePrefix}-${environment}-pg'
  location: location
  tags: tags
  sku: {
    name: skuName
    tier: 'Burstable'
  }
  properties: {
    version: '16'
    // No password authentication: the only way in is an Entra token from the managed identity.
    authConfig: {
      activeDirectoryAuth: 'Enabled'
      passwordAuth: 'Disabled'
      tenantId: tenant().tenantId
    }
    storage: {
      storageSizeGB: storageSizeGb
      autoGrow: 'Enabled'
    }
    backup: {
      backupRetentionDays: backupRetentionDays
      geoRedundantBackup: 'Disabled'
    }
    highAvailability: {
      // Single instance, as ADR-0002 states: zone redundancy is a cost the design does not take.
      mode: 'Disabled'
    }
    network: {
      publicNetworkAccess: empty(privateEndpointSubnetId) ? 'Enabled' : 'Disabled'
    }
  }
}

resource database 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2023-12-01-preview' = {
  parent: server
  name: 'payments'
  properties: {
    charset: 'UTF8'
    collation: 'en_US.utf8'
  }
}

// The intake identity is the only Entra administrator. Application access is granted with SQL roles
// created by the deployment, not by handing out server admin.
resource administrator 'Microsoft.DBforPostgreSQL/flexibleServers/administrators@2023-12-01-preview' = {
  parent: server
  name: administratorObjectId
  properties: {
    principalType: 'ServicePrincipal'
    principalName: administratorLogin
    tenantId: tenant().tenantId
  }
}

resource endpoint 'Microsoft.Network/privateEndpoints@2023-09-01' = if (!empty(privateEndpointSubnetId) && !empty(privateDnsZoneId)) {
  name: '${server.name}-pe'
  location: location
  tags: tags
  properties: {
    subnet: {
      id: privateEndpointSubnetId
    }
    privateLinkServiceConnections: [
      {
        name: 'postgres'
        properties: {
          privateLinkServiceId: server.id
          groupIds: [
            'postgresqlServer'
          ]
        }
      }
    ]
  }
}

resource endpointDns 'Microsoft.Network/privateEndpoints/privateDnsZoneGroups@2023-09-01' = if (!empty(privateEndpointSubnetId) && !empty(privateDnsZoneId)) {
  parent: endpoint
  name: 'default'
  properties: {
    privateDnsZoneConfigs: [
      {
        name: 'postgres'
        properties: {
          privateDnsZoneId: privateDnsZoneId
        }
      }
    ]
  }
}

output id string = server.id
output fqdn string = server.properties.fullyQualifiedDomainName
output databaseName string = database.name
