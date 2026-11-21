// Key Vault for the Salesforce client secret and any future provider secret.
// RBAC authorization, no access policies: the identity model is the same one the rest of the
// deployment uses, and it is reviewed in one place.

@description('Prefix shared by every resource in this deployment.')
param namePrefix string

@description('Environment name: dev, test or prod.')
param environment string

@description('Azure region for the vault.')
param location string

@description('Soft delete cannot be turned off; this is how long a deleted secret stays recoverable.')
param softDeleteRetentionInDays int

@description('Purge protection is on in prod only: it makes a mistaken vault deletion irreversible for the retention window.')
param purgeProtectionEnabled bool

@description('Private endpoint subnet, or empty to keep the default public endpoint with firewall rules.')
param privateEndpointSubnetId string

@description('Private DNS zone for privatelink.vaultcore.azure.net, or empty to skip the endpoint.')
param privateDnsZoneId string

param tags object

resource vault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: '${namePrefix}-${environment}-kv'
  location: location
  tags: tags
  properties: {
    sku: {
      family: 'A'
      name: 'standard'
    }
    tenantId: tenant().tenantId
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: softDeleteRetentionInDays
    enablePurgeProtection: purgeProtectionEnabled
    publicNetworkAccess: empty(privateEndpointSubnetId) ? 'Enabled' : 'Disabled'
    networkAcls: {
      defaultAction: empty(privateEndpointSubnetId) ? 'Allow' : 'Deny'
      bypass: 'AzureServices'
    }
  }
}

// Only created when the network module hands over a subnet and a zone: dev can run without a private
// endpoint, prod cannot.
resource endpoint 'Microsoft.Network/privateEndpoints@2023-09-01' = if (!empty(privateEndpointSubnetId) && !empty(privateDnsZoneId)) {
  name: '${vault.name}-pe'
  location: location
  tags: tags
  properties: {
    subnet: {
      id: privateEndpointSubnetId
    }
    privateLinkServiceConnections: [
      {
        name: 'vault'
        properties: {
          privateLinkServiceId: vault.id
          groupIds: [
            'vault'
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
        name: 'vault'
        properties: {
          privateDnsZoneId: privateDnsZoneId
        }
      }
    ]
  }
}

output id string = vault.id
output uri string = vault.properties.vaultUri
output name string = vault.name
