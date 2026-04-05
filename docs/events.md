# Devflow Event Catalog

Ce document decrit tous les messages structures utiles dans le projet.

Note d'implementation actuelle:

- les signaux Jira et GitHub sont actuellement produits par polling
- les types d'evenements metier restent identiques

Le point le plus important est le suivant:

- tout n'est pas un evenement agent
- toutes les situations ne viennent pas de l'agent
- l'orchestrateur reste le seul proprietaire de l'etat metier

## 1. Trois categories differentes

### A. Workflow signals

Ce sont les signaux entrants que l'orchestrateur recoit depuis l'exterieur:

- ticketing
- code host
- deployment
- scheduler
- admin API

Ils sont traites par `WorkflowSignalHandler`.

### B. Agent commands

Ce sont les commandes envoyees par l'orchestrateur vers OpenCode.

Elles disent a l'agent quoi faire.

### C. Agent events

Ce sont les evenements emis par OpenCode vers l'orchestrateur via les outils Devflow.

Dans l'architecture cible:

- OpenCode tourne dans le container agent
- les outils Devflow envoient un `POST /internal/agent-events` vers l'orchestrateur
- l'orchestrateur persiste ces evenements dans PostgreSQL
- l'orchestrateur deduplique par `eventId`

## 2.c Mapping OpenCode -> evenements HTTP

Dans l'implementation actuelle, l'agent n'ecrit pas du JSON libre dans sa reponse finale.

Il appelle explicitement un des outils suivants:

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

## 2.b Transport interne

Le transport entre agent et orchestrateur est HTTP interne.

L'orchestrateur appelle le container agent:

- `POST /internal/agent-runs`
- `POST /internal/agent-runs/{agentRunId}/cancel`

Le container agent appelle l'orchestrateur:

- `POST /internal/agent-events`

L'agent n'a pas acces:

- a PostgreSQL
- aux secrets Jira/GitHub/GitLab
- aux APIs externes metier

Il ne peut parler qu'a l'API interne de l'orchestrateur.

## 3. Workflow Signals

## `WORK_ITEM_DISCOVERED`

Quand l'emettre:

- un ticket eligible est detecte par polling
- un webhook annonce un nouveau ticket dans le scope de Devflow

Pourquoi il existe:

- creer ou retrouver un workflow pour un ticket
- lancer l'analyse d'eligibilite et de completude

Payload minimum:

```json
{
  "type": "WORK_ITEM_DISCOVERED",
  "sourceSystem": "jira",
  "workItemKey": "APP-123",
  "url": "https://jira.example.com/browse/APP-123"
}
```

Effet habituel:

- creation du `workflow_instance` si absent
- evaluation des regles d'eligibilite
- creation d'un `AgentRun` de phase `INFORMATION_COLLECTION` si le ticket est exploitable

## `WORK_ITEM_UPDATED`

Quand l'emettre:

- le titre, la description, les labels ou les champs du ticket changent

Pourquoi il existe:

- un ticket bloque peut devenir complet sans nouveau commentaire
- un ticket peut sortir ou entrer dans le scope

Effet habituel:

- re-evaluation de la completude
- re-evaluation de l'eligibilite

## `WORK_ITEM_COMMENT_RECEIVED`

Quand l'emettre:

- un nouveau commentaire apparait sur le ticket

Pourquoi il existe:

- lever un blocage d'information
- lever un blocage metier
- capturer un retour qui necessite une nouvelle implementation

Effet habituel:

- si le workflow est `WAITING_EXTERNAL_INPUT`, on teste la levee du `Blocker`
- sinon le commentaire est juste historise

## `WORK_ITEM_STATUS_CHANGED`

Quand l'emettre:

- le statut externe du ticket change

Pourquoi il existe:

- detecter une annulation manuelle
- detecter une cloture manuelle
- detecter un deplacement manuel de colonne susceptible d'impacter le workflow

Effet habituel:

- annulation
- cloture
- reconciliation

## `CODE_CHANGE_REVIEW_COMMENT_RECEIVED`

Quand l'emettre:

- un commentaire de review technique apparait sur une PR ou MR

Pourquoi il existe:

- lancer une analyse du commentaire
- decider si l'agent peut corriger ou s'il faut demander une precision

Effet habituel:

- creation d'un nouveau `AgentRun` en phase `TECHNICAL_VALIDATION`
- ou simple attente si le commentaire n'appelle pas d'action

## `CODE_CHANGE_MERGED`

Quand l'emettre:

- la PR ou MR est mergee

Pourquoi il existe:

- faire passer le workflow a l'etape suivante

Effet habituel:

- si toutes les PRs attendues du workflow sont mergees: entree en `BUSINESS_VALIDATION`
- sinon attente des autres merges ou d'autres retours de review

