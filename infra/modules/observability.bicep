// Log Analytics plus the Application Insights instance every role writes to.
// Alerts and saved queries live in Part D; this module only provisions the sink and its retention.

@description('Prefix shared by every resource in this deployment.')
param namePrefix string

@description('Environment name: dev, test or prod.')
param environment string

@description('Azure region for every resource.')
param location string

@description('Retention in days for the workspace. 30 is the free-then-cheap default; prod keeps 90.')
param retentionInDays int

@description('Daily cap in GB, so a runaway log line cannot produce a surprise bill.')
param dailyCapInGb int

param tags object

resource workspace 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: '${namePrefix}-${environment}-law'
  location: location
  tags: tags
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: retentionInDays
    features: {
      // Keeps the workspace inside Well-Architected's cost guidance without deleting audit data early.
      enableLogAccessUsingOnlyResourcePermissions: true
    }
    workspaceCapping: {
      dailyQuotaGb: dailyCapInGb
    }
  }
}

resource insights 'Microsoft.Insights/components@2020-02-02' = {
  name: '${namePrefix}-${environment}-appi'
  location: location
  tags: tags
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: workspace.id
    // Sampling keeps the ingestion bill proportional to traffic instead of to log verbosity.
    SamplingPercentage: 25
  }
}

output workspaceId string = workspace.id
output workspaceCustomerId string = workspace.properties.customerId
@secure()
output workspaceSharedKey string = workspace.listKeys().primarySharedKey
output applicationInsightsId string = insights.id
output applicationInsightsConnectionString string = insights.properties.ConnectionString
