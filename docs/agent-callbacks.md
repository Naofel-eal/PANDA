# Agent Callbacks

Ce document decrit exactement les callbacks HTTP que l'agent envoie a l'orchestrateur.

Les fichiers OpenCode associes a ces callbacks sont centralises sous [agent/opencode](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode), puis copies dans le workspace partage `/workspace/runs` avant chaque run:

- [AGENTS.md](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/AGENTS.md)
- [opencode.json](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/opencode.json)
- [devflow.js](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/.opencode/tools/devflow.js)

Tous les callbacks partent du container agent vers:

- `POST /internal/agent-events`

Le schema HTTP accepte la structure suivante:

```json
{
  "eventId": "run-id:uuid",
  "workflowId": "uuid",
  "agentRunId": "uuid",
  "type": "RUN_STARTED | PROGRESS_REPORTED | INPUT_REQUIRED | COMPLETED | FAILED | CANCELLED",
  "occurredAt": "2026-04-03T10:15:00Z",
  "providerRunRef": "optional",
  "summary": "optional",
  "blockerType": "optional",
  "reasonCode": "optional",
  "requestedFrom": "optional",
  "resumeTrigger": "optional",
  "suggestedComment": "optional",
  "artifacts": {},
  "details": {}
}
```

## Champs obligatoires

- `eventId`
- `workflowId`
- `agentRunId`
- `type`

## Champs optionnels

- `occurredAt`
- `providerRunRef`
- `summary`
- `blockerType`
- `reasonCode`
- `requestedFrom`
- `resumeTrigger`
- `suggestedComment`
- `artifacts`
- `details`

## Evenements envoyes par l'agent

## Mapping callback -> emission

- `RUN_STARTED`
  - emis par le runtime agent
  - pas de skill OpenCode
- `PROGRESS_REPORTED`
  - emis par OpenCode via [devflow-report-progress](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/.opencode/skills/devflow-report-progress/SKILL.md)
- `INPUT_REQUIRED`
  - emis par OpenCode via [devflow-request-input](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/.opencode/skills/devflow-request-input/SKILL.md)
- `COMPLETED`
  - emis par OpenCode via [devflow-complete-run](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/.opencode/skills/devflow-complete-run/SKILL.md)
- `FAILED`
  - emis par OpenCode via [devflow-fail-run](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode/.opencode/skills/devflow-fail-run/SKILL.md)
  - ou par le runtime agent en fallback si OpenCode sort sans evenement terminal
- `CANCELLED`
  - emis par le runtime agent
  - pas de skill OpenCode

## `RUN_STARTED`

Qui l'envoie:

- le runtime agent

Quand:

- juste apres le demarrage du process OpenCode

But:

- dire a l'orchestrateur que le run est effectivement parti

Exemple:

```json
{
  "eventId": "run-123:5d3d4f3d-2661-42dd-9d76-c0f5c8aa9b4a",
  "workflowId": "d5a2d1c8-cb18-4e73-a634-b5f6dce8b31f",
  "agentRunId": "6d37aabd-9e8f-4e03-a0b2-89dcf0cc0f37",
  "type": "RUN_STARTED",
  "occurredAt": "2026-04-03T10:15:00Z",
  "providerRunRef": "opencode:6d37aabd-9e8f-4e03-a0b2-89dcf0cc0f37",
  "summary": "OpenCode run started for phase IMPLEMENTATION."
}
```

## `PROGRESS_REPORTED`

Qui l'envoie:

- OpenCode via `devflow_report_progress`

Quand:

- pendant l'execution

But:

- donner une trace lisible de l'avancement sans terminer le run

Champs utilises:

- `summary`
- `details`

Exemple:

```json
{
  "eventId": "run-123:0fd0ce7d-3bc1-4c74-96ba-2a874b8a9d25",
  "workflowId": "d5a2d1c8-cb18-4e73-a634-b5f6dce8b31f",
  "agentRunId": "6d37aabd-9e8f-4e03-a0b2-89dcf0cc0f37",
  "type": "PROGRESS_REPORTED",
  "occurredAt": "2026-04-03T10:17:00Z",
  "summary": "Frontend terminee, validation backend en cours.",
  "details": {
    "step": "validation"
  }
}
```

## `INPUT_REQUIRED`

Qui l'envoie:

- OpenCode via `devflow_request_input`

Quand:

- quand il manque une information externe

But:

- demander a l'orchestrateur de bloquer le workflow
- demander a l'orchestrateur de commenter le ticket

Champs utilises:

- `blockerType`
- `reasonCode`
- `summary`
- `requestedFrom`
- `resumeTrigger`
- `suggestedComment`
- `details`

Exemple:

```json
{
  "eventId": "run-123:660431f4-f36e-4cdc-b8d2-6c79bb4b9cdb",
  "workflowId": "d5a2d1c8-cb18-4e73-a634-b5f6dce8b31f",
  "agentRunId": "6d37aabd-9e8f-4e03-a0b2-89dcf0cc0f37",
  "type": "INPUT_REQUIRED",
  "occurredAt": "2026-04-03T10:19:00Z",
  "blockerType": "MISSING_TICKET_INFORMATION",
  "reasonCode": "ACCEPTANCE_CRITERIA_AMBIGUOUS",
  "summary": "Le comportement attendu est ambigu.",
  "requestedFrom": "BUSINESS",
  "resumeTrigger": "WORK_ITEM_COMMENT_RECEIVED",
  "suggestedComment": "Peux-tu preciser si l export doit contenir uniquement les donnees filtrees ou toutes les donnees ?",
  "details": {
    "options": ["filtered", "all"]
  }
}
```

