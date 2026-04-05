# Event Catalog

All structured messages exchanged between the orchestrator and the agent.

## Design principles

1. A **command** says "do this".
2. An **event** says "here is what happened".
3. The agent never changes ticket or PR state directly — it declares structured facts.
4. The orchestrator transforms those facts into Jira transitions, comments, and GitHub operations.

## Message categories

### Agent commands (orchestrator → agent)

HTTP requests sent by the orchestrator to the agent container.

| Endpoint | Purpose |
|----------|---------|
| `POST /internal/agent-runs` | Start a new OpenCode run |
| `POST /internal/agent-runs/{agentRunId}/cancel` | Cancel an active run |

### Agent events (agent → orchestrator)

HTTP callbacks sent by the agent (or its runtime) to the orchestrator.

| Endpoint | Purpose |
|----------|---------|
| `POST /internal/agent-events` | Report lifecycle events |

## Agent commands

### `START_RUN`

Dispatched when the orchestrator wants to launch an OpenCode execution.

```json
{
  "type": "START_RUN",
  "workflowId": "uuid",
  "agentRunId": "uuid",
  "phase": "INFORMATION_COLLECTION | IMPLEMENTATION | TECHNICAL_VALIDATION | BUSINESS_VALIDATION",
  "inputSnapshot": {
    "workItemKey": "APP-123",
    "summary": "Add export button",
    "workspace": "/workspace/runs",
    "repositories": [...]
  }
}
```

### `CANCEL_RUN`

Dispatched to stop a running agent (timeout, manual cancellation, or obsolete ticket).

## Agent events

All events are sent to `POST /internal/agent-events` with this schema:

```json
{
  "eventId": "agentRunId:uuid",
  "workflowId": "uuid",
  "agentRunId": "uuid",
  "type": "RUN_STARTED | PROGRESS_REPORTED | INPUT_REQUIRED | COMPLETED | FAILED | CANCELLED",
  "occurredAt": "ISO-8601",
  "summary": "optional text",
  "blockerType": "optional",
  "reasonCode": "optional",
  "requestedFrom": "optional (DEV | BUSINESS)",
  "resumeTrigger": "optional",
  "suggestedComment": "optional",
  "artifacts": {},
  "details": {}
}
```

Required fields: `eventId`, `workflowId`, `agentRunId`, `type`.

### `RUN_STARTED`

Emitted by the agent runtime (not OpenCode) immediately after spawning the OpenCode process.

**Orchestrator effect**: transition ticket to "In Progress".

### `PROGRESS_REPORTED`

Emitted by OpenCode via `devflow_report_progress`.

**Orchestrator effect**: log only, no transition.

### `INPUT_REQUIRED`

Emitted by OpenCode via `devflow_request_input` when the agent cannot proceed without external information.

**Orchestrator effect**: transition to "Blocked" → post suggested comment on ticket → `clearRun()`.

Required payload fields: `blockerType`, `reasonCode`, `summary`, `requestedFrom`, `resumeTrigger`, `suggestedComment`.

### `COMPLETED`

Emitted by OpenCode via `devflow_complete_run` when the agent finishes its mission.

**Orchestrator effect** (depends on phase):
- `INFORMATION_COLLECTION` → chain to `IMPLEMENTATION` with new run
- `IMPLEMENTATION` → commit + push + create/reuse PRs → transition to "To Review" → `clearRun()`
- `TECHNICAL_VALIDATION` / `BUSINESS_VALIDATION` → `clearRun()`

Important: in `IMPLEMENTATION`, `COMPLETED` means "local work is done" — the orchestrator creates all branches and PRs.

Payload fields: `summary`, `artifacts.repoChanges[]`, `artifacts.changedFiles[]`, `artifacts.validationCommands[]`.

### `FAILED`

Emitted by OpenCode via `devflow_fail_run`, or by the agent runtime as fallback when OpenCode exits without a terminal event.

**Orchestrator effect**: transition to "Blocked" → post error comment → `clearRun()`.

Fallback failures include diagnostic info in `details`: `exitCode`, `signal`, `stdoutTail`, `stderrTail`.

### `CANCELLED`

Emitted by the agent runtime when a run is stopped by the orchestrator's cancel command.

**Orchestrator effect**: `clearRun()`.

## OpenCode tool → event mapping

| OpenCode tool | Event sent | Terminal? |
|---------------|------------|-----------|
| `devflow_report_progress` | `PROGRESS_REPORTED` | No |
| `devflow_request_input` | `INPUT_REQUIRED` | Yes |
| `devflow_complete_run` | `COMPLETED` | Yes |
| `devflow_fail_run` | `FAILED` | Yes |
| *(runtime auto)* | `RUN_STARTED` | No |
| *(runtime auto)* | `CANCELLED` | Yes |

Exactly one terminal event must be sent per run. If OpenCode exits without sending one, the runtime sends `FAILED` as fallback.

## What the agent must NOT do

The agent declares facts. It does not:

- Change Jira ticket status
- Post Jira comments
- Create Git branches, commits, or PRs
- Push code to remotes
- Access Jira or GitHub APIs
