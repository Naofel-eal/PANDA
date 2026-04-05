# Devflow Event Catalog

Ce document decrit tous les messages structures utiles dans le projet.

Le point le plus important est le suivant:

- tout n'est pas un evenement agent
- toutes les situations ne viennent pas de l'agent
- l'orchestrateur reste le seul proprietaire de l'etat metier

## 1. Deux categories de messages

Dans l'architecture stateless v0, il n'y a pas de workflow signals au sens d'une API. L'intake est entierement polling-based. Les deux categories restantes sont:

### A. Agent commands

Ce sont les commandes envoyees par l'orchestrateur vers le container agent.

Elles disent a l'agent quoi faire.

### B. Agent events

Ce sont les evenements emis par OpenCode vers l'orchestrateur via les outils Devflow.

L'agent les envoie via `POST /internal/agent-events`. L'orchestrateur les traite directement dans `AgentEventService` (pas de persistance, pas de deduplication par store).

## Mapping OpenCode -> evenements HTTP

L'agent appelle explicitement un des outils suivants:

- `devflow_report_progress`
  - envoie `PROGRESS_REPORTED`
- `devflow_request_input`
  - envoie `INPUT_REQUIRED`
  - termine le run courant
- `devflow_complete_run`
  - envoie `COMPLETED`
  - termine le run courant
  - en phase `IMPLEMENTATION`, cela signifie seulement "le travail local est termine"
  - l'orchestrateur inspecte ensuite les repositories du workspace partage
  - l'orchestrateur cree lui-meme les branches `devflow/...` et les pull requests
- `devflow_fail_run`
  - envoie `FAILED`
  - termine le run courant

Le runtime agent lui-meme envoie aussi:

- `RUN_STARTED`
  - des que le process OpenCode a demarre
- `CANCELLED`
  - si l'orchestrateur annule le process et qu'aucun evenement terminal n'a deja ete emis

## 2. Regles de conception

1. Une commande dit "fais ceci".
2. Un evenement dit "voici ce qui s'est passe".
3. L'agent ne change jamais directement l'etat du workflow.
4. L'agent ne commente jamais directement le ticket.
5. L'agent ne fait que declarer un fait structure.
6. L'orchestrateur transforme ce fait en transition d'etat.

## 3. Transport interne

Le transport entre agent et orchestrateur est HTTP interne.

L'orchestrateur appelle le container agent:

- `POST /internal/agent-runs`
- `POST /internal/agent-runs/{agentRunId}/cancel`

Le container agent appelle l'orchestrateur:

- `POST /internal/agent-events`

L'agent n'a pas acces:

- aux secrets Jira/GitHub
- aux APIs externes metier

Il ne peut parler qu'a l'API interne de l'orchestrateur.

## 4. Agent Commands

## `START_RUN`

Quand l'emettre:

- l'orchestrateur veut lancer une execution OpenCode

Pourquoi il existe:

- faire analyser, implementer ou corriger un sujet

Payload minimum:

```json
{
  "type": "START_RUN",
  "workflowId": "wf-456",
  "agentRunId": "run-789",
  "phase": "IMPLEMENTATION",
  "inputSnapshot": {
    "workItemKey": "APP-123",
    "summary": "Add export button"
  }
}
```

## `CANCEL_RUN`

Quand l'emettre:

- annulation manuelle
- timeout
- sujet devenu obsolete

Pourquoi il existe:

- arreter proprement OpenCode

## 5. Agent Events

Ces evenements sont emis par OpenCode via les outils Devflow.

## `RUN_STARTED`

Quand l'emettre:

- le run OpenCode a effectivement commence

Effet dans l'orchestrateur:

- transition du ticket vers "In Progress"

## `PROGRESS_REPORTED`

Quand l'emettre:

- l'agent veut laisser une trace utile de progression

Effet dans l'orchestrateur:

- log seulement, aucune transition

## `INPUT_REQUIRED`

Quand l'emettre:

- l'agent ne peut pas continuer sans information, decision ou clarification externe

Payload minimum:

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

Effet dans l'orchestrateur:

1. transition du ticket vers "Blocked"
2. commentaire sur le ticket par l'orchestrateur
3. `clearRun()`

## `COMPLETED`

Quand l'emettre:

- l'agent a termine sa mission pour ce run

Effet dans l'orchestrateur:

- en phase `INFORMATION_COLLECTION`: chaine vers `IMPLEMENTATION` avec un nouveau run
- en phase `IMPLEMENTATION`: publish PR(s) + transition "To Review" + `clearRun()`
- en phase `TECHNICAL_VALIDATION` ou `BUSINESS_VALIDATION`: `clearRun()`

Payload minimum:

```json
{
  "type": "COMPLETED",
  "summary": "Implementation completed and checks passed.",
  "artifacts": {
    "repoChanges": [
      {
        "repository": "org/frontend-app",
        "commitMessage": "feat(export): add export button [APP-123]",
        "prTitle": "feat(export): add export button [APP-123]"
      }
    ],
    "changedFiles": ["frontend-app/src/ExportButton.tsx"],
    "validationCommands": ["pnpm test"]
  }
}
```

## `FAILED`

Quand l'emettre:

- l'agent rencontre un echec technique ou logique qu'il ne peut pas resoudre seul

Effet dans l'orchestrateur:

- transition du ticket vers "Blocked"
- commentaire d'erreur sur le ticket
- `clearRun()`

## `CANCELLED`

Quand l'emettre:

- le run a ete arrete proprement suite a `CANCEL_RUN`

Effet dans l'orchestrateur:

- `clearRun()`

## 6. Intake par polling (remplace les workflow signals)

Dans v0, il n'y a pas d'endpoint `/api/v1/workflow-signals`. L'intake est entierement gere par:

- `JiraTicketPollingJob`: detecte les tickets "To Do" et "Blocked" avec de nouveaux commentaires
- `GitHubPollingJob`: detecte les commentaires de review et les merges

Ces pollers declenchent directement des runs agent ou des transitions de tickets, sans passer par un systeme de signaux intermediaire.

## 7. Ce qu'il ne faut pas mettre en evenement agent

L'agent declare:

- j'ai commence
- j'avance
- je suis bloque
- j'ai fini
- j'ai echoue

L'orchestrateur decide:

- quelle transition appliquer
- quel commentaire ecrire
- quel statut Jira changer
