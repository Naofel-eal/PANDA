# Agent Callbacks

HTTP contract between the agent runtime and the orchestrator.

## Transport

The runtime posts lifecycle events to:

```text
POST /internal/agent-events
```

The orchestrator controls the runtime through:

```text
POST /internal/agent-runs
POST /internal/agent-runs/{agentRunId}/cancel
```

## Runtime template files

The runtime image keeps its OpenCode template under `agent/opencode/`.

Before each run, it materializes the OpenCode configuration into the shared workspace `/workspace/runs`:

| File or directory | Purpose |
|-------------------|---------|
| `agent/opencode/AGENTS.md` | Source-controlled agent protocol for the NUD runtime |
| `/workspace/runs/opencode.json` | OpenCode configuration written before the run |
| `/workspace/runs/.opencode/tools/nud.js` | NUD tool definitions |
| `/workspace/runs/.opencode/lib/nud-constants.js` | Shared callback constants |
| `/workspace/runs/.opencode/skills/*/SKILL.md` | Callback skill documentation |

## Event schema

```json
{
  "eventId": "agentRunId:uuid",
  "workflowId": "uuid",
  "agentRunId": "uuid",
  "type": "RUN_STARTED | PROGRESS_REPORTED | INPUT_REQUIRED | COMPLETED | FAILED | CANCELLED",
  "occurredAt": "ISO-8601",
  "providerRunRef": "string",
  "summary": "string",
  "blockerType": "string",
  "reasonCode": "string",
  "requestedFrom": "DEV | BUSINESS | SYSTEM",
  "resumeTrigger": "string",
  "suggestedComment": "string",
  "artifacts": { ... },
  "details": { ... }
}
```

Required: `eventId`, `workflowId`, `agentRunId`, `type`. All other fields are optional.

## Event reference

### `RUN_STARTED`

Sent by the runtime after spawning OpenCode.

Effect: ticket -> "In Progress".

```json
{
  "type": "RUN_STARTED",
  "summary": "OpenCode run started for phase IMPLEMENTATION."
}
```

### `PROGRESS_REPORTED`

Sent by OpenCode via `nud_report_progress`.

Effect: log only.

```json
{
  "type": "PROGRESS_REPORTED",
  "summary": "Frontend implementation done, running backend tests."
}
```

### `INPUT_REQUIRED`

Sent by OpenCode via `nud_request_input` when the run cannot continue without clarification.

Effect: ticket -> "Blocked", Jira comment posted, workflow cleared.

```json
{
  "type": "INPUT_REQUIRED",
  "blockerType": "MISSING_TICKET_INFORMATION",
  "reasonCode": "ACCEPTANCE_CRITERIA_AMBIGUOUS",
  "summary": "The expected behavior is ambiguous.",
  "requestedFrom": "BUSINESS",
  "resumeTrigger": "WORK_ITEM_COMMENT_RECEIVED",
  "suggestedComment": "Can you clarify the expected export scope?"
}
```

### `COMPLETED`

Sent by OpenCode via `nud_complete_run` when local work is finished.

Effect by phase:

| Phase | Orchestrator action |
|-------|---------------------|
| `INFORMATION_COLLECTION` | Chain asynchronously to `IMPLEMENTATION` after 3 seconds |
| `IMPLEMENTATION` | Publish code changes, move ticket to "To Review", then clear the workflow |
| `TECHNICAL_VALIDATION` | Publish review fixes, keep or return the ticket to "To Review", then clear the workflow |
| `BUSINESS_VALIDATION` | Publish follow-up fixes, move ticket to "To Review", then clear the workflow |

Important: `COMPLETED` never means "the PR already exists". It means "the local workspace is ready for publication".

```json
{
  "type": "COMPLETED",
  "summary": "Implementation finished for frontend and backend.",
  "artifacts": {
    "changedFiles": ["frontend/src/Health.tsx", "backend/src/health.js"],
    "validationCommands": ["npm test"],
    "repoChanges": [
      {
        "repository": "my-org/frontend",
        "commitMessage": "feat(health): add health status component",
        "prTitle": "feat(health): add health status component [APP-123]"
      }
    ]
  }
}
```

### `FAILED`

Sent by OpenCode via `nud_fail_run`, or by the runtime as fallback when OpenCode exits without a terminal event.

Effect: ticket -> "Blocked", failure comment posted, workflow cleared.

```json
{
  "type": "FAILED",
  "summary": "Tests fail on a regression.",
  "details": { "failingCommand": "npm test" }
}
```

Fallback example:

```json
{
  "type": "FAILED",
  "summary": "OpenCode exited with code 1 without sending a terminal event.",
  "details": {
    "exitCode": 1,
    "stdoutTail": "...",
    "stderrTail": "..."
  }
}
```

### `CANCELLED`

Sent by the runtime when the orchestrator cancels a run.

Effect: workflow cleared.

```json
{
  "type": "CANCELLED",
  "summary": "Run was cancelled by the orchestrator."
}
```

## Terminal event rules

- Exactly one terminal event per run: `INPUT_REQUIRED`, `COMPLETED`, `FAILED`, or `CANCELLED`
- `RUN_STARTED` and `PROGRESS_REPORTED` are non-terminal
- If OpenCode exits without a terminal event, the runtime sends `FAILED`
- The agent never creates tickets, branches, commits, pull requests, or Jira comments directly