## `COMPLETED`

Qui l'envoie:

- OpenCode via `devflow_complete_run`

Quand:

- quand le run est termine avec succes

But:

- signaler que le travail local est fini

Point important:

- en phase `IMPLEMENTATION`, `COMPLETED` ne veut pas dire que l'agent a cree les branches ou les pull requests
- cela veut dire que le travail local est termine
- l'orchestrateur inspecte ensuite les repositories modifies, cree les branches `devflow/...`, commit, push et ouvre les PRs
- apres l'ACK HTTP de l'orchestrateur, le process OpenCode se termine et un nouveau process sera relance pour le ticket suivant ou la prochaine reprise

Champs utilises:

- `summary`
- `artifacts`
- `details`

Exemple:

```json
{
  "eventId": "run-123:b1d7227b-4c84-406a-a2a6-d54bf4f9b0b2",
  "workflowId": "d5a2d1c8-cb18-4e73-a634-b5f6dce8b31f",
  "agentRunId": "6d37aabd-9e8f-4e03-a0b2-89dcf0cc0f37",
  "type": "COMPLETED",
  "occurredAt": "2026-04-03T10:25:00Z",
  "summary": "Implementation locale terminee pour le frontend et le backend.",
  "artifacts": {
    "changedFiles": [
      "frontend-app/src/ExportButton.tsx",
      "backend-app/src/export/service.ts"
    ],
    "validationCommands": [
      "pnpm test",
      "./gradlew test"
    ],
    "repoChanges": [
      {
        "repository": "my-org/frontend-app",
        "commitMessage": "APP-123: add export button",
        "prTitle": "APP-123 - frontend export"
      },
      {
        "repository": "my-org/backend-app",
        "commitMessage": "APP-123: add export endpoint",
        "prTitle": "APP-123 - backend export endpoint"
      }
    ]
  },
  "details": {
    "followUpNotes": "Backend endpoint assumes existing auth middleware."
  }
}
```

## `FAILED`

Qui l'envoie:

- OpenCode via `devflow_fail_run`
- ou le runtime agent en fallback si OpenCode sort sans evenement terminal

Quand:

- quand le run echoue

But:

- faire passer le run et le workflow en echec
- terminer le process OpenCode apres l'ACK HTTP de l'orchestrateur

Exemple explicite:

```json
{
  "eventId": "run-123:20c52d3f-e2a2-4d7d-9ee5-901dbaf4cc95",
  "workflowId": "d5a2d1c8-cb18-4e73-a634-b5f6dce8b31f",
  "agentRunId": "6d37aabd-9e8f-4e03-a0b2-89dcf0cc0f37",
  "type": "FAILED",
  "occurredAt": "2026-04-03T10:27:00Z",
  "summary": "Les tests echouent sur une regression non comprise.",
  "details": {
    "failingCommand": "pnpm test"
  }
}
```

Exemple fallback runtime:

```json
{
  "eventId": "run-123:50b7b52c-a994-4ab9-a9fd-6c9b3a5b9ce8",
  "workflowId": "d5a2d1c8-cb18-4e73-a634-b5f6dce8b31f",
  "agentRunId": "6d37aabd-9e8f-4e03-a0b2-89dcf0cc0f37",
  "type": "FAILED",
  "occurredAt": "2026-04-03T10:28:00Z",
  "summary": "OpenCode exited with code 1.",
  "details": {
    "exitCode": 1,
    "signal": null,
    "stdoutTail": "...",
    "stderrTail": "..."
  }
}
```

## `CANCELLED`

Qui l'envoie:

- le runtime agent

Quand:

- quand l'orchestrateur annule un run actif

But:

- fermer proprement le run
- terminer le process OpenCode

Exemple:

```json
{
  "eventId": "run-123:0d66c2f9-f3d4-4ee7-94db-6d431ab0f15d",
  "workflowId": "d5a2d1c8-cb18-4e73-a634-b5f6dce8b31f",
  "agentRunId": "6d37aabd-9e8f-4e03-a0b2-89dcf0cc0f37",
  "type": "CANCELLED",
  "occurredAt": "2026-04-03T10:29:00Z",
  "summary": "Run 6d37aabd-9e8f-4e03-a0b2-89dcf0cc0f37 was cancelled by the orchestrator.",
  "details": {
    "exitCode": null,
    "signal": "SIGTERM",
    "stdoutTail": "...",
    "stderrTail": "..."
  }
}
```

## Regles importantes

- un seul evenement terminal doit exister par run:
  - `INPUT_REQUIRED`
  - `COMPLETED`
  - `FAILED`
  - `CANCELLED`
- `RUN_STARTED` et `PROGRESS_REPORTED` sont non terminaux
- l'agent ne cree jamais directement de ticket, branche, commit, push ou pull request
- l'agent decrit ce qui s'est passe
- l'orchestrateur transforme ce fait en transition metier et en actions externes
