# Event Catalog

All structured messages exchanged between the orchestrator and the agent runtime.

## Design principles

1. A command tells the runtime what to do.
2. An event tells the orchestrator what happened.
3. The agent never changes Jira or GitHub state directly.
4. The orchestrator converts events into Jira transitions, Jira comments, Git operations, and pull-request publication.

## Message categories

### Agent commands (orchestrator -> agent runtime)

| Endpoint | Purpose |
|----------|---------|
| `POST /internal/agent-runs` | Start a new OpenCode run |
| `POST /internal/agent-runs/{agentRunId}/cancel` | Cancel an active run |

### Agent events (agent runtime -> orchestrator)

| Endpoint | Purpose |
|----------|---------|
| `POST /internal/agent-events` | Report run lifecycle events |

## Agent commands

### `START_RUN`

Dispatched when the orchestrator wants to launch an OpenCode execution.

```json
{
  "commandId": "uuid",
  "workflowId": "uuid",
  "agentRunId": "uuid",
  "type": "START_RUN",
  "phase": "INFORMATION_COLLECTION | IMPLEMENTATION | TECHNICAL_VALIDATION | BUSINESS_VALIDATION",
  "objective": "Implement work item APP-123",
  "inputSnapshot": {
    "workflowId": "uuid",
    "workItemKey": "APP-123",
    "phase": "IMPLEMENTATION",
    "workspace": {
      "projectRoot": "/workspace/runs",
      "repositories": [
        {
          "repository": "my-org/api",
          "projectRoot": "/workspace/runs/api"
        }
      ]
    },
    "repositories": ["my-org/api"]
  },
  "execution": {
    "model": "github-copilot/claude-sonnet-4.6",
    "smallModel": "openai/gpt-5-mini",
    "openAiApiKey": null,
    "anthropicApiKey": null,
    "geminiApiKey": null,
    "copilotGithubToken": "optional"
  }
}
```

Notes:

- `inputSnapshot.phase` is duplicated intentionally for the agent prompt.
- `execution` contains only the model credentials needed for the current run.
- `TECHNICAL_VALIDATION` snapshots also include `codeChange` and `reviewComments`.

### `CANCEL_RUN`

Dispatched to stop a running agent because of timeout, stale-run cleanup, or explicit cancellation.

The cancel endpoint has no request body:

```text
POST /internal/agent-runs/{agentRunId}/cancel
```

## Agent events

All events are sent to `POST /internal/agent-events` with this schema:

```json
{
  "eventId": "agentRunId:uuid",
  "workflowId": "uuid",
  "agentRunId": "uuid",
  "type": "RUN_STARTED | PROGRESS_REPORTED | INPUT_REQUIRED | COMPLETED | FAILED | CANCELLED",
  "occurredAt": "ISO-8601",
  "providerRunRef": "optional provider identifier",
  "summary": "optional text",
  "blockerType": "optional",
  "reasonCode": "optional",
  "requestedFrom": "optional",
  "resumeTrigger": "optional",
  "suggestedComment": "optional",
  "artifacts": {},
  "details": {}
}
```

Required fields: `eventId`, `workflowId`, `agentRunId`, `type`.

### `RUN_STARTED`

Emitted by the runtime, not by OpenCode, immediately after spawning the OpenCode process.

Orchestrator effect: transition the ticket to "In Progress".

### `PROGRESS_REPORTED`

Emitted by OpenCode via `devflow_report_progress`.

Orchestrator effect: log only.

### `INPUT_REQUIRED`

Emitted by OpenCode via `devflow_request_input` when the agent cannot proceed without external input.

Orchestrator effect: transition the ticket to "Blocked", post the suggested comment, and clear the active workflow.

Expected payload fields: `blockerType`, `reasonCode`, `summary`, `requestedFrom`, `resumeTrigger`, `suggestedComment`.

### `COMPLETED`

Emitted by OpenCode via `devflow_complete_run` when the agent has finished its local work.

Orchestrator effect depends on the active phase:

- `INFORMATION_COLLECTION` -> chain directly to a new `IMPLEMENTATION` run
- `IMPLEMENTATION` -> publish code changes -> transition to "To Review" -> clear workflow
- `TECHNICAL_VALIDATION` -> publish review fixes -> keep or return the ticket to "To Review" -> clear workflow
- `BUSINESS_VALIDATION` -> publish follow-up fixes -> transition to "To Review" -> clear workflow
- any unsupported phase -> clear workflow only

Important: `COMPLETED` means "local work is finished". The orchestrator still owns branch creation, commit, push, and pull-request publication.

Common payload fields:

- `summary`
- `artifacts.repoChanges[]`
- `artifacts.changedFiles[]`
- `artifacts.validationCommands[]`
- `artifacts.followUpNotes[]`

### `FAILED`

Emitted by OpenCode via `devflow_fail_run`, or by the runtime when OpenCode exits without a terminal event.

Orchestrator effect: transition the ticket to "Blocked", post an error comment, and clear the workflow.

Fallback failures may include diagnostic information in `details`, such as `exitCode`, `signal`, `stdoutTail`, and `stderrTail`.

### `CANCELLED`

Emitted by the runtime when a run is stopped through the cancel endpoint.

Orchestrator effect: clear the workflow.

## OpenCode tool to event mapping

| OpenCode tool | Event sent | Terminal? |
|---------------|------------|-----------|
| `devflow_report_progress` | `PROGRESS_REPORTED` | No |
| `devflow_request_input` | `INPUT_REQUIRED` | Yes |
| `devflow_complete_run` | `COMPLETED` | Yes |
| `devflow_fail_run` | `FAILED` | Yes |
| runtime automatic event | `RUN_STARTED` | No |
| runtime automatic event | `CANCELLED` | Yes |

Exactly one terminal event must be sent per run. If OpenCode exits without one, the runtime sends `FAILED`.

## What the agent must not do

The agent reports facts. It does not:

- change Jira status directly
- post Jira comments directly
- create Git branches, commits, or pull requests directly
- push code to remotes directly
- call Jira or GitHub APIs directly
