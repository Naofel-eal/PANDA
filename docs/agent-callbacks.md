# Agent Callbacks

HTTP contract between the agent container and the orchestrator.

## Transport

All callbacks are sent from the agent container to:

```
POST /internal/agent-events
```

The orchestrator sends commands to the agent at:

```
POST /internal/agent-runs
POST /internal/agent-runs/{agentRunId}/cancel
```

## OpenCode project files

The following files are maintained in `agent/opencode/` and copied into the shared workspace `/workspace/runs` before each run:

| File | Purpose |
|------|---------|
| `AGENTS.md` | Agent instructions and output requirements |
| `opencode.json` | OpenCode configuration (model, steps, permissions) |
| `.opencode/tools/devflow.js` | Devflow tool definitions |
| `.opencode/lib/devflow-constants.js` | Shared constants |
| `.opencode/skills/*/SKILL.md` | Callback skill documentation |

## Event schema

```json
{
  "eventId": "agentRunId:uuid",
  "workflowId": "uuid",
  "agentRunId": "uuid",
  "type": "RUN_STARTED | PROGRESS_REPORTED | INPUT_REQUIRED | COMPLETED | FAILED | CANCELLED",
  "occurredAt": "ISO-8601",
  "summary": "string",
  "blockerType": "string",
  "reasonCode": "string",
  "requestedFrom": "DEV | BUSINESS",
  "resumeTrigger": "string",
  "suggestedComment": "string",
  "artifacts": { ... },
  "details": { ... }
}
```

Required: `eventId`, `workflowId`, `agentRunId`, `type`. All other fields are optional.

## Event reference

### `RUN_STARTED`

Sent by the agent runtime (not OpenCode) after spawning the OpenCode process.

**Effect**: ticket → "In Progress".

```json
{
  "type": "RUN_STARTED",
  "summary": "OpenCode run started for phase IMPLEMENTATION."
}
```

### `PROGRESS_REPORTED`

Sent by OpenCode via `devflow_report_progress` during execution.

**Effect**: log only.

```json
{
  "type": "PROGRESS_REPORTED",
  "summary": "Frontend implementation done, running backend tests."
}
```

### `INPUT_REQUIRED`

Sent by OpenCode via `devflow_request_input` when blocked on missing information.

**Effect**: ticket → "Blocked" + comment posted + `clearRun()`.

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

Sent by OpenCode via `devflow_complete_run` when the run is finished.

**Effect** by phase:

| Phase | Orchestrator action |
|-------|-------------------|
| `INFORMATION_COLLECTION` | Chain to `IMPLEMENTATION` (async, 3s delay) |
| `IMPLEMENTATION` | Commit, push, create/reuse PRs → ticket → "To Review" → `clearRun()` |
| `TECHNICAL_VALIDATION` | `clearRun()` |
| `BUSINESS_VALIDATION` | `clearRun()` |

Important: `COMPLETED` in `IMPLEMENTATION` means "local work is done". The orchestrator creates all `devflow/*` branches and pull requests.

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
        "commitMessage": "feat(health): add health status component [APP-123]",
        "prTitle": "feat(health): add health status component [APP-123]"
      }
    ]
  }
}
```

### `FAILED`

Sent by OpenCode via `devflow_fail_run`, or by the agent runtime as fallback when OpenCode exits without a terminal event.

**Effect**: ticket → "Blocked" + error comment + `clearRun()`.

```json
{
  "type": "FAILED",
  "summary": "Tests fail on a regression.",
  "details": { "failingCommand": "npm test" }
}
```

Fallback example (runtime-generated):

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

Sent by the agent runtime when the orchestrator cancels a run.

**Effect**: `clearRun()`.

```json
{
  "type": "CANCELLED",
  "summary": "Run was cancelled by the orchestrator."
}
```

## Terminal event rules

- Exactly one terminal event per run: `INPUT_REQUIRED`, `COMPLETED`, `FAILED`, or `CANCELLED`
- `RUN_STARTED` and `PROGRESS_REPORTED` are non-terminal
- If OpenCode exits without a terminal event, the runtime sends `FAILED` as fallback
- The agent never creates tickets, branches, commits, PRs, or Jira comments directly