## `CODE_CHANGE_CLOSED_UNMERGED`

Quand l'emettre:

- la PR ou MR est fermee sans merge

Pourquoi il existe:

- detecter un abandon ou une action manuelle

Effet habituel:

- `BLOCKED`
- `FAILED`
- ou `CANCELLED`

La reaction exacte depend de ta politique.

## `DEPLOYMENT_AVAILABLE`

Quand l'emettre:

- l'environnement de validation est pret
- ou le merge sur une branche donnee vaut disponibilite metier

Pourquoi il existe:

- entrer en validation metier

Effet habituel:

- notification validation
- passage du ticket en statut de validation

## `BUSINESS_VALIDATION_REPORTED`

Quand l'emettre:

- une validation metier aboutit
- une validation metier est refusee

Payload minimum:

```json
{
  "type": "BUSINESS_VALIDATION_REPORTED",
  "workflowId": "wf-456",
  "result": "REJECTED",
  "summary": "The export format is not the expected one."
}
```

Pourquoi il existe:

- terminer le workflow
- ou renvoyer le sujet en implementation

Effet habituel:

- `DONE` si `APPROVED`
- nouveau run si `REJECTED` et assez d'information
- `WAITING_EXTERNAL_INPUT` si retour ambigu

## `RECONCILIATION_REQUESTED`

Quand l'emettre:

- tick periodique du scheduler
- action admin
- reprise apres redemarrage

Pourquoi il existe:

- verifier que PostgreSQL et les outils externes sont coherents

Effet habituel:

- relecture du ticket, de la PR, du statut de merge, du blocage en cours

## `MANUAL_RESUME_REQUESTED`

Quand l'emettre:

- un humain veut forcer la reprise d'un workflow

Pourquoi il existe:

- debloquer un cas ou le signal externe a ete manque

Effet habituel:

- reevaluation immediate du workflow

## `MANUAL_CANCEL_REQUESTED`

Quand l'emettre:

- un humain veut annuler le workflow

Pourquoi il existe:

- abandon manuel du sujet

Effet habituel:

- annulation du run actif s'il existe
- passage du workflow en `CANCELLED`

## 4. Agent Commands

Dans la V1, les commandes sont minimalistes.

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
- workflow deja resolu autrement

Pourquoi il existe:

- arreter proprement OpenCode

Remarque:

- je ne recommande pas `RESUME_RUN` en V1
- il est plus simple de recreer un nouveau `AgentRun` avec un nouveau `inputSnapshot`

## 5. Agent Events

Ces evenements sont emis par OpenCode via les outils Devflow.

## `RUN_STARTED`

Quand l'emettre:

- le run OpenCode a effectivement commence

Pourquoi il existe:

- confirmer que le run n'est plus seulement planifie

Effet habituel:

- `agent_run.status = RUNNING`

## `PROGRESS_REPORTED`

Quand l'emettre:

- l'agent veut laisser une trace utile de progression

Pourquoi il existe:

- audit
- debug
- observation

Effet habituel:

- aucun changement de phase
- historisation uniquement

Exemples de progression utiles:

- repository cloned
- branch created
- tests started
- pull request draft prepared

## `INPUT_REQUIRED`

Quand l'emettre:

- l'agent ne peut pas continuer sans information, decision ou clarification externe

Pourquoi il existe:

- ouvrir un `Blocker`
- demander un commentaire sur le ticket
- suspendre le workflow proprement

Payload minimum:

```json
{
  "type": "INPUT_REQUIRED",
  "blockerType": "MISSING_TICKET_INFORMATION",
  "reasonCode": "ACCEPTANCE_CRITERIA_AMBIGUOUS",
  "summary": "The expected behavior is ambiguous.",
  "requestedFrom": "BUSINESS",
  "resumeTrigger": "NEW_COMMENT_ON_WORK_ITEM",
  "suggestedComment": "Can you clarify the expected export scope?"
}
```

Effet habituel:

1. creation d'un `workflow_blocker`
2. passage du workflow en `WAITING_EXTERNAL_INPUT`
3. commentaire sur le ticket par l'orchestrateur
4. fin du run en cours

Situations typiques:

- criteres d'acceptation ambigus
- mapping repository introuvable
- commentaire de review technique ambigu
- retour metier insuffisamment precis

## `COMPLETED`

Quand l'emettre:

- l'agent a termine sa mission pour ce run

Pourquoi il existe:

- faire avancer le workflow a l'etape suivante

Payload minimum:

```json
{
  "type": "COMPLETED",
  "summary": "Implementation completed and checks passed.",
  "artifacts": {
    "repositories": ["org/frontend-app"],
    "branches": ["feature/APP-123-export"],
    "codeChanges": [
      "https://github.com/org/frontend-app/pull/42"
    ]
  }
}
```

