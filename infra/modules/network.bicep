// The private network: one VNet, one subnet for the Container Apps environment and one for private
// endpoints, plus the three private DNS zones the endpoints need to resolve to private addresses.
//
// The design puts the intake behind the API Manager and states it is not reachable from the internet
// (SOLUTION_DESIGN.md §1.2), so the endpoints are part of the design and not an extra.

@description('Prefix shared by every resource in this deployment.')
param namePrefix string

@description('Environment name: dev, test or prod.')
param environment string

@description('Azure region for the network.')
param location string

@description('Address space for the VNet. /16 leaves room for a second region without re-addressing.')
param vnetAddressPrefix string

@description('Dedicated, delegated subnet for the workload-profiles Container Apps environment.')
param containerAppsSubnetPrefix string

@description('Private endpoint subnet.')
param privateEndpointsSubnetPrefix string

param tags object

resource vnet 'Microsoft.Network/virtualNetworks@2023-09-01' = {
  name: '${namePrefix}-${environment}-vnet'
  location: location
  tags: tags
  properties: {
    addressSpace: {
      addressPrefixes: [
        vnetAddressPrefix
      ]
    }
    subnets: [
      {
        name: 'snet-containerapps'
        properties: {
          addressPrefix: containerAppsSubnetPrefix
          delegations: [
            {
              name: 'containerapps'
              properties: {
                serviceName: 'Microsoft.App/environments'
              }
            }
          ]
        }
      }
      {
        name: 'snet-private-endpoints'
        properties: {
          addressPrefix: privateEndpointsSubnetPrefix
          privateEndpointNetworkPolicies: 'Disabled'
        }
      }
    ]
  }
}

resource containerAppsSubnet 'Microsoft.Network/virtualNetworks/subnets@2023-09-01' = {
  parent: vnet
  name: 'snet-containerapps'
}

resource privateEndpointsSubnet 'Microsoft.Network/virtualNetworks/subnets@2023-09-01' = {
  parent: vnet
  name: 'snet-private-endpoints'
}

resource postgresZone 'Microsoft.Network/privateDnsZones@2020-06-01' = {
  name: 'privatelink.postgres.database.azure.com'
  location: 'global'
  tags: tags
}

resource serviceBusZone 'Microsoft.Network/privateDnsZones@2020-06-01' = {
  name: 'privatelink.servicebus.windows.net'
  location: 'global'
  tags: tags
}

resource vaultZone 'Microsoft.Network/privateDnsZones@2020-06-01' = {
  name: 'privatelink.vaultcore.azure.net'
  location: 'global'
  tags: tags
}

resource postgresLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2020-06-01' = {
  parent: postgresZone
  name: '${vnet.name}-link'
  location: 'global'
  properties: {
    virtualNetwork: {
      id: vnet.id
    }
    registrationEnabled: false
  }
}

resource serviceBusLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2020-06-01' = {
  parent: serviceBusZone
  name: '${vnet.name}-link'
  location: 'global'
  properties: {
    virtualNetwork: {
      id: vnet.id
    }
    registrationEnabled: false
  }
}

resource vaultLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2020-06-01' = {
  parent: vaultZone
  name: '${vnet.name}-link'
  location: 'global'
  properties: {
    virtualNetwork: {
      id: vnet.id
    }
    registrationEnabled: false
  }
}

output vnetId string = vnet.id
output containerAppsSubnetId string = containerAppsSubnet.id
output privateEndpointsSubnetId string = privateEndpointsSubnet.id
output postgresDnsZoneId string = postgresZone.id
output serviceBusDnsZoneId string = serviceBusZone.id
output vaultDnsZoneId string = vaultZone.id
