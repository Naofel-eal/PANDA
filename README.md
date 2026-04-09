# DevFlow

DevFlow is an autonomous coding orchestrator for Jira and GitHub. Drop a ticket in Jira, and an AI agent ([OpenCode](https://opencode.ai)) picks it up, reads the checked-out repositories, implements the change, opens or reuses pull requests, and follows up on review and validation feedback.

There is no database and no durable queue. DevFlow re-discovers Jira and GitHub state on every poll cycle and only keeps the currently active workflow in memory.

## What the agent actually does

DevFlow works in explicit workflow phases:

1. `INFORMATION_COLLECTION`
   The agent explores the local workspace in read-only mode, validates its understanding of the ticket, and asks for clarification only when it is genuinely blocked.
2. `IMPLEMENTATION`
   The agent modifies the code, runs relevant validation commands, and signals that the local work is complete. The orchestrator then creates branches, commits, pushes, and opens or reuses pull requests.
3. `TECHNICAL_VALIDATION`
   New human comments on an open DevFlow pull request trigger a focused run on the reviewed branch so the agent can address review feedback.
4. `BUSINESS_VALIDATION`
   New Jira feedback on a ticket in "To Validate" triggers a follow-up run so business feedback can be implemented and published back to review.

The orchestrator also handles the surrounding workflow:

- Ineligible "To Do" tickets are moved to "Blocked" with a Jira comment describing what is missing.
- Merged DevFlow pull requests automatically move the ticket from "To Review" to "To Validate".
- Review feedback is deduplicated by comparing comment timestamps with the latest commit on the branch.
- Blocked and validation tickets can resume either from a new Jira comment or from a ticket update that happened after the last DevFlow comment.

## Quick start

```bash
cp .env.example .env
docker compose up --build
```

The orchestrator listens on `http://localhost:8080`. Useful endpoints:

- Swagger UI: `http://localhost:8080/q/swagger-ui`
- Health checks: `http://localhost:8080/q/health`

## Configuration

All configuration lives in `.env`. See [`.env.example`](.env.example) for the canonical list.

### Jira

| Variable | Description | Default |
|----------|-------------|---------|
| `JIRA_BASE_URL` | Atlassian instance URL | `https://example.atlassian.net` |
| `JIRA_USER_EMAIL` | Jira account email | - |
| `JIRA_API_TOKEN` | Jira API token | - |
| `JIRA_EPIC_KEY` | Epic key to poll (for example `SCRUM-2`) | - |
| `JIRA_TODO_STATUS` | Intake status for new tickets | `To Do` |
| `JIRA_IN_PROGRESS_STATUS` | Status used when an agent run starts | `In Progress` |
| `JIRA_BLOCKED_STATUS` | Status used when the ticket needs clarification or publication fails | `Blocked` |
| `JIRA_REVIEW_STATUS` | Status used when pull requests are ready for review | `To Review` |
| `JIRA_VALIDATE_STATUS` | Status used after pull requests are merged | `To Validate` |
| `JIRA_DONE_STATUS` | Final status if your Jira workflow needs it | `Done` |
| `JIRA_POLL_INTERVAL_MINUTES` | Jira poll frequency | `1` |
| `JIRA_POLL_MAX_RESULTS` | Jira page size per poll | `50` |

### GitHub

| Variable | Description | Default |
|----------|-------------|---------|
| `GITHUB_API_URL` | GitHub API base URL | `https://api.github.com` |
| `GITHUB_TOKEN` | GitHub token used by the orchestrator for clone, fetch, push, and PR APIs | - |
| `GITHUB_DEFAULT_BASE_BRANCH` | Fallback base branch when a repository does not override it | `develop` |
| `GITHUB_POLL_INTERVAL_MINUTES` | GitHub poll frequency | `1` |
| `GITHUB_COMMIT_USER_NAME` | Git author name for orchestrator-created commits | `Devflow Bot` |
| `GITHUB_COMMIT_USER_EMAIL` | Git author email for orchestrator-created commits | `devflow@example.local` |
| `DEVFLOW_GITHUB_REPOSITORIES_0_NAME` | Repository slug (for example `my-org/api`) | - |
| `DEVFLOW_GITHUB_REPOSITORIES_0_BASE_BRANCH` | Base branch for that repository | `main` |

Add more repositories with indices `1`, `2`, and so on.

### Agent runtime and models

DevFlow sends model-provider credentials to the agent runtime per run. Configure the runtime plus the provider that matches `OPENCODE_MODEL` and `OPENCODE_SMALL_MODEL`.

| Variable | Description | Default |
|----------|-------------|---------|
| `AGENT_MAX_RUN_DURATION_MINUTES` | Hard timeout enforced by the agent runtime | `15` |
| `AGENT_STALE_TIMEOUT_BUFFER_MINUTES` | Extra delay before the orchestrator cancels a stale run as backup | `5` |
| `OPENCODE_MODEL` | Primary model (for example `github-copilot/claude-sonnet-4.6`) | - |
| `OPENCODE_SMALL_MODEL` | Smaller model for lighter tasks | - |
| `COPILOT_GITHUB_TOKEN` | GitHub Copilot token for `github-copilot/*` models | - |
| `OPENAI_API_KEY` | OpenAI API key for `openai/*` models | - |
| `ANTHROPIC_API_KEY` | Anthropic API key for `anthropic/*` models | - |
| `GEMINI_API_KEY` | Gemini API key for `gemini/*` models | - |

## Architecture overview

```
┌──────────────┐   poll    ┌───────┐
│ Orchestrator │ ───────── │  Jira │
│  (Quarkus)   │ ───────── │GitHub │
│              │   poll    └───────┘
│              │
│              │   HTTP    ┌───────┐
│              │ ────────> │ Agent │
│              │ <──────── │(OpenCode)
└──────────────┘  events   └───────┘
       │                       │
       └───── /workspace/runs ─┘
              (shared volume)
```

- The orchestrator owns Jira and GitHub credentials, prepares the shared workspace, and performs every Git publish operation.
- The agent runtime receives only the model-provider credentials needed for the current run. It never receives Jira credentials or the orchestrator GitHub token.
- Both containers share `/workspace/runs`, where repositories are checked out for the agent to inspect or modify locally.

## Security

DevFlow runs an AI agent with shell access, so the architecture is built around one rule: the agent never handles business-system secrets directly.

### Credential isolation

The agent container does not receive Jira credentials or the orchestrator GitHub token. The only credentials passed to the agent runtime are the ones required for model inference (`COPILOT_GITHUB_TOKEN`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, or `GEMINI_API_KEY`, depending on the selected model).

All Git operations (clone, fetch, push) are executed by the orchestrator, which injects authentication per command with `git -c http.extraHeader`. Nothing is written to `.git/config`.

### Network segmentation

The Docker Compose setup uses two networks:

- `control` (internal): orchestrator-to-agent communication only
- `egress`: outbound access for Jira, GitHub, and model providers

The runtime also blocks common direct network helpers such as `curl`, `wget`, and `gh` during agent runs.

### Agent guardrails

- All agent callbacks are matched against the active `agentRunId`.
- Repository writes are constrained to the shared workspace root.
- Published branches must start with `devflow/`.
- The agent is instructed not to start applications and to limit validations to compile, lint, and test commands.
- The runtime enforces a hard timeout, then the orchestrator applies stale-run cancellation as a backup.

### Request validation

Incoming agent events are validated with Bean Validation (`@Valid`, `@NotNull`, `@NotBlank`) before they reach the application layer.

## Detailed documentation

- [Architecture](docs/architecture.md) - hexagonal design, workflow holder, polling, timeout and security boundaries
- [Workflow](docs/workflow.md) - end-to-end flow diagram and Jira transitions
- [Scenarios](docs/scenarios.md) - concrete scenarios handled by the current implementation
- [Agent callbacks](docs/agent-callbacks.md) - HTTP contract between the agent runtime and the orchestrator
- [Event catalog](docs/events.md) - command and event payloads used by the system