Effet habituel:

- fin du `agent_run`
- mise a jour des references externes
- passage a `TECHNICAL_VALIDATION` ou etape suivante

## `FAILED`

Quand l'emettre:

- l'agent rencontre un echec technique ou logique qu'il ne peut pas resoudre seul

Pourquoi il existe:

- distinguer un vrai echec d'un simple besoin d'information

Payload minimum:

```json
{
  "type": "FAILED",
  "reasonCode": "TESTS_UNSTABLE",
  "summary": "The test suite is failing for unrelated reasons and the run cannot continue safely."
}
```

Effet habituel:

- fin du `agent_run`
- passage du workflow en `BLOCKED` ou `FAILED`
- notification equipe dev si necessaire

Situations typiques:

- environnement casse
- permissions insuffisantes
- dependance externe indisponible
- corruption du workspace

## `CANCELLED`

Quand l'emettre:

- le run a ete arrete proprement suite a `CANCEL_RUN`

Pourquoi il existe:

- tracer la fin effective d'un run annule

Effet habituel:

- `agent_run.status = CANCELLED`

## 6. Situations possibles et evenement attendu

## Nouveau ticket detecte

- signal: `WORK_ITEM_DISCOVERED`
- decision par l'orchestrateur: creer ou ignorer le workflow

## Ticket incomplet

- commande agent: `START_RUN` en `INFORMATION_COLLECTION` ou evaluation locale
- evenement agent: `INPUT_REQUIRED`
- reaction orchestrateur: commentaire ticket + `WAITING_EXTERNAL_INPUT`

## Ticket complete apres commentaire

- signal: `WORK_ITEM_COMMENT_RECEIVED` ou `WORK_ITEM_UPDATED`
- reaction orchestrateur: lever le `Blocker`
- suite: nouveau `START_RUN`

## Sujet non eligible

- signal: `WORK_ITEM_DISCOVERED` ou `WORK_ITEM_UPDATED`
- pas d'evenement agent necessaire
- decision par l'orchestrateur: ignorer ou marquer hors scope

## Aucun environnement d'execution disponible

- pas d'evenement agent necessaire si l'agent n'a pas encore ete lance
- decision par l'orchestrateur: `BLOCKED` avec `NO_EXECUTION_ENVIRONMENT`

## Commentaire de review technique

- signal: `CODE_CHANGE_REVIEW_COMMENT_RECEIVED`
- suite: nouveau `START_RUN` en `TECHNICAL_VALIDATION`
- si ambigu: `INPUT_REQUIRED`
- sinon: `COMPLETED` apres correction

## PR mergee

- signal: `CODE_CHANGE_MERGED`
- pas d'evenement agent necessaire
- decision par l'orchestrateur: `DONE` ou validation metier

## Validation metier refusee

- signal: `BUSINESS_VALIDATION_REPORTED` ou `WORK_ITEM_COMMENT_RECEIVED`
- si assez d'information: nouveau `START_RUN`
- sinon: `INPUT_REQUIRED`

## Annulation manuelle

- signal: `MANUAL_CANCEL_REQUESTED` ou `WORK_ITEM_STATUS_CHANGED`
- commande agent: `CANCEL_RUN` si besoin
- evenement agent: `CANCELLED`

## Redemarrage de l'application

- signal: `RECONCILIATION_REQUESTED`
- reaction orchestrateur: relire PostgreSQL et etats externes

## 7. Ce qu'il ne faut pas mettre en evenement agent

Ne mets pas dans l'agent:

- le changement de statut metier du workflow
- l'ecriture directe dans le ticket
- la creation directe du `Blocker` en base workflow
- la decision de marquer `DONE`, `FAILED`, `CANCELLED`

L'agent declare:

- j'ai commence
- j'avance
- je suis bloque
- j'ai fini
- j'ai echoue

L'orchestrateur decide:

- quelle transition appliquer
- quel commentaire ecrire
- quel etat persister

## 8. Ensemble minimal recommande pour la V1

Si tu veux partir tres simple, tu peux commencer avec seulement:

### Workflow signals

- `WORK_ITEM_DISCOVERED`
- `WORK_ITEM_COMMENT_RECEIVED`
- `CODE_CHANGE_REVIEW_COMMENT_RECEIVED`
- `CODE_CHANGE_MERGED`
- `DEPLOYMENT_AVAILABLE`
- `BUSINESS_VALIDATION_REPORTED`
- `RECONCILIATION_REQUESTED`

### Agent commands

- `START_RUN`
- `CANCEL_RUN`

### Agent events

- `RUN_STARTED`
- `PROGRESS_REPORTED`
- `INPUT_REQUIRED`
- `COMPLETED`
- `FAILED`
- `CANCELLED`

Ce set couvre deja toutes les situations critiques de ton workflow.
