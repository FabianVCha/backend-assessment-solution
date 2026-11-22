// A nested deployment makes the environment's assigned DNS name available at the start of this module.
param zoneName string
param privateIp string
param vnetId string
param tags object

resource zone 'Microsoft.Network/privateDnsZones@2020-06-01' = {
  name: zoneName
  location: 'global'
  tags: tags
}

resource vnetLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2020-06-01' = {
  parent: zone
  name: 'application-vnet'
  location: 'global'
  properties: {
    virtualNetwork: {
      id: vnetId
    }
    registrationEnabled: false
  }
}

resource wildcard 'Microsoft.Network/privateDnsZones/A@2020-06-01' = {
  parent: zone
  name: '*'
  properties: {
    ttl: 300
    aRecords: [
      {
        ipv4Address: privateIp
      }
    ]
  }
}
