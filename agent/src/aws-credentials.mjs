import { XMLParser } from "./xml-parser.mjs"

const AZURE_LOGIN_URL = "https://login.microsoftonline.com"
const AWS_STS_URL = "https://sts.amazonaws.com"

export async function resolveAwsCredentials(llmHubConfig) {
  const { clientId, clientSecret, tenantId, roleArn, resource } = llmHubConfig

  console.log("[aws-credentials] Acquiring Azure AD token for LLM Hub")
  const azureToken = await acquireAzureToken(clientId, clientSecret, tenantId, resource)

  console.log("[aws-credentials] Exchanging Azure token for AWS credentials via STS")
  const credentials = await assumeRoleWithWebIdentity(roleArn, azureToken)

  console.log(`[aws-credentials] AWS credentials acquired, expires ${credentials.expiration}`)
  return credentials
}

async function acquireAzureToken(clientId, clientSecret, tenantId, resource) {
  const url = `${AZURE_LOGIN_URL}/${tenantId}/oauth2/v2.0/token`
  const body = new URLSearchParams({
    grant_type: "client_credentials",
    client_id: clientId,
    client_secret: clientSecret,
    scope: `${resource}/.default`
  })

  const response = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: body.toString(),
    signal: AbortSignal.timeout(15_000)
  })

  if (!response.ok) {
    const text = await response.text()
    throw new Error(`Azure AD token request failed: HTTP ${response.status} — ${text}`)
  }

  const json = await response.json()
  return json.access_token
}

async function assumeRoleWithWebIdentity(roleArn, webIdentityToken) {
  const params = new URLSearchParams({
    Action: "AssumeRoleWithWebIdentity",
    Version: "2011-06-15",
    RoleArn: roleArn,
    RoleSessionName: "PANDA_Agent_Session",
    WebIdentityToken: webIdentityToken,
    DurationSeconds: "3600"
  })

  const response = await fetch(`${AWS_STS_URL}/?${params.toString()}`, {
    method: "GET",
    headers: { "accept": "application/xml" },
    signal: AbortSignal.timeout(15_000)
  })

  if (!response.ok) {
    const text = await response.text()
    throw new Error(`AWS STS AssumeRoleWithWebIdentity failed: HTTP ${response.status} — ${text}`)
  }

  const xml = await response.text()
  const parser = new XMLParser(xml)

  return {
    accessKeyId: parser.extractText("AccessKeyId"),
    secretAccessKey: parser.extractText("SecretAccessKey"),
    sessionToken: parser.extractText("SessionToken"),
    expiration: parser.extractText("Expiration")
  }
}
