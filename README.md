# 🤖 DevFlow

DevFlow is an autonomous coding orchestrator. Drop a ticket in Jira, and an AI agent ([OpenCode](https://opencode.ai)) picks it up, reads your codebase, writes the code, opens pull requests — and even fixes review comments on its own.

No human in the loop. You review the PR when it's ready.

## 🧠 What the agent actually does

DevFlow doesn't just blindly generate code. It works in two phases, like a developer would:

1. **📖 It reads first.** The agent explores your codebase, understands the existing architecture, checks the coding conventions, and builds an implementation plan. If the ticket is vague or missing context, it asks questions directly in a Jira comment — and waits for your answer before moving on.

2. **💻 Then it codes.** It writes the implementation, runs the linter, runs the tests, fixes what breaks. When it's done, DevFlow opens a pull request per repository — with a clean diff ready for review.

And it doesn't stop there:

- **🔁 It handles review feedback.** Post an inline comment on the PR, and DevFlow picks it up, spins up a new agent run to address your remarks, and pushes the fix to the same branch.
- **✅ It tracks merges.** Once all PRs are merged, the Jira ticket moves to "To Validate" automatically. No manual status updates.
- **💬 It can resume from validation feedback.** Add a comment on a ticket in "To Validate", and DevFlow starts a new run to address it.

The whole system is **stateless** — no database, no in-memory store. DevFlow re-discovers everything from Jira and GitHub on every poll cycle. If it restarts, it picks up right where it left off.

## 🚀 Quick start

```bash
cp .env.example .env   # fill in your credentials
docker compose up --build
```

## ⚙️ Configuration

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
| `AGENT_MAX_RUN_DURATION_MINUTES` | Hard timeout for a single agent run before the runtime kills it |
| `AGENT_STALE_TIMEOUT_BUFFER_MINUTES` | Extra minutes before the orchestrator cancels a stale run as backup |
| `OPENCODE_MODEL` | Primary model (e.g. `github-copilot/claude-sonnet-4.6`) |
| `OPENCODE_SMALL_MODEL` | Lightweight model for cheap tasks |
| `COPILOT_GITHUB_TOKEN` | GitHub Copilot token (if using `github-copilot/*` models) |
| `OPENAI_API_KEY` | OpenAI key (if using `openai/*` models) |
| `ANTHROPIC_API_KEY` | Anthropic key (if using `anthropic/*` models) |

## 🏗️ Architecture overview

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

## 🔒 Security

DevFlow runs an AI agent with shell access — so security is not an afterthought. The architecture is designed around one principle: **the agent never touches your secrets**.

### 🔑 Credential isolation

The agent container has **zero access** to your GitHub token, Jira credentials, or any `.env` variable. The only secret it receives is a Copilot LLM token (scoped exclusively to model inference — no `repo`, no `admin`, no API access).

All Git operations (clone, fetch, push) are performed by the orchestrator, which injects credentials per-command via `git -c http.extraHeader`. Nothing is ever written to `.git/config` or persisted on disk.

### 🌐 Network segmentation

The system uses two Docker networks:

- **`control`** (internal) — orchestrator-to-agent communication only. Not reachable from outside.
- **`egress`** — outbound access to Jira, GitHub, and LLM APIs.

The agent cannot reach the orchestrator's secrets, and the internal endpoints are not exposed beyond the Docker host.

### 🧱 Agent sandboxing

Even though the agent has shell access inside its container, several guardrails are in place:

- **No credentials available** — there is nothing to exfiltrate.
- **Path traversal protection** — tool calls validate all paths against an allowed root directory.
- **Branch name enforcement** — all branches must start with `devflow/`, preventing writes to protected branches.
- **Run ID matching** — every agent callback is validated against the active run ID, preventing event spoofing.
- **Configurable hard timeout** — the agent process is killed after `AGENT_MAX_RUN_DURATION_MINUTES` (default `15`, SIGTERM then SIGKILL after 5s). The orchestrator has a backup stale-run detector delayed by `AGENT_STALE_TIMEOUT_BUFFER_MINUTES` (default `5`).
- **Behavioral rules** — the agent is instructed never to start applications, never to kill its own runner, and to only run compilation, lint, and test commands.

### ✅ Request validation

All HTTP payloads from the agent are validated with Bean Validation (`@Valid`, `@NotNull`, `@NotBlank`) before processing. Malformed events are rejected.

## 📚 Detailed documentation

- [Architecture](docs/architecture.md) — hexagonal design, stateless model, timeout mechanisms, security boundaries
- [Workflow](docs/workflow.md) — end-to-end flow diagram (Mermaid)
- [Scenarios](docs/scenarios.md) — exhaustive list of every scenario handled by the system
- [Agent callbacks](docs/agent-callbacks.md) — HTTP contract between agent and orchestrator
- [Event catalog](docs/events.md) — all commands and events in the system

## 📄 License

DevFlow is licensed under the [Business Source License 1.1](LICENSE).

- **Non-commercial use**: free
- **Commercial production use**: requires a paid license — contact naofel.eal@gmail.com
- **Change Date**: April 5, 2030 — after which the code becomes [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)
