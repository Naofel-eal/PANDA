# Devflow

Devflow is a stateless Quarkus orchestrator that drives a ticket-centric delivery workflow across four phases:

- `INFORMATION_COLLECTION`
- `IMPLEMENTATION`
- `TECHNICAL_VALIDATION`
- `BUSINESS_VALIDATION`

The repository contains:

- a Quarkus orchestrator (stateless, no database)
- an OpenCode agent runtime in its own container
- Docker Compose wiring, shared workspace volumes, and isolated networks

## Stateless architecture (v0)

Devflow v0 is entirely stateless. There is no database, no in-memory store, and no outbox pattern. The only mutable state is a single `volatile RunContext currentRun` field in `DevFlowRuntime`, which tracks the currently active agent run.

Key properties:

- all ticket and PR state is re-discovered from Jira and GitHub on every poll cycle
- if the orchestrator restarts mid-run, `currentRun` is lost and the ticket stays in its current Jira status until manual intervention
- at most one agent run is active at a time, enforced by the volatile reference
- no deduplication store — idempotency is achieved through Jira/GitHub status checks

## Java package layout

The Java code follows three top-level layers:

- `domain` — business enums, exceptions, core business objects
- `application` — use cases, application services, runtime state, ports
- `infrastructure` — Jira adapter, GitHub adapter, OpenCode agent adapter, HTTP resources

Architectural guardrails:

- `domain` and `application` do not import any infrastructure classes
- Jira interaction is encapsulated in `infrastructure/ticketing/jira`
- GitHub interaction is encapsulated in `infrastructure/codehost/github`

## Runtime topology

`orchestrator`

- owns the workflow lifecycle
- holds the volatile `currentRun` reference
- polls Jira and GitHub periodically
- launches agent runs by HTTP
- receives structured agent events by HTTP
- keeps all business-system secrets on its side
- performs all Git operations (clone, fetch, push, PR creation)

`agent`

- runs OpenCode
- has no database access
- has no Jira/GitHub secrets
- communicates workflow state only through Devflow HTTP tool calls
- does not receive the GitHub token from Docker Compose
- works on a shared Git workspace whose remotes stay credential-free

## Polling-based discovery

### Jira polling (`JiraTicketPollingJob`)

- polls every minute by default
- searches the configured epic for tickets in "To Do" status — picks the first eligible and starts an info-collection agent run
- searches for tickets in "Blocked" status — detects new user comments and resumes the workflow
- skips ineligible tickets that already have an eligibility comment and no new information (with a 60-second grace period)
- if busy (agent run active), the entire poll cycle is skipped

### GitHub polling (`GitHubPollingJob`)

- polls every minute by default
- Phase 1 (always runs): checks recently closed PRs on `devflow/*` branches for merges — transitions the ticket from "To Review" to "To Validate" with a detailed comment
- Phase 2 (skipped if busy): checks open `devflow/*` PRs for new review comments — deduplicates by comparing comment `created_at` vs last commit date on the branch

## Source code retrieval

Target repositories are not expected to exist in the containers beforehand.

- the user provides GitHub access through the orchestrator config
- the user lists the repositories Devflow is allowed to use in `devflow.github.repositories`
- each configured repository can declare its own source branch with `base-branch`
- the orchestrator refreshes all configured repositories for each run
- the orchestrator keeps one checkout per repository directly under `/workspace/runs`
- the Git remotes stored in the shared workspace stay credential-free
- only the orchestrator injects GitHub authentication when it executes `git clone`, `git fetch`, or `git push`
- at the beginning of a new task, each selected repository is updated from its configured source branch
- after a pull-request review comment, the reviewed repository is checked out on the feature branch that was previously published
- before each run, the agent runtime materializes the OpenCode project files at the root of `/workspace/runs`
- when implementation is completed, the orchestrator checks out the `devflow/...` branch, commits, pushes, opens or reuses one pull request per modified repository, then resets each repository back to its configured source branch

The effective shared workspace seen by the agent looks like this:

```text
/workspace/runs/
├─ AGENTS.md
├─ opencode.json
├─ .opencode/
│  ├─ skills/
│  │  ├─ devflow-report-progress/
│  │  │  └─ SKILL.md
│  │  ├─ devflow-request-input/
│  │  │  └─ SKILL.md
│  │  ├─ devflow-complete-run/
│  │  │  └─ SKILL.md
│  │  └─ devflow-fail-run/
│  │     └─ SKILL.md
│  ├─ tools/
│  │  └─ devflow.js
│  └─ lib/
│     └─ devflow-constants.js
├─ repo-a/
│  └─ .git/
└─ repo-b/
   └─ .git/
```

If no repository is configured in Devflow, the workflow is blocked in information collection with a ticket comment explaining the reason.

## Key internal APIs

Orchestrator to agent:

