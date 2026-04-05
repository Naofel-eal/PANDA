# Devflow Architecture

## Objectif

Construire un orchestrateur Quarkus en architecture hexagonale capable de suivre un besoin metier depuis un ticket jusqu'a la validation technique, et eventuellement la validation metier, sans dependre d'un outil particulier comme Jira, GitHub, GitLab ou d'un environnement de developpement local permanent.

Note d'implementation actuelle:

- le mode minimal actuellement implemente pour ton projet est un mode polling-only
- Jira est interroge periodiquement sur un epic cible et un statut cible
- GitHub est interroge periodiquement pour les pull requests deja suivies par Devflow

L'implementation presente dans ce repository suit cette architecture avec la topologie concrete suivante:

- `postgres` pour la persistance
- `orchestrator` Quarkus pour l'etat metier
- `agent` OpenCode pour l'execution des runs
- un workspace partage monte dans `orchestrator` et `agent`

Le coeur doit raisonner avec des concepts neutres:

- `WorkItem` au lieu de ticket Jira
- `CodeChange` au lieu de PR/MR GitHub/GitLab
- `ReviewComment` au lieu de commentaire GitHub
- `ValidationStep` au lieu de workflow Jira
- `ExecutionEnvironment` au lieu de poste de dev local

## Principes de conception

1. Le domaine ne connait aucun fournisseur externe.
2. Tous les acces externes passent par des ports.
3. Chaque integration est un adapter remplacable.
4. Le workflow est pilote par la configuration.
5. L'etat courant est stocke en base relationnelle.
6. L'historique est conserve dans un journal d'evenements.
7. Un ticket bloque n'est jamais "perdu": il reste correle a un workflow et peut etre repris sur un nouvel evenement.

## Vue hexagonale

### Coeur

- `domain`
  - agregats
  - objets metier
  - etats
  - regles de transition
  - types de blocage
- `application`
  - cas d'usage
  - orchestration
  - evaluation des politiques
  - correlation des evenements
  - emission de commandes

### Adapters entrants

- webhook ticketing
- webhook code host
- webhook deployment
- callback HTTP agent
- scheduler de polling
- CLI ou API admin

### Adapters sortants

- ticketing: Jira, Linear, Azure Boards, custom
- code host: GitHub, GitLab, Bitbucket
- git workspace: local, container, runner distant
- agent runtime: container OpenCode appele en HTTP par l'orchestrateur
- validation/deploiement: CI/CD, environment watcher
- notification: email, Slack, Teams, commentaire ticket
- persistence: PostgreSQL

## Topologie conteneurisee mise en place

### Reseaux

- `control`
  - `orchestrator <-> agent`
- `data`
  - `orchestrator <-> postgres`
- `egress`
  - acces sortant pour l'orchestrateur
  - acces sortant optionnel pour l'agent si OpenCode utilise un provider LLM heberge

### Secrets

- Jira/GitHub/GitLab/PostgreSQL restent cote `orchestrator`
- l'agent n'a pas acces a PostgreSQL
- l'agent ne voit pas les credentials metier
- l'agent ne peut signaler son etat que via `POST /internal/agent-events`
- le token GitHub n'est pas injecte dans le container agent
- les remotes Git du workspace partage restent sans credentials embarques
- seul l'orchestrateur injecte l'authentification GitHub au moment d'executer `clone`, `fetch` et `push`

### Workspace partage

Le workspace est monte dans les deux containers sous `/workspace/runs`.

Le layout concret maintenant implemente est le suivant:

