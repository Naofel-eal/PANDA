# Devflow Architecture

## Objectif

Construire un orchestrateur Quarkus en architecture hexagonale capable de suivre un besoin metier depuis un ticket jusqu'a la validation technique, et eventuellement la validation metier, sans dependre d'un outil particulier comme Jira, GitHub, GitLab ou d'un environnement de developpement local permanent.

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
- scheduler de polling
- CLI ou API admin

### Adapters sortants

- ticketing: Jira, Linear, Azure Boards, custom
- code host: GitHub, GitLab, Bitbucket
- git workspace: local, container, runner distant
- design: Figma, URL simple, repository docs
- validation/deploiement: CI/CD, environment watcher
- notification: email, Slack, Teams, commentaire ticket
- persistence: PostgreSQL

## Modules recommandes

Pour Quarkus, je recommande un decoupage simple en plusieurs modules Maven/Gradle:

- `devflow-domain`
- `devflow-application`
- `devflow-adapters-inbound-rest`
- `devflow-adapters-inbound-scheduler`
- `devflow-adapters-outbound-persistence-postgres`
- `devflow-adapters-outbound-ticketing-jira`
- `devflow-adapters-outbound-codehost-github`
- `devflow-adapters-outbound-codehost-gitlab`
- `devflow-adapters-outbound-design-figma`
- `devflow-adapters-outbound-workspace-local`
- `devflow-adapters-outbound-workspace-container`
- `devflow-bootstrap-quarkus`

Version 1 pragmatique:

- garde les adapters comme modules Maven/Gradle normaux
- expose des interfaces stables
- charge les implementations via CDI Quarkus

Tu n'as pas besoin d'un systeme de plugins dynamique en V1. Pour l'opensource, publier des SPI propres suffit largement.

## Ports metier

### Ports entrants

- `WorkflowSignalHandler`
  - recoit un signal normalise depuis un adapter entrant
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
- `DesignPort`
  - lire les liens de design
  - recuperer le contenu utile
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
- `MISSING_DESIGN_REFERENCE`
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
   - design
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
- design accessible si necessaire

### Reprise d'un ticket bloque

Quand un nouveau commentaire arrive sur un ticket bloque:

1. l'adapter ticketing envoie `WorkItemCommentReceived`
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

- PostgreSQL pour l'etat courant
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
- design document

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
    phases:
      information-collection:
        enabled: true
      implementation:
        enabled: true
      technical-validation:
        enabled: true
        merge-required: true
      business-validation:
        enabled: true
        mode: auto-on-merge-to-branch
        branch: develop

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
    optional-links:
      - design

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

    design:
      main:
        type: figma
        token: ${FIGMA_TOKEN}

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
- Hibernate ORM + PostgreSQL pour l'etat
- JSONB pour le contexte et les metadonnees adapter-specifiques
- Panache uniquement si tu veux aller vite, sinon repository classique
- SmallRye Config pour la configuration YAML
- SmallRye Fault Tolerance pour les appels externes

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
