# Architecture

## Overview

DevFlow is a Quarkus orchestrator built with hexagonal architecture. It follows a ticket from creation to validation without depending on any specific tool — Jira, GitHub, and the AI agent are all replaceable adapters behind ports.

The current implementation (v0) is **fully stateless**: no database, no in-memory store, no outbox pattern. The only mutable state is a single `volatile RunContext currentRun` field in `DevFlowRuntime`.

## Stateless model

- All ticket and PR state is re-discovered from Jira and GitHub on every poll cycle
- At most one agent run is active at a time, enforced by the volatile reference
- No deduplication store — idempotency relies on Jira/GitHub status checks
- If the orchestrator restarts mid-run, `currentRun` is lost and the ticket stays in its current Jira status until manual intervention

## Topology

```
orchestrator (Quarkus, Java 21)
├── DevFlowRuntime          — volatile currentRun
├── JiraTicketPollingJob     — polls Jira every minute
├── GitHubPollingJob         — polls GitHub every minute
├── AgentEventService        — handles agent callbacks
└── WorkItemTransitionService — drives Jira transitions

agent (Node.js + OpenCode)
├── HTTP server              — POST /internal/agent-runs, cancel
├── OpenCode process         — AI coding agent
└── devflow.js tools         — POST /internal/agent-events
```

### Docker Compose networks

| Network | Members | Purpose |
|---------|---------|---------|
| `control` (internal) | orchestrator, agent | Agent callback traffic |
| `egress` | orchestrator, agent | Outbound access (Jira, GitHub, LLM providers) |

## Hexagonal design

### Domain layer

Business objects and enums with no external dependencies:

- `WorkItem`, `CodeChangeRef`, `IncomingComment` — neutral abstractions
- `WorkflowPhase`, `BlockerType`, `RequestedFrom`, `ResumeTrigger` — business enums
- Business exceptions

### Application layer

- **Use cases**: `AgentEventService` — handles all agent lifecycle events
- **Services**: `EligibilityService`, `WorkItemTransitionService`, `WorkspaceLayoutService`
- **Runtime**: `DevFlowRuntime` (volatile `RunContext`), `RunContext` record
- **Ports**: `TicketingPort`, `CodeHostPort`, `AgentRuntimePort`

### Infrastructure layer

Inbound adapters:
- `AgentEventResource` — HTTP endpoint for agent callbacks
- `JiraTicketPollingJob` — scheduled Jira poller
- `GitHubPollingJob` — scheduled GitHub poller

Outbound adapters:
- `JiraTicketingAdapter` — implements `TicketingPort`
- `GitHubCodeHostAdapter` — implements `CodeHostPort`
- `HttpAgentRuntimeClient` — implements `AgentRuntimePort`

## Polling

### Jira (`JiraTicketPollingJob`)

- Polls every minute (configurable via `JIRA_POLL_INTERVAL_MINUTES`)
- Searches the configured epic for tickets in "To Do" status — picks the first eligible, starts an info-collection agent run
- Searches for "Blocked" tickets — detects new user comments (author-based filtering via `/rest/api/3/myself`) and resumes the workflow
- Skips ineligible tickets that already have an eligibility comment (with a 60-second grace period for reassessment)
- If busy (`runtime.isBusy()`), the entire poll cycle is skipped

### GitHub (`GitHubPollingJob`)

- Polls every minute (configurable via `GITHUB_POLL_INTERVAL_MINUTES`)
- **Phase 1** (always runs): checks recently closed PRs on `devflow/*` branches for merges — transitions the ticket to "To Validate" with a comment
- **Phase 2** (skipped if busy): checks open `devflow/*` PRs for new review comments — deduplicates by comparing comment `created_at` vs last commit date on the branch

## Timeout mechanisms

Two layers of defense-in-depth prevent a stuck run from blocking the system permanently:

### Layer 1 — Agent runtime timeout (primary)

The Node.js runtime kills the OpenCode process after `maxRunDurationMs` (15 minutes). The process exit triggers a `FAILED` fallback callback to the orchestrator.

Sequence: timer expires → `SIGTERM` → wait 5s → `SIGKILL` if still alive → `handleProcessExit` → send `FAILED`

### Layer 2 — Orchestrator stale-run detection (backup)

The polling jobs check `DevFlowRuntime.isStale()` before skipping for `isBusy()`. If a run exceeds `max-run-duration-minutes` (20 minutes, configurable via `AGENT_MAX_RUN_DURATION_MINUTES`), the orchestrator sends a cancel command and clears `currentRun` locally.

The two timers are deliberately staggered (15 min agent, 20 min orchestrator) so Layer 1 fires first under normal conditions. Layer 2 only activates if the agent runtime itself is unreachable or stuck.

## Security boundaries

- Jira and GitHub credentials stay in the orchestrator container
- The agent has no access to business-system secrets
- The agent cannot talk to Jira or GitHub directly — only to `POST /internal/agent-events`
- The GitHub token is not injected into the agent container
- Git remotes in the shared workspace are credential-free
- Only the orchestrator injects GitHub authentication when executing `git clone`, `git fetch`, or `git push`

## Shared workspace

Both containers mount `/workspace/runs` as a shared volume.

```
/workspace/runs/
├─ AGENTS.md              # Agent instructions (copied before each run)
├─ opencode.json           # OpenCode config
├─ .opencode/
│  ├─ skills/              # Callback skills (report-progress, request-input, complete-run, fail-run)
│  ├─ tools/devflow.js     # Devflow tool definitions
│  └─ lib/devflow-constants.js
├─ repo-a/                 # Checked out repository
└─ repo-b/                 # Checked out repository
```

- Before each run, the agent runtime copies `agent/opencode/` template into `/workspace/runs`
- For new tickets, each repository is reset to its configured base branch
- For review comments, the reviewed repository is checked out on the existing `devflow/*` feature branch
- After PR publication, each repository is reset to its base branch

## Git workflow

All Git operations are performed by the orchestrator (never by the agent):

1. **Clone/fetch** with injected credentials
2. **Checkout** base branch or feature branch
3. After agent completes implementation:
   - Detect modified files in each repository
   - Create `devflow/{ticket-key}/{repo-slug}` branch
   - Commit with agent-provided message (or default)
   - Push branch
   - Create or reuse GitHub pull request
   - Reset workspace to base branch
