# Devflow Architecture

## Objectif

Construire un orchestrateur Quarkus en architecture hexagonale capable de suivre un besoin metier depuis un ticket jusqu'a la validation technique, et eventuellement la validation metier, sans dependre d'un outil particulier comme Jira, GitHub, GitLab ou d'un environnement de developpement local permanent.

## Architecture v0 (stateless)

L'implementation actuelle est entierement stateless:

- pas de base de donnees (PostgreSQL supprime)
- pas de store in-memory replicant une base
- un seul champ `volatile RunContext currentRun` dans `DevFlowRuntime`
- polling-only: Jira et GitHub sont interroges periodiquement
- dispatch direct: pas d'outbox, pas de scheduler dispatcher
- pas de deduplication par store — l'idempotence repose sur les statuts Jira/GitHub

### Compromis v0

- si l'orchestrateur redemarre pendant un run actif, `currentRun` est perdu
- le ticket reste dans son statut Jira actuel ("In Progress") jusqu'a une intervention manuelle
- un seul agent run a la fois, impose par la reference volatile

### Protection contre les runs bloques

Deux mecanismes de timeout en defense-en-profondeur empechent un run bloque de paralyser le systeme:

1. **Agent runtime timeout** (layer 1, primaire) — le runtime Node.js tue le process OpenCode apres `maxRunDurationMs` (15 minutes par defaut). Le process exit declenche le callback `FAILED` en fallback.
2. **Orchestrator stale-run detection** (layer 2, backup) — les polling jobs verifient `DevFlowRuntime.isStale()` avant de skip pour `isBusy()`. Si un run depasse `max-run-duration-minutes` (20 minutes par defaut, configurable via `AGENT_MAX_RUN_DURATION_MINUTES`), l'orchestrateur envoie une commande `cancelRun()` a l'agent et nettoie `currentRun` localement.

Les deux timers sont decales (15 min agent, 20 min orchestrateur) pour que le layer 1 agisse en premier dans le cas normal. Le layer 2 ne se declenche que si l'agent runtime lui-meme est injoignable ou bloque.

### Topologie

```
orchestrator (Quarkus)
├── DevFlowRuntime (volatile currentRun)
├── JiraTicketPollingJob (poll every 1 min)
├── GitHubPollingJob (poll every 1 min)
├── AgentEventService (callback handler)
└── WorkItemTransitionService (direct Jira transitions)

agent (Node.js + OpenCode)
├── HTTP server (POST /internal/agent-runs, cancel)
├── OpenCode process
└── devflow.js tools → POST /internal/agent-events
```

## Principes de conception

1. Le domaine ne connait aucun fournisseur externe.
2. Tous les acces externes passent par des ports.
3. Chaque integration est un adapter remplacable.
4. Le workflow est pilote par les transitions de statut Jira.
5. L'etat courant est re-decouvert a chaque cycle de polling depuis Jira et GitHub.
6. Un ticket bloque reste dans son statut Jira ("Blocked") jusqu'a ce qu'un nouveau commentaire le fasse reprendre.

Le coeur raisonne avec des concepts neutres:

- `WorkItem` au lieu de ticket Jira
- `CodeChangeRef` au lieu de PR GitHub
- `IncomingComment` au lieu de commentaire GitHub/Jira

## Vue hexagonale

### Coeur

- `domain`
  - objets metier (WorkItem, CodeChangeRef, IncomingComment)
  - enums metier (WorkflowPhase, BlockerType, RequestedFrom, ResumeTrigger)
  - exceptions metier
- `application`
  - use cases (AgentEventService)
  - services applicatifs (EligibilityService, WorkItemTransitionService, WorkspaceLayoutService)
  - runtime state (DevFlowRuntime, RunContext)
  - ports (TicketingPort, CodeHostPort, AgentRuntimePort)

### Adapters entrants

- callback HTTP agent (`AgentEventResource`)
- scheduler de polling (`JiraTicketPollingJob`, `GitHubPollingJob`)

### Adapters sortants

- ticketing: Jira (`JiraTicketingAdapter`)
- code host: GitHub (`GitHubCodeHostAdapter`)
- agent runtime: HTTP (`HttpAgentRuntimeClient`)
- git workspace: local filesystem

## Secrets

- Jira/GitHub restent cote `orchestrator`
- l'agent n'a pas acces aux credentials metier
- l'agent ne peut signaler son etat que via `POST /internal/agent-events`
- le token GitHub n'est pas injecte dans le container agent
- les remotes Git du workspace partage restent sans credentials embarques
- seul l'orchestrateur injecte l'authentification GitHub au moment d'executer `clone`, `fetch` et `push`