```text
/workspace/runs/
├─ AGENTS.md
├─ opencode.json
├─ .opencode/
│  ├─ skills/
│  └─ tools/
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

## Modules recommandes

Pour Quarkus, je recommande un decoupage simple en plusieurs modules Maven/Gradle:

- `devflow-domain`
- `devflow-application`
- `devflow-adapters-inbound-rest`
- `devflow-adapters-inbound-agent-callback`
- `devflow-adapters-inbound-scheduler`
- `devflow-adapters-outbound-persistence-postgres`
- `devflow-adapters-outbound-ticketing-jira`
- `devflow-adapters-outbound-codehost-github`
- `devflow-adapters-outbound-codehost-gitlab`
- `devflow-adapters-outbound-agent-http`
- `devflow-adapters-outbound-workspace-local`
- `devflow-adapters-outbound-workspace-container`
- `devflow-bootstrap-quarkus`

Version 1 pragmatique:

- garde les adapters comme modules Maven/Gradle normaux
- expose des interfaces stables
- charge les implementations via CDI Quarkus

Tu n'as pas besoin d'un systeme de plugins dynamique en V1. Pour l'opensource, publier des SPI propres suffit largement.

## Implementation actuelle du repository

Le repository n'est pas encore decoupe en multi-modules Gradle. La version actuelle privilegie une livraison executable rapide:

- un module Quarkus unique pour l'orchestrateur
- un runtime agent Node/OpenCode sous `agent/`
- une orchestration Docker Compose sous [compose.yaml](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/compose.yaml)

Le decoupage hexagonal est maintenant aligne sur trois dossiers racine:

- `domain`
  - objets metier
  - enums metier
  - exceptions metier
- `application`
  - use cases
  - services applicatifs
  - ports a implementer par l'infrastructure
  - ports de persistance pour isoler PostgreSQL
- `infrastructure`
  - `persistence/postgres`
  - `ticketing/jira`
  - `codehost/github`
  - `agent/opencode`
  - `http`
  - `scheduler`
  - `support`

Regle d'implementation actuellement respectee:

- `domain` et `application` ne dependent pas des entites JPA ni des repositories Panache
- la persistance PostgreSQL est mappee uniquement dans `infrastructure/persistence/postgres`
- les cas d'usage manipulent des objets de `domain.model` et passent par des ports applicatifs

Les integrations minimales effectivement implementees sont:

- Jira
  - webhook entrant
  - ajout de commentaire de ticket
- GitHub
  - webhook entrant review/merge
  - publication locale `git` + creation de pull request via API GitHub
- OpenCode
  - lancement de run par HTTP
  - callback d'evenements agent par HTTP
  - reutilisation possible d'une configuration OpenCode utilisateur montee en lecture seule dans le container agent et fusionnee avec le template Devflow centralise sous [agent/opencode](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/agent/opencode)
  - recherche d'information internet possible via `websearch` et `webfetch`

## Ports metier

### Ports entrants

- `WorkflowSignalHandler`
  - recoit un signal normalise depuis un adapter entrant
- `AgentEventHandler`
  - recoit un evenement structure emis par un agent runtime
- `WorkflowAdminUseCase`
  - permet de reprendre, bloquer, annuler, reindexer un workflow

### Ports sortants

- `WorkItemPort`
  - lire un item
  - lister les nouveaux items eligibles
  - lire les commentaires
  - commenter
  - changer le statut externe
- `CodeHostPort`
  - cloner/puller
  - creer une branche
  - pousser
  - creer un code change
  - lire reviews/commentaires
  - detecter merge
- `AgentRuntimePort`
  - lancer une execution agent
  - annuler une execution agent
- `ExecutionEnvironmentPort`
  - provisionner un workspace
  - executer des commandes
  - nettoyer l'environnement
- `DeploymentPort`
  - detecter deploiement
  - verifier qu'un environnement est disponible
- `NotificationPort`
  - prevenir equipe dev/validation
- `WorkflowRepository`
  - charger/sauvegarder l'etat courant
- `WorkflowEventRepository`
  - journaliser les evenements
- `OutboxPort`
  - publier les commandes asynchrones
- `ClockPort`
  - horodatage testable

## Concepts metier principaux

### WorkflowInstance

Instance de suivi d'un besoin metier.

Champs minimum:

- `id`
- `tenantId`
- `workflowDefinitionId`
- `workItemRef`
- `currentPhase`
- `status`
- `currentBlockerId`
- `context`
- `version`
- `createdAt`
- `updatedAt`

### WorkItemRef

Reference neutre vers l'outil de ticketing:

- `system`
- `project`
- `key`
- `url`

### CodeChangeRef

Reference neutre vers la review technique:

- `system`
- `repository`
- `changeId`
- `url`

### WorkflowPhase

- `INFORMATION_COLLECTION`
- `IMPLEMENTATION`
- `TECHNICAL_VALIDATION`
- `BUSINESS_VALIDATION`
- `DONE`

### WorkflowStatus

Je recommande de separer phase et statut pour eviter l'explosion du nombre d'etats.

- `ACTIVE`
- `WAITING_EXTERNAL_INPUT`
- `WAITING_SYSTEM`
- `BLOCKED`
- `COMPLETED`
- `FAILED`
- `CANCELLED`

### AgentRun

Execution bornee d'un agent pour un workflow donne.

Champs minimum:

- `id`
- `workflowId`
- `phase`
- `status`
- `inputSnapshot`
- `providerRunRef`
- `startedAt`
- `endedAt`

### AgentCommand

Commande envoyee par l'orchestrateur au runtime agent.

Types recommandes:

- `START_RUN`
- `CANCEL_RUN`

Transport concret:

- `POST /internal/agent-runs`
- `POST /internal/agent-runs/{agentRunId}/cancel`

### AgentEvent

Evenement envoye par le runtime agent a l'orchestrateur.

Types recommandes:

- `RUN_STARTED`
- `PROGRESS_REPORTED`
- `INPUT_REQUIRED`
- `COMPLETED`
- `FAILED`
- `CANCELLED`

### Blocker

Un blocage est un objet metier a part entiere.

Champs utiles:

- `id`
- `workflowId`
- `phase`
- `type`
- `reason`
- `requestedFrom`
- `openedAt`
- `resolvedAt`
- `resumeTrigger`
- `details`

Exemples de `type`:

- `MISSING_TICKET_INFORMATION`
- `NOT_ELIGIBLE`
- `MISSING_REPOSITORY_MAPPING`
- `MISSING_TECHNICAL_FEEDBACK_CONTEXT`
- `WAITING_TECHNICAL_REVIEW`
- `WAITING_BUSINESS_FEEDBACK`
- `NO_EXECUTION_ENVIRONMENT`

## Qui appelle qui

### Flux nominal

1. Un adapter entrant recoit un evenement externe.
2. Il le convertit en `WorkflowSignal` canonique.
3. Il appelle `WorkflowSignalHandler`.
4. Le cas d'usage charge ou cree la `WorkflowInstance`.
5. Le moteur applique les regles metier et decide:
   - ignorer
   - avancer
   - bloquer
   - reprendre
   - emettre des commandes
6. L'etat courant est persiste.
7. Les evenements de domaine sont journalises.
8. Les commandes sortantes sont placees dans l'outbox.
9. Un dispatcher execute les commandes via les ports sortants.
10. Les systemes externes repondent ensuite soit synchrone, soit via un nouvel evenement entrant.

### En termes de composants

- `Webhook/Poller Adapter`
  -> `WorkflowSignalHandler`
  -> `WorkflowEngine`
  -> `WorkflowRepository`
  -> `WorkflowEventRepository`
  -> `OutboxPort`
  -> `ActionDispatcher`
  -> adapters sortants

Le coeur n'appelle jamais directement Jira, GitHub ou GitLab. Il appelle des ports.

## Communication orchestrateur-agent

Le point cle est le suivant:

- l'orchestrateur possede l'etat metier
- l'agent execute une mission bornee
- l'agent ne modifie jamais directement un ticket ou le workflow

Le bon modele n'est pas une conversation libre. C'est un modele `commande -> execution -> evenements`.

Dans l'architecture cible:

- l'orchestrateur appelle le container agent en HTTP
- le container agent execute OpenCode
- OpenCode utilise des outils qui envoient des appels HTTP vers l'orchestrateur
- l'orchestrateur persiste tout dans PostgreSQL et decide des transitions

L'agent:

- n'a pas acces a PostgreSQL
- n'a pas acces a Jira, GitHub ou GitLab
- n'a pas acces aux secrets metier
- ne peut parler qu'a l'API interne de l'orchestrateur

Cela permet d'isoler les secrets dans le container orchestrateur.

### Lancement de l'agent

1. L'orchestrateur decide qu'une execution est necessaire.
2. Il construit un `AgentRun` avec un `inputSnapshot`.
3. Il persiste le `AgentRun` en base.
4. Il envoie une commande `START_RUN` via `AgentRuntimePort`.
5. Le container agent demarre OpenCode et emet `RUN_STARTED`.

Le `inputSnapshot` doit contenir uniquement le contexte utile:

- identite du workflow
- phase courante
- resume du ticket
- references du ticket et du code change si elles existent
- contraintes d'execution
- resume du run precedent si reprise

### Ce que l'orchestrateur envoie

Exemple de commande:

```json
{
  "commandId": "cmd-123",
  "workflowId": "wf-456",
  "agentRunId": "run-789",
  "type": "START_RUN",
  "phase": "IMPLEMENTATION",
  "objective": "Implement work item APP-123",
  "inputSnapshot": {
    "workItem": {
      "system": "jira",
      "key": "APP-123",
      "title": "Add export button"
    },
    "repositories": ["org/frontend-app"],
    "acceptanceCriteria": [
      "Export current filtered results"
    ],
    "previousRunSummary": null
  }
}
```

### Ce que l'agent renvoie

L'agent ne renvoie pas du texte libre pour piloter le workflow. Il renvoie des evenements structures.

Exemple d'evenement de blocage:

```json
{
  "eventId": "evt-321",
  "workflowId": "wf-456",
  "agentRunId": "run-789",
  "type": "INPUT_REQUIRED",
  "blockerType": "MISSING_TICKET_INFORMATION",
  "reasonCode": "ACCEPTANCE_CRITERIA_AMBIGUOUS",
  "summary": "Two plausible implementations exist and the expected export scope is ambiguous.",
  "requestedFrom": "BUSINESS",
  "resumeTrigger": "NEW_COMMENT_ON_WORK_ITEM",
  "suggestedComment": "Can you clarify whether the export must contain only filtered data or the entire dataset?"
}
```

Dans ce modele:

- l'agent constate un blocage
- l'agent le decrit
- l'orchestrateur transforme cela en `Blocker`
- l'orchestrateur commente le ticket via `WorkItemPort`

### Comment l'orchestrateur recoit les informations

Je te conseille ce modele:

- l'orchestrateur cree un `agent_run`
- l'orchestrateur appelle `POST /internal/agent-runs` sur le container agent
- le container agent execute OpenCode
- OpenCode appelle des outils comme `devflow_request_input`
- ces outils font `POST /internal/agent-events` vers l'orchestrateur
- l'orchestrateur persiste `agent_event`
- l'orchestrateur applique ensuite les transitions metier
- l'orchestrateur reste le seul composant qui modifie `workflow_instance`

Pourquoi PostgreSQL reste indispensable:

- tu veux stocker l'etat courant des workflows
- tu veux dedupliquer les evenements HTTP
- tu veux garantir un seul `agent_run` actif
- tu veux reprendre apres redemarrage
- tu veux un audit complet des evenements recus
- tu veux un composant DB separe du container orchestrateur

Une implementation simple suffit:

- endpoint `POST /internal/agent-events`
- persistance transactionnelle des `agent_event`
- deduplication par `event_id`
- mise a jour transactionnelle du workflow

### APIs internes

API exposee par le container agent:

- `POST /internal/agent-runs`
  - demarre un nouveau run OpenCode
- `POST /internal/agent-runs/{agentRunId}/cancel`
  - arrete un run en cours

API exposee par l'orchestrateur:

- `POST /internal/agent-events`
  - recoit les evenements structures emis par l'agent

L'authentification doit etre interne:

- bearer token interne
- ou mTLS si tu veux durcir davantage

### Comment l'agent demande le blocage d'un ticket

Il ne demande pas "bloque le ticket" en langage libre.

Il emet un evenement `INPUT_REQUIRED` avec:

- `blockerType`
- `reasonCode`
- `summary`
- `requestedFrom`
- `resumeTrigger`
- `suggestedComment`

Puis l'orchestrateur fait lui-meme:

1. creation du `workflow_blocker`
2. passage du workflow en `WAITING_EXTERNAL_INPUT`
3. commentaire sur le ticket
4. journalisation de l'evenement

### Reprise apres blocage

Quand un nouveau commentaire arrive sur le ticket:

1. l'adapter ticketing emet `WORK_ITEM_COMMENT_RECEIVED`
2. l'orchestrateur recharge le workflow bloque
3. il verifie si le commentaire suffit pour lever le `Blocker`
4. si oui, il cloture le blocage
5. il cree un nouveau `AgentRun`

Je recommande pour la V1:

- creer un nouveau `AgentRun`
- fournir le resume du run precedent dans `inputSnapshot`

C'est plus robuste que de garder une session LLM vivante pendant des heures ou des jours.

## Workflow cible

### 1. Information collection

Declencheurs:

- nouveau `WorkItem` detecte
- nouveau commentaire sur un `WorkItem` deja bloque
- relecture periodique par polling

Etapes:

1. Verifier l'eligibilite.
2. Verifier que l'item contient assez d'informations.
3. Recuperer les artefacts utiles.
   - ticket
   - contexte fonctionnel
   - mapping repo
4. Si manque d'information:
   - creer un `Blocker`
   - commenter sur l'item
   - passer `status = WAITING_EXTERNAL_INPUT`
5. Si assez d'information:
   - passer a `IMPLEMENTATION`

Checks typiques:

- type de ticket autorise
- label/epic/projet autorise
- description suffisante
- criteres d'acceptation presents
- repository cible determinable

### Reprise d'un ticket bloque

Quand un nouveau commentaire arrive sur un ticket bloque:

1. l'adapter ticketing envoie `WORK_ITEM_COMMENT_RECEIVED`
2. le moteur recharge le workflow
3. il verifie si le commentaire concerne le `Blocker` actif
4. il re-evalue la completude d'information
5. si suffisant:
   - cloture le `Blocker`
   - repasse `status = ACTIVE`
   - continue vers `IMPLEMENTATION`
6. sinon:
   - met a jour le `Blocker`
   - publie un nouveau commentaire cible si besoin

### 2. Implementation

Etapes:

1. Provisionner un environnement d'execution.
2. Recuperer les repositories.
3. Creer une branche.
4. Produire la modification.
5. Executer les verifications locales minimales.
6. Push.
7. Creer le `CodeChange`.
8. Commenter le ticket avec le resume.
9. Mettre le ticket dans l'etat de review technique.

Important:

- `ExecutionEnvironmentPort` doit etre abstrait
- on ne suppose pas un poste local

Strategies possibles:

- `local`
- `container`
- `remote-runner`
- `kubernetes-job`

Si aucune strategie n'est disponible, le workflow passe en blocage `NO_EXECUTION_ENVIRONMENT`.

### 3. Technical validation

Cette phase est obligatoire.

Declencheurs:

- commentaires de review
- changement d'etat du code change
- merge
- statut CI

Etapes:

1. Attendre les retours de review.
2. Si commentaire technique:
   - verifier si l'agent a assez de contexte pour corriger
   - sinon creer un blocage et demander une precision
3. Si assez de contexte:
   - revenir temporairement en implementation
   - corriger
   - pousser
   - repondre au commentaire
4. Quand toutes les conditions techniques sont vertes:
   - attendre merge
   - ou merger si la politique l'autorise
5. Une fois merge:
   - evaluer le passage en validation metier
   - ou terminer directement si elle est desactivee

### 4. Business validation

Cette phase est optionnelle et configurable.

Modes possibles:

- `disabled`
- `manual`
- `auto-on-deploy`
- `auto-on-merge-to-branch`

Exemple chez toi:

- merge sur `develop`
- deploiement automatique en dev
- le merge vers `develop` peut valoir entree en validation metier

Etapes:

1. detecter que l'environnement de validation est disponible
2. notifier l'equipe de validation ou commenter le ticket
3. mettre le ticket dans le statut externe approprie
4. attendre validation ou retour metier
5. si retour metier:
   - verifier si l'agent peut corriger
   - sinon creer un blocage
   - sinon revenir en implementation
6. si validation positive:
   - cloturer le workflow
   - mettre le ticket en `Done`

## Machine d'etat recommandee

Au lieu de multiplier les etats "bloque techniquement", "bloque metier", "bloque info", stocke:

- `phase`
- `status`
- `blocker`

Exemple:

- `phase = INFORMATION_COLLECTION`
- `status = WAITING_EXTERNAL_INPUT`
- `blocker.type = MISSING_TICKET_INFORMATION`

Puis:

- `phase = TECHNICAL_VALIDATION`
- `status = WAITING_EXTERNAL_INPUT`
- `blocker.type = MISSING_TECHNICAL_FEEDBACK_CONTEXT`

Cette representation est plus simple a maintenir, plus facile a exposer en API, et plus stable quand tu ajoutes de nouveaux adapters.

## Stockage de l'etat

Je recommande:

- PostgreSQL pour l'etat courant et l'audit
- un journal d'evenements append-only pour l'audit
- une outbox pour les commandes asynchrones

### Tables minimum

#### `workflow_instance`

- `id`
- `tenant_id`
- `workflow_definition_id`
- `work_item_system`
- `work_item_key`
- `phase`
- `status`
- `current_blocker_id`
- `context_json`
- `version`
- `created_at`
- `updated_at`

#### `workflow_blocker`

- `id`
- `workflow_id`
- `phase`
- `type`
- `reason`
- `requested_from`
- `resume_trigger_json`
- `details_json`
- `opened_at`
- `resolved_at`
- `status`

#### `workflow_event`

- `id`
- `workflow_id`
- `event_type`
- `event_payload_json`
- `occurred_at`
- `source_system`
- `source_event_id`

#### `external_reference`

- `id`
- `workflow_id`
- `ref_type`
- `system`
- `external_id`
- `url`
- `metadata_json`

Exemples:

- ticket
- code change
- repo
- branch
- deployment

#### `agent_run`

- `id`
- `workflow_id`
- `phase`
- `status`
- `input_snapshot_json`
- `provider_run_ref`
- `started_at`
- `ended_at`

#### `agent_event`

- `id`
- `agent_run_id`
- `event_id`
- `event_type`
- `event_payload_json`
- `occurred_at`
- `processed_at`
- `status`

#### `inbox_event`

Pour dedupliquer les webhooks et signaux entrants.

- `id`
- `source_system`
- `source_event_id`
- `payload_hash`
- `received_at`
- `processed_at`
- `status`

#### `outbox_command`

- `id`
- `workflow_id`
- `command_type`
- `payload_json`
- `created_at`
- `processed_at`
- `status`

### Pourquoi pas seulement un event store

Tu peux faire du pur event sourcing, mais pour une V1 opensource Quarkus, je conseille:

- etat courant relationnel lisible
- journal d'evenements en support

Tu simplifies:

- les requetes d'administration
- les dashboards
- la reprise sur incident
- le debugging

Je te recommande:

- `workflow_instance`, `workflow_blocker`, `agent_run`, `agent_event` dans PostgreSQL
- un index unique qui garantit un seul `agent_run` en statut `RUNNING`
- un historique append-only des evenements agent

Contraintes importantes:

- unicite de `agent_event.event_id` pour dedupliquer les callbacks HTTP
- unicite logique d'un seul `agent_run` actif a la fois

## Correlation des commentaires et reprise

Le point cle de ton besoin est ici.

Quand un workflow est bloque, il faut garder:

- l'objet attendu
  - ticket ou code review
- le type d'information manquante
- qui doit repondre
- comment detecter qu'on peut reprendre

Exemple de strategie:

1. Devflow commente sur le ticket:
   - il manque les criteres d'acceptation
   - merci de repondre ici
2. Le `Blocker` stocke:
   - `requestedFrom = BUSINESS`
   - `resumeTrigger = NEW_COMMENT_ON_WORK_ITEM`
3. Quand un commentaire arrive:
   - l'adapter ticketing pousse un signal
   - le moteur retrouve le workflow par `workItemRef`
   - il relance la regle `InformationReadinessPolicy`
4. Si le commentaire suffit:
   - `blocker.resolvedAt` est renseigne
   - le workflow reprend
5. Sinon:
   - le workflow reste bloque
   - un nouveau commentaire plus precis peut etre emis

Meme logique pour les commentaires de PR/MR et les retours de validation metier.

## Politique de workflow configurable

Le workflow ne doit pas etre code en dur pour Jira/GitHub.

Il doit etre pilote par:

- adapters selectionnes
- capacites declarees
- regles de mapping
- regles d'eligibilite
- presence ou non d'une phase metier

## Exemple de configuration YAML

```yaml
devflow:
  workflow:
    definition-id: default

  eligibility:
    work-item-types:
      - story
      - bug
      - task
    required-fields:
      - title
      - description
    required-links:
      - repository

  adapters:
    work-item:
      main:
        type: jira
        base-url: ${JIRA_BASE_URL}
        credentials:
          mode: basic-token
          username: ${JIRA_USERNAME}
          token: ${JIRA_TOKEN}
        discovery:
          mode: jql
          query: 'project = APP AND labels = "agent"'
        mappings:
          to-review: "To Review"
          validation: "Validation"
          done: "Done"

    code-host:
      main:
        type: github
        api-url: https://api.github.com
        credentials:
          mode: token
          token: ${GITHUB_TOKEN}
        repositories:
          - org/backend-service
          - org/frontend-app

    agent-runtime:
      main:
        type: http
        base-url: http://agent:8081

    execution-environment:
      main:
        type: container
        image: ghcr.io/acme/devflow-runner:latest
        workdir: /workspace

    deployment:
      main:
        type: branch-merge
        tracked-branch: develop

    notification:
      main:
        type: ticket-comment
