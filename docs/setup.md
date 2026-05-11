# Setup Guide

## Prerequisites

- Docker and Docker Compose
- Java 21+ and Gradle (for local development only)
- A Jira Cloud instance with API access
- A GitHub account with repo access (PAT or GitHub App)
- An AI model provider API key (OpenAI, Anthropic, Gemini, or GitHub Copilot)

## 1. Clone and configure

```bash
git clone https://github.com/Naofel-eal/PANDA.git
cd PANDA
cp .env.example .env
```

## 2. Jira setup

1. Create an API token at https://id.atlassian.com/manage-profile/security/api-tokens
2. Create a dedicated Jira user (service account) for PANDA, or use your own
3. Find the account ID: go to your Jira profile URL — the ID is the last segment (e.g. `5f1234abcdef567890`)
4. Ensure your Jira project workflow has these statuses: To Do, In Progress, Blocked, To Review, To Validate, Done

Fill in `.env`:

```env
JIRA_BASE_URL=https://your-instance.atlassian.net
JIRA_API_TOKEN=your-api-token
JIRA_PROJECT_KEY=YOURPROJECT
JIRA_SERVICE_ACCOUNT_ID=your-jira-account-id
```

**How it works:** PANDA polls for tickets assigned to the service account in "To Do" status. To trigger PANDA, assign a ticket to the service account.

## 3. GitHub setup

### Option A: Personal Access Token (simplest)

1. Create a fine-grained PAT at https://github.com/settings/tokens with `contents: write` and `pull_requests: write` on target repos

```env
GITHUB_TOKEN=ghp_your_token
```

### Option B: GitHub App (recommended for orgs)

1. Create a GitHub App with Repository permissions: Contents (Read & Write), Pull Requests (Read & Write)
2. Install the App on your org/repos
3. Download the private key PEM file

```env
GITHUB_APP_ID=123456
GITHUB_APP_PRIVATE_KEY=-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END RSA PRIVATE KEY-----
GITHUB_APP_INSTALLATION_ID=78901234
```

### Repository configuration

Add each repository PANDA should manage:

```env
PANDA_GITHUB_REPOSITORIES_0_NAME=your-org/your-repo
PANDA_GITHUB_REPOSITORIES_0_BASE_BRANCH=main
PANDA_GITHUB_REPOSITORIES_1_NAME=your-org/another-repo
PANDA_GITHUB_REPOSITORIES_1_BASE_BRANCH=main
```

## 4. AI model provider

Pick one provider and set the matching model names.

### GitHub Copilot (free with GitHub Pro)

```env
OPENCODE_MODEL=github-copilot/claude-sonnet-4.6
OPENCODE_SMALL_MODEL=github-copilot/gpt-4o-mini
COPILOT_GITHUB_TOKEN=your-copilot-token
```

### Anthropic

```env
OPENCODE_MODEL=anthropic/claude-sonnet-4-20250514
OPENCODE_SMALL_MODEL=anthropic/claude-haiku-3-5-20241022
ANTHROPIC_API_KEY=sk-ant-...
```

### OpenAI

```env
OPENCODE_MODEL=openai/gpt-4o
OPENCODE_SMALL_MODEL=openai/gpt-4o-mini
OPENAI_API_KEY=sk-...
```

### Gemini

```env
OPENCODE_MODEL=gemini/gemini-2.5-pro
OPENCODE_SMALL_MODEL=gemini/gemini-2.0-flash
GEMINI_API_KEY=...
```

## 5. Run

```bash
docker compose up --build
```

The orchestrator starts on `http://localhost:8080`.

Verify it's running:
- Health: `http://localhost:8080/q/health`
- Swagger: `http://localhost:8080/q/swagger-ui`
- Logs: `docker compose logs -f orchestrator`

## 6. Test it

1. Create a Jira ticket in your project with a clear title and description
2. Assign it to the PANDA service account
3. Set status to "To Do"
4. Watch the logs — PANDA should pick it up within 1 minute

## Local development (without Docker)

```bash
# Start the orchestrator
./gradlew quarkusDev

# Start the agent runtime (in another terminal)
cd agent
npm install
node src/server.mjs
```

Environment variables must be set in your shell or in a `.env` file (Quarkus reads `.env` automatically in dev mode).

## Troubleshooting

| Symptom | Check |
|---------|-------|
| "Skipping Jira ticket polling" in logs | `JIRA_SERVICE_ACCOUNT_ID` is missing or empty |
| Tickets not picked up | Verify ticket is assigned to the service account and in "To Do" status |
| GitHub push fails | Verify token has `contents: write` permission on the repo |
| Agent times out | Increase `AGENT_MAX_RUN_DURATION_MINUTES` (default 15) |
| "Connection failure" in logs | Check network connectivity to Jira/GitHub APIs |