## Workspace partage

Le workspace est monte dans les deux containers sous `/workspace/runs`.

```text
/workspace/runs/
├─ AGENTS.md
├─ opencode.json
├─ .opencode/
│  ├─ skills/
│  ├─ tools/
│  └─ lib/
├─ repo-a/
└─ repo-b/
```

Principes:

- le root du workspace est aussi le projet OpenCode effectif de l'agent
- les repositories sont clones directement sous `/workspace/runs`
- avant chaque run, le runtime agent copie le template `agent/opencode` vers `/workspace/runs`
- au demarrage d'un nouveau ticket, chaque repository est mis a jour depuis sa branche source configuree
- apres un commentaire de review sur une PR existante, le repository concerne est re-checkoute sur la branche de travail precedemment publiee
- apres publication, chaque repository est reset sur sa branche source

Le chemin effectif est injecte dans `inputSnapshot.workspace` a chaque lancement.

## Implementation actuelle du repository

Le repository n'est pas decoupe en multi-modules Gradle. La version actuelle privilegie une livraison executable rapide:

- un module Quarkus unique pour l'orchestrateur
- un runtime agent Node/OpenCode sous `agent/`
- une orchestration Docker Compose sous `compose.yaml`

Le decoupage hexagonal est aligne sur trois dossiers racine:

- `domain` — objets metier, enums, exceptions
- `application` — use cases, services, ports, runtime
- `infrastructure` — ticketing/jira, codehost/github, agent/opencode, http

Les integrations implementees sont:

- Jira
  - polling des tickets par epic et statut
  - ajout de commentaire de ticket
  - transitions de statut
- GitHub
  - polling des PRs ouvertes et fermees sur les branches `devflow/*`
  - publication locale `git` + creation de pull request via API GitHub
  - detection de merge avec transition automatique
- OpenCode
  - lancement de run par HTTP
  - callback d'evenements agent par HTTP
  - instructions agent dans `AGENTS.md` (copiees dans le workspace)

## Ports metier

### Ports entrants

- `AgentEventResource`
  - recoit les evenements structures emis par l'agent runtime

### Ports sortants

- `TicketingPort`
  - lire un item
  - lister les commentaires
  - commenter
  - changer le statut externe
- `CodeHostPort`
  - cloner/puller
  - preparer le workspace
  - publier les code changes (commit, push, PR)
  - lister les repositories configurees
- `AgentRuntimePort`
  - lancer une execution agent
  - annuler une execution agent

## Flux nominal (stateless)

1. `JiraTicketPollingJob` interroge Jira pour les tickets "To Do" dans l'epic.
2. Pour chaque ticket, `EligibilityService` evalue si le ticket est exploitable.
3. Si eligible et `!runtime.isBusy()`, un run agent est lance directement via `AgentRuntimePort`.
4. `DevFlowRuntime.startRun()` enregistre le `RunContext` volatile.
5. L'agent execute OpenCode et envoie des evenements via `POST /internal/agent-events`.
6. `AgentEventService` gere les evenements:
   - `RUN_STARTED` → transition "In Progress"
   - `INPUT_REQUIRED` → commentaire Jira + transition "Blocked" + `clearRun()`
   - `COMPLETED(info_collection)` → chaine vers IMPLEMENTATION avec nouveau run
   - `COMPLETED(implementation)` → publish PR + transition "To Review" + `clearRun()`
   - `FAILED` → commentaire Jira + transition "Blocked" + `clearRun()`
   - `CANCELLED` → `clearRun()`
7. `GitHubPollingJob` detecte les merges et les commentaires de review.

## Communication orchestrateur-agent

Le modele est `commande -> execution -> evenements`:

- l'orchestrateur appelle le container agent en HTTP (`POST /internal/agent-runs`)
- le container agent execute OpenCode
- OpenCode utilise des outils qui envoient des appels HTTP vers l'orchestrateur (`POST /internal/agent-events`)
- l'orchestrateur gere les transitions directement (pas de persistance intermediaire)

L'agent:

- n'a pas acces a Jira, GitHub ou aux secrets metier
- ne peut parler qu'a l'API interne de l'orchestrateur

## Catalogue d'evenements

Le catalogue detaille des evenements et commandes est decrit dans `docs/events.md`.
