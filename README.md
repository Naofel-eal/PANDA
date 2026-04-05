# Devflow

Devflow is a Quarkus orchestrator that drives a ticket-centric delivery workflow across four phases:

- `INFORMATION_COLLECTION`
- `IMPLEMENTATION`
- `TECHNICAL_VALIDATION`
- `BUSINESS_VALIDATION`

The repository now contains the full internal stack:

- a Quarkus orchestrator
- a PostgreSQL persistence layer
- an OpenCode agent runtime in its own container
- Docker Compose wiring, shared workspace volumes, and isolated networks

## Java package layout

The Java code now follows three top-level layers:

- [domain](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/src/main/java/io/devflow/domain)
  - business enums
  - business exceptions
  - core business objects
- [application](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/src/main/java/io/devflow/application)
  - use cases
  - application services
  - ports implemented by infrastructure
- [infrastructure](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/src/main/java/io/devflow/infrastructure)
  - PostgreSQL persistence
  - Jira adapter
  - GitHub adapter
  - OpenCode agent adapter
  - HTTP resources and scheduler

Architectural guardrails enforced in code:

- `domain` and `application` do not import PostgreSQL entities or Panache repositories
- persistence is exposed only through ports under `application/port/persistence`
- PostgreSQL mapping stays under `infrastructure/persistence/postgres`

## Runtime topology

`postgres`

- source of truth for workflows, blockers, runs, inbox events, and outbox commands

`orchestrator`

- owns the workflow state machine
- receives external workflow signals
- launches agent runs by HTTP
- receives structured agent events by HTTP
- keeps all business-system secrets on its side

`agent`

- runs OpenCode
- has no PostgreSQL access
- has no Jira/GitHub/GitLab secrets
- communicates workflow state only through Devflow HTTP tool calls
- does not receive the GitHub token from Docker Compose
- works on a shared Git workspace whose remotes stay credential-free

## Source Code Retrieval

Target repositories are not expected to exist in the containers beforehand.

- the user provides GitHub access through the orchestrator config
- the user explicitly lists the repositories Devflow is allowed to use in `devflow.github.repositories`
- each configured repository can declare its own source branch with `base-branch`
- the ticket provides the work item context only; repository selection comes exclusively from `devflow.github.repositories`
- the orchestrator refreshes all repositories configured in Devflow for each run
- the orchestrator keeps one checkout per repository directly under `/workspace/runs`
- the Git remotes stored in the shared workspace stay credential-free
- only the orchestrator injects GitHub authentication when it executes `git clone`, `git fetch`, or `git push`
- at the beginning of a new task, each selected repository is updated from its configured source branch
- after a pull-request review comment, the reviewed repository is checked out again on the feature branch that was previously published
- before each run, the agent runtime materializes the OpenCode project files at the root of `/workspace/runs`
- the agent starts from `/workspace/runs` and sees the list of repository paths in its input snapshot
- when implementation is completed, the orchestrator checks out the `devflow/...` branch, commits, pushes, opens or reuses one pull request per modified repository, then resets each repository back to its configured source branch
- after a terminal callback, the OpenCode process exits and a new process is launched for the next ticket

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
│  └─ tools/
│     └─ devflow.js
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

Provider polling:

- `JiraTicketPollingJob` polls Jira every minute by default
- it reads only tickets in the configured epic and status
- it injects the resulting work items into the orchestrator as normal workflow signals
- `GitHubPollingJob` polls GitHub every minute by default
- it watches only the pull requests already tracked by Devflow
- it injects review comments and pull request state changes into the orchestrator

External systems to orchestrator:

- `POST /api/v1/workflow-signals`

Inspection:

- `GET /api/v1/workflows`
- `GET /api/v1/workflows/{id}`
- `GET /api/v1/dashboard/tickets`

User interface:

- `GET /`

## OpenCode protocol

All repository-owned OpenCode files are centralized under [agent/opencode](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode).

The repository-owned template contains:

- [AGENTS.md](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/AGENTS.md)
- [opencode.json](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/opencode.json)
- [devflow.js](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/.opencode/tools/devflow.js)
- the callback skills under [agent/opencode/.opencode/skills](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/.opencode/skills)

The template directory itself mirrors the expected OpenCode project layout:

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
   └─ tools/
      └─ devflow.js