- `POST /internal/agent-runs`
- `POST /internal/agent-runs/{agentRunId}/cancel`

Agent to orchestrator:

- `POST /internal/agent-events`

There are no external-facing APIs, no workflow signals endpoint, and no dashboard in v0. All intake is polling-based.

## OpenCode protocol

All repository-owned OpenCode files are centralized under `agent/opencode`.

The template directory mirrors the expected OpenCode project layout:

```text
agent/opencode/
├─ AGENTS.md
├─ opencode.json
└─ .opencode/
   ├─ skills/
   │  ├─ devflow-report-progress/
   │  │  └─ SKILL.md
   │  ├─ devflow-request-input/
   │  │  └─ SKILL.md
   │  ├─ devflow-complete-run/
   │  │  └─ SKILL.md
   │  └─ devflow-fail-run/
   │     └─ SKILL.md
   ├─ tools/
   │  └─ devflow.js
   └─ lib/
      └─ devflow-constants.js
```

Before each run, the agent runtime copies this template into the shared workspace root `/workspace/runs`.

The agent must communicate workflow state only through these tools:

- `devflow_report_progress`
- `devflow_request_input`
- `devflow_complete_run`
- `devflow_fail_run`

Agent instructions and output requirements are defined in `AGENTS.md` (not hardcoded in Java). The orchestrator copies this file to the workspace before each run, and OpenCode reads it automatically.

Important semantic rule:

- `devflow_complete_run` during `IMPLEMENTATION` means "the local implementation is finished"
- it does not mean "the agent created a branch or a pull request"
- the orchestrator receives `COMPLETED`, scans the shared workspaces, then creates the `devflow/...` branches and GitHub pull requests itself

## Scenario catalog

A scenario catalog of the current implementation is available in `docs/scenarios.md`.

The exact agent callback catalog and payloads are documented in `docs/agent-callbacks.md`.

## Local stack

Create your local environment file first:

```bash
cp .env.example .env
```

```bash
docker compose up --build
```

The only user-facing runtime config is `.env.example`. Copy it to `.env`, then fill:

- `JIRA_BASE_URL`, `JIRA_USER_EMAIL`, `JIRA_API_TOKEN`
- `JIRA_EPIC_KEY` to select the epic polled locally
- `JIRA_TODO_STATUS` if your Jira status is not exactly `To Do`
- `JIRA_POLL_INTERVAL_MINUTES` and `JIRA_POLL_MAX_RESULTS`
- `GITHUB_TOKEN` for clone, pull, push, and pull-request operations
- `GITHUB_POLL_INTERVAL_MINUTES`
- indexed repository variables like `DEVFLOW_GITHUB_REPOSITORIES_0_NAME` and `DEVFLOW_GITHUB_REPOSITORIES_0_BASE_BRANCH`
- `OPENCODE_MODEL` if you want to force a specific model
- `OPENCODE_SMALL_MODEL` if you want to force the lightweight OpenCode model
- one provider key under `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, or `COPILOT_GITHUB_TOKEN`

Example `.env` fragment:

```dotenv
JIRA_BASE_URL=https://your-company.atlassian.net
JIRA_USER_EMAIL=you@example.com
JIRA_API_TOKEN=your_jira_api_token
JIRA_EPIC_KEY=APP-42
JIRA_TODO_STATUS=To Do
JIRA_POLL_INTERVAL_MINUTES=1
JIRA_POLL_MAX_RESULTS=50

GITHUB_TOKEN=your_github_token
GITHUB_POLL_INTERVAL_MINUTES=1
DEVFLOW_GITHUB_REPOSITORIES_0_NAME=my-org/frontend-app
DEVFLOW_GITHUB_REPOSITORIES_0_BASE_BRANCH=develop
DEVFLOW_GITHUB_REPOSITORIES_1_NAME=my-org/backend-app
DEVFLOW_GITHUB_REPOSITORIES_1_BASE_BRANCH=main

OPENAI_API_KEY=your_openai_api_key
```

For GitHub Copilot with OpenCode, use a dedicated token with Copilot access:

```dotenv
OPENCODE_MODEL=github-copilot/gpt-4o
OPENCODE_SMALL_MODEL=github-copilot/gpt-4o-mini
COPILOT_GITHUB_TOKEN=your_copilot_github_token
```

Important notes:

- the agent is isolated from business-system secrets
- the agent does not receive `GITHUB_TOKEN`, `JIRA_API_TOKEN`, or the `.env` file
- the agent can receive a dedicated `COPILOT_GITHUB_TOKEN` only for the OpenCode child process when you choose the `copilot/...` provider
- the shared `.git/config` files do not contain embedded GitHub credentials
- the orchestrator injects only the model-provider settings needed for the current agent run
- Jira intake is polling-only
- GitHub review comments and merge states are also polled
- there is no database — the orchestrator is fully stateless with a single volatile run reference
