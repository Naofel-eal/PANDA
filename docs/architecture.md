# Architecture

## Overview

PANDA is a Quarkus orchestrator built with hexagonal architecture. It follows a Jira ticket through four execution phases:

- `INFORMATION_COLLECTION`
- `IMPLEMENTATION`
- `TECHNICAL_VALIDATION`
- `BUSINESS_VALIDATION`

Jira, GitHub, and the AI runtime are all replaceable adapters behind ports.

The system uses file-based persistence for workflows (JSON files with atomic writes) and re-discovers external state from Jira and GitHub on every poll cycle. At most one workflow is active at a time, held in `InMemoryWorkflowHolder` and backed by `FileWorkflowRepository`.

## Runtime state model

- Ticket and pull request state is re-discovered from Jira and GitHub on every poll cycle.
- At most one workflow is active at a time.
- The in-memory `Workflow` tracks the ticket, phase, status, active `agentRunId`, start time, transitions, and already published pull requests.
- Workflows are persisted to disk as JSON files via `FileWorkflowRepository` with atomic writes (write-to-temp then rename).
- On startup, `InMemoryWorkflowHolder` rehydrates active workflows from disk, enabling recovery after orchestrator restarts.
- Orphaned tickets (stuck in non-terminal Jira statuses without an active workflow) are recovered to "To Do" by `OrphanedTicketRecoveryJob` at startup.

## Topology

```
orchestrator (Quarkus, Java 21)
├── InMemoryWorkflowHolder      - holds the current Workflow (backed by FileWorkflowRepository)
├── JiraTicketPollingJob        - polls Jira for intake and Jira-side feedback
├── GitHubPollingJob            - polls GitHub for merges and review feedback
├── DispatchAgentRunUseCase     - prepares workspace and starts agent runs
├── HandleAgentEventUseCase     - consumes agent lifecycle events
├── PublishCodeChangesUseCase   - commits, pushes, and creates or reuses PRs
├── RecoverOrphanedTicketsUseCase - recovers stuck tickets at startup
└── CancelStaleRunUseCase       - detects and cancels timed-out runs

agent runtime (Node.js + OpenCode)
├── HTTP server                 - POST /internal/agent-runs and cancel endpoint
├── workspace materializer      - writes opencode config into /workspace/runs
├── OpenCode process            - coding agent
└── PANDA callback tools      - POST /internal/agent-events
```

### Docker Compose networks

| Network | Members | Purpose |
|---------|---------|---------|
| `control` (internal) | orchestrator, agent | orchestrator-to-agent commands and agent callbacks |
| `egress` | orchestrator, agent | outbound access to Jira, GitHub, and model providers |

## Hexagonal design

### Domain layer

Pure domain objects and enums:

- `Workflow`, `WorkflowPhase`
- `WorkItem`, `IncomingComment`
- `CodeChangeRef`
- `BlockerType`, `RequestedFrom`, `ResumeTrigger`
- domain exceptions

### Application layer

Main use cases and services:

- `StartInfoCollectionUseCase`
- `ResumeWorkflowUseCase`
- `HandleReviewCommentUseCase`
- `HandleMergedPullRequestUseCase`
- `HandleAgentEventUseCase`
- `PublishCodeChangesUseCase`
- `CancelStaleRunUseCase`
- `RecoverOrphanedTicketsUseCase`
- `WorkspaceLayoutService`
- `WorkflowHolder`

Ports:

- `TicketingPort`
- `CodeHostPort`
- `AgentRuntimePort`
- `WorkflowRepository`

### Infrastructure layer

Inbound adapters:

- `AgentEventResource`
- `JiraTicketPollingJob`
- `GitHubPollingJob`

Outbound adapters:

- `JiraTicketingAdapter`
- `GitHubCodeHostAdapter`
- `HttpAgentRuntimeClient`
- `FileWorkflowRepository`

## Polling

### Jira (`JiraTicketPollingJob`)

- Polls every minute by default (`JIRA_POLL_INTERVAL_MINUTES`).
- Searches the configured project for tickets assigned to the PANDA service account in the configured "To Do" status.
- Eligible tickets start an `INFORMATION_COLLECTION` run.
- Ineligible tickets are moved to "Blocked" and receive a Jira comment listing the missing fields.
- Tickets already blocked by an eligibility comment are skipped until a user comment or a later ticket update is detected.
- Tickets in "Blocked" resume in `INFORMATION_COLLECTION` when a new user comment exists or the ticket changed after the last PANDA comment.
- Tickets in "To Validate" resume in `INFORMATION_COLLECTION` using the same feedback detection rule.
- If a workflow is active, the Jira poll cycle is skipped.