```

Before each run, the agent runtime copies this template into the shared workspace root `/workspace/runs`, so that OpenCode starts inside a project that matches the documented layout from the OpenCode config and rules docs:

- `AGENTS.md` at the workspace root
- `opencode.json` at the workspace root
- `.opencode/skills/*/SKILL.md`
- `.opencode/tools/devflow.js`
- repository directories directly under the workspace root

The agent must communicate workflow state only through these tools:

- `devflow_report_progress`
- `devflow_request_input`
- `devflow_complete_run`
- `devflow_fail_run`

The OpenCode skills that explain when to use each callback are centralized here:

- [devflow-report-progress](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/.opencode/skills/devflow-report-progress/SKILL.md)
- [devflow-request-input](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/.opencode/skills/devflow-request-input/SKILL.md)
- [devflow-complete-run](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/.opencode/skills/devflow-complete-run/SKILL.md)
- [devflow-fail-run](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/.opencode/skills/devflow-fail-run/SKILL.md)

`RUN_STARTED` and `CANCELLED` do not have OpenCode skills because they are emitted by the agent runtime itself, not by the model.

Important semantic rule:

- `devflow_complete_run` during `IMPLEMENTATION` means "the local implementation is finished"
- it does not mean "the agent created a branch or a pull request"
- the orchestrator receives `COMPLETED`, scans the shared workspaces, then creates the `devflow/...` branches and GitHub pull requests itself

## Scenario Catalog

A simple, exhaustive scenario catalog of the current implementation is available in [docs/scenarios.md](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/docs/scenarios.md).

The exact agent callback catalog and payloads are documented in [docs/agent-callbacks.md](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/docs/agent-callbacks.md).

## Local stack

Create your local environment file first:

```bash
cp .env.example .env
```

```bash
docker compose up --build
```

The only user-facing runtime config is [`.env.example`](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/.env.example). Copy it to `.env`, then fill:

- `JIRA_BASE_URL`, `JIRA_USER_EMAIL`, `JIRA_API_TOKEN`
- `JIRA_EPIC_KEY` to select the epic polled locally
- `JIRA_TODO_STATUS` if your Jira status is not exactly `To Do`
- `JIRA_POLL_INTERVAL_MINUTES` and `JIRA_POLL_MAX_RESULTS`
- `GITHUB_TOKEN` for clone, pull, push, and pull-request operations
- `GITHUB_POLL_INTERVAL_MINUTES`
- indexed repository variables like `DEVFLOW_GITHUB_REPOSITORIES_0_NAME` and `DEVFLOW_GITHUB_REPOSITORIES_0_BASE_BRANCH`
- `OPENCODE_MODEL` if you want to force a specific model
- `OPENCODE_SMALL_MODEL` if you want to force the lightweight OpenCode model used for title generation and other cheap tasks
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

Important note:

- the agent is isolated from PostgreSQL and business-system secrets
- the agent does not receive `GITHUB_TOKEN`, `JIRA_API_TOKEN`, or the `.env` file
- the agent can receive a dedicated `COPILOT_GITHUB_TOKEN` only for the OpenCode child process when you choose the `copilot/...` provider
- the shared `.git/config` files do not contain embedded GitHub credentials
- the orchestrator injects only the model-provider settings needed for the current agent run
- if you use a hosted model provider, the agent container still needs model-provider egress
- if you want strict `agent -> orchestrator only` networking, place a model gateway inside the cluster and point OpenCode to that internal endpoint
- Jira intake is now polling-only in the minimal setup
- GitHub review comments and merge states are also polled in the minimal setup
- GitHub polling only tracks pull requests already created and registered by Devflow

## Example workflow signal

```bash
curl -X POST http://localhost:8080/api/v1/workflow-signals \
  -H 'content-type: application/json' \
  -d '{
    "type": "WORK_ITEM_DISCOVERED",
    "sourceSystem": "jira",
    "sourceEventId": "jira-evt-1",
    "workItem": {
      "key": "APP-123",
      "type": "story",
      "title": "Add export button",
      "description": "Users need to export the filtered report",
      "url": "https://jira.example.com/browse/APP-123"
    }
  }'
```

## Verification performed

- `./gradlew compileJava --no-daemon`
- `./gradlew compileJava test --no-daemon`
- `node --check agent/src/server.mjs`
- `node --check agent/opencode/.opencode/tools/devflow.js`
- `docker compose config`

The agent image build completed successfully. The orchestrator image build was started but I stopped the verification while Docker was still downloading heavy base layers.