```

## Secrets et configuration

Je te conseille:

- fichier YAML pour la structure
- variables d'environnement pour les secrets
- eventuellement Vault ou Kubernetes Secrets plus tard

Ne stocke pas les credentials en base applicative sauf si tu dois gerer du multi-tenant avec rotation centralisee.

## Creation d'un nouvel adapter

Pour qu'un utilisateur ajoute `GitHub` si toi tu livres `GitLab`, il faut une SPI claire.

Exemple:

- `WorkItemPort`
- `CodeHostPort`
- `AgentRuntimePort`
- `ExecutionEnvironmentPort`
- `DeploymentPort`

Chaque adapter:

- implemente un port
- declare un `type`
- expose ses proprietes de configuration
- mappe ses objets externes vers les objets canoniques

Le coeur ne doit jamais voir:

- `Issue` Jira
- `PullRequest` GitHub
- `MergeRequest` GitLab

Il ne doit voir que:

- `WorkItem`
- `CodeChange`
- `ReviewComment`
- `WorkflowSignal`

## Recommandation d'implementation Quarkus

- RESTEasy Reactive pour les webhooks
- Scheduler Quarkus pour polling/reconciliation
- Hibernate ORM + PostgreSQL
- colonnes JSONB pour le contexte et les metadonnees adapter-specifiques
- Panache uniquement si tu veux aller vite, sinon repository classique
- SmallRye Config pour la configuration YAML
- SmallRye Fault Tolerance pour les appels externes

## Catalogue d'evenements

Le catalogue detaille des evenements et commandes est decrit dans [events.md](/Users/naof.elelalouani/Documents/Dev/Perso/devflow/docs/events.md).

## Sequence resumee

1. Detecter un nouvel item ou un nouveau commentaire.
2. Creer ou retrouver le workflow associe.
3. Evaluer eligibilite et completude.
4. Si incomplet, bloquer et commenter.
5. Si complet, provisionner l'environnement et implementer.
6. Creer le code change et attendre la validation technique.
7. Si retour technique, corriger ou bloquer.
8. Une fois merge, lancer la validation metier si configuree.
9. Si retour metier, corriger ou bloquer.
10. Si valide, cloturer.

## Decision forte

Le point le plus important de toute l'architecture est le suivant:

Le workflow doit etre pilote par un moteur de transitions sur etat canonique, et non par des conditions codees directement dans les adapters.

Autrement dit:

- les adapters traduisent
- le coeur decide
- la base garde l'etat
- les evenements permettent la reprise

Si tu gardes cette regle, ton orchestrateur restera extensible.