### GitHub (`GitHubPollingJob`)

- Polls every minute by default (`GITHUB_POLL_INTERVAL_MINUTES`).
- Phase 1 always runs: recently closed `panda/*` pull requests are checked for merges. If the related ticket is still in "To Review" and the merge is newer than the ticket update timestamp, the ticket moves to "To Validate".
- Phase 2 runs only when no workflow is active: open `panda/*` pull requests are scanned for new human feedback.
- Both pull request review comments and standard issue comments on the PR are considered.
- Feedback is deduplicated by keeping only comments created after the last commit on the source branch.
- Matching feedback starts a `TECHNICAL_VALIDATION` run scoped to the reviewed repository and branch.

## Agent phases

### `INFORMATION_COLLECTION`

- Workspace is read-only by policy.
- The agent inspects local repositories, validates ticket understanding, and either requests clarification or hands off to implementation.
- A `COMPLETED` event automatically chains to `IMPLEMENTATION` with a fresh `agentRunId`.

### `IMPLEMENTATION`

- The agent edits the workspace, runs validation commands, and emits `COMPLETED` when local work is done.
- The orchestrator then publishes the changes and moves the ticket to "To Review".

### `TECHNICAL_VALIDATION`

- Triggered by human GitHub feedback on an open PANDA pull request.
- The workspace is prepared on the existing review branch for the reviewed repository.
- On success, the orchestrator republishes the fixes and keeps the ticket in "To Review" (the Jira transition becomes a no-op if the ticket is already there).

### `BUSINESS_VALIDATION`

- Triggered by Jira feedback while the ticket is in "To Validate".
- The agent implements follow-up business feedback from Jira comments or ticket updates.
- On success, the orchestrator publishes the changes and moves the ticket back to "To Review".

## Timeout mechanisms

Two layers of defense keep a stuck run from blocking the system indefinitely.

### Layer 1: agent runtime timeout

The Node.js runtime kills the OpenCode process after `AGENT_MAX_RUN_DURATION_MINUTES` (default `15` minutes).

Sequence: timer expires -> `SIGTERM` -> wait 5 seconds -> `SIGKILL` if still alive -> fallback `FAILED` callback.

### Layer 2: orchestrator stale-run detection

Both polling jobs call stale-run cancellation before skipping for `isBusy()`. If the active workflow exceeds `AGENT_MAX_RUN_DURATION_MINUTES + AGENT_STALE_TIMEOUT_BUFFER_MINUTES` (default `15 + 5 = 20` minutes), the orchestrator sends a cancel command to the agent runtime and clears the in-memory workflow locally.

The timers are intentionally staggered so the runtime timeout fires first under normal conditions.

## Security boundaries

- Jira credentials stay in the orchestrator container.
- The orchestrator GitHub token also stays in the orchestrator container.
- The agent runtime only receives the model-provider credentials required for the current run.
- Git remotes in the shared workspace are credential-free.
- The runtime replaces `curl`, `wget`, and `gh` with blocking shims during agent runs.
- Only the orchestrator injects authentication when running `git clone`, `git fetch`, or `git push`.

## Shared workspace

Both containers mount `/workspace/runs` as a shared volume.

```
/workspace/runs/
├─ opencode.json
├─ .opencode/
│  ├─ skills/
│  ├─ tools/panda.js
│  └─ lib/panda-constants.js
├─ repo-a/
└─ repo-b/
```

- Before each run, the agent runtime materializes `opencode.json` and `.opencode/` into `/workspace/runs`.
- Repositories are checked out directly under `/workspace/runs` using the repository slug suffix (`my-org/api` -> `/workspace/runs/api`).
- For new tickets and business-validation feedback, repositories are prepared on their configured base branches unless the snapshot pins a specific branch.
- For technical validation, the reviewed repository is prepared on the existing `panda/*` source branch.
- After publication, each repository is reset to its base branch.

## Git workflow

All Git publication is handled by the orchestrator, never by the agent:

1. Clone or refresh each configured repository with injected credentials.
2. Check out the correct base branch or existing review branch.
3. After the agent signals `COMPLETED`, inspect the modified repositories.
4. For each changed repository:
   - create or reuse a `panda/{ticket-key}/{repo-slug}` branch by default
   - commit with agent-provided metadata when available
   - force-push the branch
   - create a new pull request or reuse the existing open one for that branch
5. Reset the workspace back to the configured base branch.
