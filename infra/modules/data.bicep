// PostgreSQL Flexible Server: the inbox, the payment state and the outbox.
// Entra-only authentication. A separate privileged group bootstraps SQL roles from the private
// network; the application identity must never be a PostgreSQL server administrator.

@description('Prefix shared by every resource in this deployment.')
param namePrefix string

@description('Environment name: dev, test or prod.')
param environment string

@description('Azure region for the server.')
param location string

@description('VM size and matching PostgreSQL Flexible Server tier.')
param skuName string
param skuTier string

@description('Initial storage in GB.')
param storageSizeGb int

@description('Backup retention in days. Seven is the cheapest point that still allows a same-week restore.')
param backupRetentionDays int

@description('Object ID of the separate Entra administrator group.')
param administratorObjectId string

@description('Display name of the Entra administrator group.')
param administratorLogin string

@description('Private endpoint subnet.')
param privateEndpointSubnetId string

@description('Private DNS zone for privatelink.postgres.database.azure.com.')
param privateDnsZoneId string

param tags object

resource server 'Microsoft.DBforPostgreSQL/flexibleServers@2023-12-01-preview' = {
  name: '${namePrefix}-${environment}-pg'
  location: location
  tags: tags
  sku: {
    name: skuName
    tier: skuTier
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
      publicNetworkAccess: 'Disabled'
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

// Application access is a separate SQL bootstrap step, not an automatic grant of server admin.
resource administrator 'Microsoft.DBforPostgreSQL/flexibleServers/administrators@2023-12-01-preview' = {
  parent: server
  name: administratorObjectId
  properties: {
    principalType: 'Group'
    principalName: administratorLogin
    tenantId: tenant().tenantId
  }
}

resource endpoint 'Microsoft.Network/privateEndpoints@2023-09-01' = {
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

resource endpointDns 'Microsoft.Network/privateEndpoints/privateDnsZoneGroups@2023-09-01' = {
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
