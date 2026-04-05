# DevFlow

DevFlow is an autonomous coding orchestrator. It picks up Jira tickets, dispatches an AI agent ([OpenCode](https://opencode.ai)) to analyze and implement the work across one or more GitHub repositories, creates pull requests, and reacts to review feedback — all without human intervention.

## How it works

1. **Polls Jira** every minute for eligible tickets in a configured epic.
2. **Information collection** — an agent run analyzes the ticket and the codebase to build an implementation plan. If something is unclear, the ticket is blocked with a Jira comment asking for clarification.
3. **Implementation** — a second agent run writes the code, runs tests, and reports completion. The orchestrator commits, pushes, and opens one PR per modified repository.
4. **Review loop** — when a reviewer posts an inline comment on a PR, DevFlow detects it and dispatches a new agent run to address the feedback, then pushes the fix.
5. **Merge detection** — once all PRs are merged, the ticket is automatically moved to "To Validate".

DevFlow is **stateless** (no database). It re-discovers ticket and PR state from Jira and GitHub on every poll cycle. The only in-process state is a single volatile reference tracking the currently active agent run.

## Quick start

```bash
cp .env.example .env   # fill in your credentials
docker compose up --build
```

## Configuration

All configuration is done through environment variables in `.env`. See [`.env.example`](.env.example) for the full list.

### Jira

| Variable | Description | Default |
|----------|-------------|---------|
| `JIRA_BASE_URL` | Atlassian instance URL | — |
| `JIRA_USER_EMAIL` | Jira account email | — |
| `JIRA_API_TOKEN` | Jira API token | — |
| `JIRA_EPIC_KEY` | Epic key to poll (e.g. `SCRUM-2`) | — |
| `JIRA_TODO_STATUS` | Status name for new tickets | `To Do` |
| `JIRA_POLL_INTERVAL_MINUTES` | Poll frequency | `1` |

### GitHub

| Variable | Description | Default |
|----------|-------------|---------|
| `GITHUB_TOKEN` | Personal access token (repo scope) | — |
| `GITHUB_POLL_INTERVAL_MINUTES` | Poll frequency | `1` |
| `DEVFLOW_GITHUB_REPOSITORIES_0_NAME` | Repository slug (e.g. `my-org/api`) | — |
| `DEVFLOW_GITHUB_REPOSITORIES_0_BASE_BRANCH` | Base branch to work from | `main` |

Add more repositories with index `1`, `2`, etc.

### AI model

DevFlow uses OpenCode as its agent runtime. Configure the LLM provider with one of:

| Variable | Description |
|----------|-------------|
| `OPENCODE_MODEL` | Primary model (e.g. `github-copilot/claude-sonnet-4.6`) |
| `OPENCODE_SMALL_MODEL` | Lightweight model for cheap tasks |
| `COPILOT_GITHUB_TOKEN` | GitHub Copilot token (if using `github-copilot/*` models) |
| `OPENAI_API_KEY` | OpenAI key (if using `openai/*` models) |
| `ANTHROPIC_API_KEY` | Anthropic key (if using `anthropic/*` models) |

## Architecture overview

```
┌──────────────┐   poll    ┌───────┐
│ Orchestrator │ ───────── │  Jira │
│  (Quarkus)   │ ───────── │GitHub │
│              │   poll    └───────┘
│              │
│              │   HTTP    ┌───────┐
│              │ ────────▸ │ Agent │
│              │ ◂──────── │(OpenCode)
└──────────────┘  events   └───────┘
       │                       │
       └───── /workspace/runs ─┘
              (shared volume)
```

- The **orchestrator** owns all secrets (Jira, GitHub), manages Git operations, and drives ticket transitions.
- The **agent** is sandboxed — no access to Jira/GitHub credentials. It communicates only through structured HTTP callbacks.
- Both containers share a workspace volume where repositories are checked out.

## Detailed documentation

- [Architecture](docs/architecture.md) — hexagonal design, stateless model, timeout mechanisms, security boundaries
- [Workflow](docs/workflow.md) — end-to-end flow diagram (Mermaid)
- [Scenarios](docs/scenarios.md) — exhaustive list of every scenario handled by the system
- [Agent callbacks](docs/agent-callbacks.md) — HTTP contract between agent and orchestrator
- [Event catalog](docs/events.md) — all commands and events in the system

## License

Private project.
