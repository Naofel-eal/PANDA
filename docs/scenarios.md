# Devflow Scenarios

Ce document decrit 100% des scenarios geres par l'implementation actuelle.

Il ne decrit pas une cible theorique. Il decrit ce que le code fait aujourd'hui.

Note d'implementation actuelle:

- l'intake Jira se fait maintenant par polling sur l'epic configure
- le suivi GitHub se fait maintenant par polling sur les pull requests suivies par Devflow
- les references historiques aux webhooks dans ce document restent a realigner

## Composants

- `Jira`
- `GitHub`
- `Orchestrator`
- `Postgres`
- `Agent`
- `OpenCode`

## Regles globales

- `Jira` et `GitHub` parlent au `Orchestrator` via webhooks.
- `Orchestrator` stocke l'etat dans `Postgres`.
- `Orchestrator` lance `Agent` en HTTP.
- `Agent` lance `OpenCode`.
- `OpenCode` remonte ses evenements au `Orchestrator` via les tools Devflow.
- `Orchestrator` est le seul composant qui commente Jira et publie sur GitHub.
- Un seul `agent_run` peut etre actif a la fois.

## Sources d'entree actuellement supportees

- `POST /webhooks/jira`
- `POST /webhooks/github`
- `POST /api/v1/workflow-signals`
- `POST /internal/agent-events`

## 1. Ticket Jira cree avec assez d'information

Conditions:

- le type de ticket est autorise
- `title`, `description` et au moins un `repository` sont presents

Interactions:

- `Jira -> Orchestrator`: webhook `WORK_ITEM_DISCOVERED`
- `Orchestrator -> Postgres`: enregistre l'inbox event
- `Orchestrator -> Postgres`: cree ou recharge le workflow
- `Orchestrator -> Postgres`: enregistre la reference du ticket
- `Orchestrator -> Postgres`: cree un `agent_run` `INFORMATION_COLLECTION`
- `Orchestrator -> Postgres`: cree une commande outbox `START_RUN`
- `Scheduler Orchestrator -> Agent`: `POST /internal/agent-runs`
- `Agent -> Orchestrator`: `RUN_STARTED`
- `Orchestrator -> Postgres`: passe le run a `RUNNING` et le workflow a `ACTIVE`

## 2. Ticket Jira mis a jour avec assez d'information

Interactions:

- `Jira -> Orchestrator`: webhook `WORK_ITEM_UPDATED`
- `Orchestrator -> Postgres`: met a jour le contexte du workflow
- `Orchestrator -> Postgres`: enregistre ou met a jour la reference ticket
- `Orchestrator -> Postgres`: relance un `agent_run` `INFORMATION_COLLECTION`

## 3. Ticket Jira cree ou mis a jour sans assez d'information

Conditions:

- il manque au moins `title`, `description` ou `repository`

Interactions:

- `Jira -> Orchestrator`: webhook `WORK_ITEM_DISCOVERED` ou `WORK_ITEM_UPDATED`
- `Orchestrator -> Postgres`: cree ou recharge le workflow
- `Orchestrator -> Postgres`: ouvre ou met a jour un `blocker`
- `Orchestrator -> Postgres`: met le workflow en `WAITING_EXTERNAL_INPUT`
- `Orchestrator -> Jira`: ajoute un commentaire avec la raison du blocage

Etat final:

- phase `INFORMATION_COLLECTION`
- statut `WAITING_EXTERNAL_INPUT`

## 4. Ticket Jira cree avec un type non eligible

Cas 1: aucun workflow n'existe encore

Interactions:

- `Jira -> Orchestrator`: webhook `WORK_ITEM_DISCOVERED`
- `Orchestrator`: evalue le ticket
- `Orchestrator`: ignore le ticket
- `Orchestrator -> Postgres`: pas de workflow cree

Cas 2: un workflow existe deja

Interactions:

- `Jira -> Orchestrator`: webhook `WORK_ITEM_UPDATED`
- `Orchestrator -> Postgres`: recharge le workflow existant
- `Orchestrator -> Postgres`: met le workflow en `CANCELLED`

## 5. Commentaire ajoute sur un ticket bloque en collecte d'information

Conditions:

- le workflow est en `WAITING_EXTERNAL_INPUT`
- la phase est `INFORMATION_COLLECTION`

Interactions:

- `Jira -> Orchestrator`: webhook `WORK_ITEM_COMMENT_RECEIVED`
- `Orchestrator -> Postgres`: deduplication du commentaire
- `Orchestrator -> Postgres`: resout le blocker ouvert
- `Orchestrator -> Postgres`: cree un nouveau `agent_run` `INFORMATION_COLLECTION`
- `Scheduler Orchestrator -> Agent`: `POST /internal/agent-runs`

## 6. Commentaire ajoute sur un ticket bloque en implementation

Conditions:

- le workflow est en `WAITING_EXTERNAL_INPUT`
- la phase n'est pas `INFORMATION_COLLECTION`

Interactions:

- `Jira -> Orchestrator`: webhook `WORK_ITEM_COMMENT_RECEIVED`
- `Orchestrator -> Postgres`: deduplication du commentaire
- `Orchestrator -> Postgres`: resout le blocker ouvert
- `Orchestrator -> Postgres`: cree un nouveau `agent_run` `IMPLEMENTATION`
- `Scheduler Orchestrator -> Agent`: `POST /internal/agent-runs`

## 7. Commentaire ajoute sur un ticket non bloque

Interactions:

- `Jira -> Orchestrator`: webhook `WORK_ITEM_COMMENT_RECEIVED`
- `Orchestrator -> Postgres`: deduplication du commentaire
- `Orchestrator -> Postgres`: journalise l'evenement
- `Orchestrator`: ne relance rien

## 8. Commentaire Jira duplique

Interactions:

- `Jira -> Orchestrator`: webhook `WORK_ITEM_COMMENT_RECEIVED`
- `Orchestrator -> Postgres`: retrouve le meme `comment_id` avec le meme hash
- `Orchestrator`: ignore le commentaire

## 9. Commentaire Jira edite

Interactions:

- `Jira -> Orchestrator`: webhook `WORK_ITEM_COMMENT_RECEIVED`
- `Orchestrator -> Postgres`: retrouve le meme `comment_id` avec un hash different
- `Orchestrator`: traite le commentaire comme un nouveau signal metier

## 10. Run agent demarre

Interactions:

- `Orchestrator -> Agent`: `POST /internal/agent-runs`
- `Agent -> OpenCode`: lance `opencode run`
- `Agent -> Orchestrator`: `RUN_STARTED`
- `Orchestrator -> Postgres`: met le run a `RUNNING`
- `Orchestrator -> Postgres`: met le workflow a `ACTIVE`

## 11. Run agent envoie un progres

Interactions:

- `OpenCode -> Agent`: appelle `report_progress`
- `Agent -> Orchestrator`: `PROGRESS_REPORTED`
- `Orchestrator -> Postgres`: journalise l'evenement

## 12. Run agent demande une information externe

Interactions:

- `OpenCode -> Agent`: appelle `request_input`
- `Agent -> Orchestrator`: `INPUT_REQUIRED`
- `Orchestrator -> Postgres`: met le run a `WAITING_INPUT`
- `Orchestrator -> Postgres`: ouvre ou met a jour un `blocker`
- `Orchestrator -> Postgres`: met le workflow en `WAITING_EXTERNAL_INPUT`
- `Orchestrator -> Jira`: ajoute le commentaire suggere

Exemples typiques:

- specification ambigue
- mapping repository manquant
- contexte technique insuffisant

## 13. Run agent termine la collecte d'information

Conditions:

- le run courant est en phase `INFORMATION_COLLECTION`

Interactions:

- `OpenCode -> Agent`: appelle `complete_run`
- `Agent -> Orchestrator`: `COMPLETED`
- `Orchestrator -> Postgres`: clot le run courant
- `Orchestrator -> Postgres`: resout le blocker eventuel
- `Orchestrator -> Postgres`: cree un nouveau `agent_run` `IMPLEMENTATION`
- `Scheduler Orchestrator -> Agent`: `POST /internal/agent-runs`

## 14. Run agent termine l'implementation

Conditions:

- le run courant est en phase `IMPLEMENTATION`

Interactions:

- `OpenCode -> Agent`: appelle `complete_run`
- `Agent -> Orchestrator`: `COMPLETED`
- `Orchestrator -> Postgres`: clot le run courant
- `Orchestrator -> GitHub`: inspecte tous les repositories du workspace partage
- `Orchestrator -> GitHub`: pour chaque repository modifie, cree ou reutilise une branche prefixee `devflow/...`
- `Orchestrator -> GitHub`: `git add`, `git commit`, `git push` pour chaque repository modifie
- `Orchestrator -> GitHub API`: cree ou retrouve une pull request par repository modifie
- `Orchestrator -> Postgres`: enregistre une reference `CODE_CHANGE` par pull request
- `Orchestrator -> Postgres`: passe le workflow en `TECHNICAL_VALIDATION`
- `Orchestrator -> Jira`: ajoute un commentaire de fin d'implementation

Etat final:

- phase `TECHNICAL_VALIDATION`
- statut `WAITING_SYSTEM`

Point important:

- `complete_run` veut dire "l'agent a fini le travail local"
- c'est l'orchestrateur qui decide que le ticket est pret pour la validation technique, apres creation effective de toutes les PRs necessaires

## 15. Run agent termine pendant la validation technique

Conditions:

- le run courant est en phase `TECHNICAL_VALIDATION`

Interactions:

- `OpenCode -> Agent`: appelle `complete_run`
- `Agent -> Orchestrator`: `COMPLETED`
- `Orchestrator -> Postgres`: clot le run courant
- `Orchestrator -> Postgres`: laisse le workflow en `TECHNICAL_VALIDATION`

## 16. Run agent termine apres un retour de validation metier

Conditions:

- le run courant est en phase `BUSINESS_VALIDATION`

Interactions:

- `OpenCode -> Agent`: appelle `complete_run`
- `Agent -> Orchestrator`: `COMPLETED`
- `Orchestrator -> Postgres`: clot le run courant
- `Orchestrator -> Postgres`: repasse le workflow en `TECHNICAL_VALIDATION`

## 17. Run agent echoue

Interactions:

- `OpenCode -> Agent`: appelle `fail_run`
- `Agent -> Orchestrator`: `FAILED`
- `Orchestrator -> Postgres`: met le run a `FAILED`
- `Orchestrator -> Postgres`: met le workflow a `FAILED`

## 18. Processus OpenCode sort sans evenement terminal

Cas 1: sortie normale sans `complete_run`

Interactions:

- `OpenCode -> Agent`: le process se termine
- `Agent -> Orchestrator`: envoie `FAILED`
- `Orchestrator -> Postgres`: met le run a `FAILED`
- `Orchestrator -> Postgres`: met le workflow a `FAILED`

Cas 2: sortie apres annulation

Interactions:

- `Orchestrator -> Agent`: demande l'annulation
- `Agent`: tue le process OpenCode
- `Agent -> Orchestrator`: envoie `CANCELLED`

## 19. Annulation d'un run actif

Interactions:

- `Orchestrator -> Agent`: `POST /internal/agent-runs/{id}/cancel`
- `Agent`: envoie `SIGTERM`, puis `SIGKILL` si besoin
- `Agent -> Orchestrator`: `CANCELLED`
- `Orchestrator -> Postgres`: met le run a `CANCELLED`
- `Orchestrator -> Postgres`: remet le workflow a `WAITING_SYSTEM` si le workflow lui-meme n'etait pas annule

## 20. Annulation demandee sur un run non actif

Interactions:

- `Orchestrator -> Agent`: `POST /internal/agent-runs/{id}/cancel`
- `Agent`: repond `ignored`
- `Orchestrator`: rien d'autre

## 21. Nouveau commentaire de review GitHub

Conditions:

- webhook `pull_request_review_comment`
- action `created` ou `edited`

Interactions:

- `GitHub -> Orchestrator`: webhook GitHub
- `Orchestrator -> Postgres`: deduplication du commentaire
- `Orchestrator -> Postgres`: retrouve le workflow via la reference `CODE_CHANGE`
- `Orchestrator -> Postgres`: passe le workflow en `IMPLEMENTATION`
- `Orchestrator -> Postgres`: cree un nouveau `agent_run` `IMPLEMENTATION`
- `Scheduler Orchestrator -> Agent`: `POST /internal/agent-runs`

## 22. Commentaire de review GitHub duplique

Interactions:

- `GitHub -> Orchestrator`: webhook GitHub
- `Orchestrator -> Postgres`: retrouve le meme `comment_id` et le meme hash
- `Orchestrator`: ignore l'evenement

## 23. Pull request GitHub mergee et toutes les PRs du workflow sont mergees

Conditions:

- webhook `pull_request`
- action `closed`
- `merged = true`
- toutes les PRs du workflow sont mergees

Interactions:

- `GitHub -> Orchestrator`: webhook GitHub
- `Orchestrator -> Postgres`: marque la PR concernee comme `MERGED`
- `Orchestrator -> Postgres`: verifie si toutes les PRs du workflow sont mergees
- `Orchestrator -> Postgres`: passe le workflow en `BUSINESS_VALIDATION` seulement si toutes les PRs attendues sont mergees
- `Orchestrator -> Postgres`: ouvre un blocker `WAITING_BUSINESS_FEEDBACK`
- `Orchestrator -> Jira`: ajoute un commentaire demandant la validation metier

Etat final:

- phase `BUSINESS_VALIDATION`
- statut `WAITING_EXTERNAL_INPUT`

## 24. Pull request GitHub mergee alors qu'il reste encore des PRs ouvertes

Conditions:

- webhook `pull_request`
- action `closed`
- `merged = true`
- au moins une autre PR du workflow n'est pas mergee

Interactions:

- `GitHub -> Orchestrator`: webhook GitHub
- `Orchestrator -> Postgres`: marque la PR concernee comme `MERGED`
- `Orchestrator -> Postgres`: verifie si toutes les PRs du workflow sont mergees
- `Orchestrator -> Postgres`: conserve le workflow en `TECHNICAL_VALIDATION`
- `Orchestrator -> Postgres`: laisse le workflow en `WAITING_SYSTEM`

## 25. Pull request GitHub fermee sans merge

Conditions:

- webhook `pull_request`
- action `closed`
- `merged = false`

Interactions:

- `GitHub -> Orchestrator`: webhook GitHub
- `Orchestrator -> Postgres`: retrouve le workflow via `CODE_CHANGE`
- `Orchestrator -> Postgres`: met le workflow en `BLOCKED`

## 26. Webhook GitHub non gere

Interactions:

- `GitHub -> Orchestrator`: webhook autre que review comment ou PR close
- `Orchestrator`: ignore le webhook

## 27. Deployment disponible pendant la validation metier

Interactions:

- `Systeme externe -> Orchestrator`: `DEPLOYMENT_AVAILABLE` via `/api/v1/workflow-signals`
- `Orchestrator -> Postgres`: ouvre ou met a jour un blocker `WAITING_BUSINESS_FEEDBACK`
- `Orchestrator -> Jira`: ajoute un commentaire disant que l'environnement est pret

## 28. Deployment disponible hors validation metier

Interactions:

- `Systeme externe -> Orchestrator`: `DEPLOYMENT_AVAILABLE`
- `Orchestrator -> Postgres`: journalise seulement

## 29. Validation metier approuvee

Interactions:

- `Systeme externe -> Orchestrator`: `BUSINESS_VALIDATION_REPORTED` avec `APPROVED`
- `Orchestrator -> Postgres`: resout le blocker
- `Orchestrator -> Postgres`: passe le workflow a `DONE`

## 30. Validation metier refusee

Interactions:

- `Systeme externe -> Orchestrator`: `BUSINESS_VALIDATION_REPORTED`
- `Orchestrator -> Postgres`: resout le blocker courant
- `Orchestrator -> Postgres`: repasse le workflow en `IMPLEMENTATION`
- `Orchestrator -> Postgres`: cree un nouveau `agent_run`
- `Scheduler Orchestrator -> Agent`: `POST /internal/agent-runs`

## 31. Changement de statut du ticket vers annule

Interactions:

- `Systeme externe -> Orchestrator`: `WORK_ITEM_STATUS_CHANGED`
- `Orchestrator -> Postgres`: met le workflow a `CANCELLED`

## 32. Changement de statut du ticket vers done ou closed

Interactions:

- `Systeme externe -> Orchestrator`: `WORK_ITEM_STATUS_CHANGED`
- `Orchestrator -> Postgres`: met le workflow en phase `DONE` et statut `COMPLETED`

## 33. Reconciliation demandee

Interactions:

- `Systeme externe -> Orchestrator`: `RECONCILIATION_REQUESTED`
- `Orchestrator -> Postgres`: journalise l'evenement

Note:

- aujourd'hui, aucune action de reconciliation supplementaire n'est executee

## 35. Reprise manuelle

Interactions:

- `Systeme externe -> Orchestrator`: `MANUAL_RESUME_REQUESTED`
- `Orchestrator -> Postgres`: resout le blocker courant
- `Orchestrator -> Postgres`: choisit la phase a relancer
- `Orchestrator -> Postgres`: cree un nouveau `agent_run`
- `Scheduler Orchestrator -> Agent`: `POST /internal/agent-runs`

Regle actuelle:

- si la phase courante est `INFORMATION_COLLECTION`, on relance `INFORMATION_COLLECTION`
- sinon, on relance `IMPLEMENTATION`

## 36. Annulation manuelle du workflow

Interactions:

- `Systeme externe -> Orchestrator`: `MANUAL_CANCEL_REQUESTED`
- `Orchestrator -> Postgres`: met le workflow a `CANCELLED`

## 37. Evenement Jira ou GitHub deja recu

Conditions:

- meme `sourceSystem`
- meme `sourceEventType`
- meme `sourceEventId`

Interactions:

- `Jira/GitHub -> Orchestrator`: webhook duplique
- `Orchestrator -> Postgres`: retrouve l'inbox event existant
- `Orchestrator`: ignore le signal

## 38. Evenement agent deja recu

Conditions:

- meme `eventId`

Interactions:

- `Agent -> Orchestrator`: callback duplique
- `Orchestrator -> Postgres`: retrouve l'`agent_event`
- `Orchestrator`: ignore le callback

## 39. Un run est deja actif quand le scheduler veut en lancer un autre

Interactions:

- `Scheduler Orchestrator -> Postgres`: verifie s'il existe un run `STARTING` ou `RUNNING`
- `Scheduler Orchestrator`: ne dispatch rien tant qu'un run est actif

## 40. L'agent recoit une nouvelle demande alors qu'un run est deja actif

Interactions:

- `Orchestrator -> Agent`: `POST /internal/agent-runs`
- `Agent`: detecte `activeRun`
- `Agent -> Orchestrator`: reponse HTTP `409 another_run_is_active`
- `Orchestrator -> Postgres`: la commande reste reessayable par le scheduler

## 41. Scenario nominal complet sans validation metier

Interactions:

- `Jira -> Orchestrator`: ticket cree avec assez d'information
- `Orchestrator -> Agent`: run `INFORMATION_COLLECTION`
- `Agent -> Orchestrator`: `RUN_STARTED`
- `Agent -> Orchestrator`: `COMPLETED`
- `Orchestrator -> Agent`: run `IMPLEMENTATION`
- `Agent -> Orchestrator`: `RUN_STARTED`
- `Agent -> Orchestrator`: `COMPLETED`
- `Orchestrator -> GitHub`: push + PR
- `GitHub -> Orchestrator`: `CODE_CHANGE_MERGED`
- `Orchestrator -> Postgres`: passe le workflow a `DONE`

## 42. Scenario nominal complet avec review technique

Interactions:

- `Jira -> Orchestrator`: ticket cree avec assez d'information
- `Orchestrator -> Agent`: `INFORMATION_COLLECTION`
- `Agent -> Orchestrator`: `COMPLETED`
- `Orchestrator -> Agent`: `IMPLEMENTATION`
- `Agent -> Orchestrator`: `COMPLETED`
- `Orchestrator -> GitHub`: push + PR
- `GitHub -> Orchestrator`: commentaire de review
- `Orchestrator -> Agent`: nouveau run `IMPLEMENTATION`
- `Agent -> Orchestrator`: `COMPLETED`
- `Orchestrator -> GitHub`: mise a jour de la branche/PR
- `GitHub -> Orchestrator`: PR mergee

## 43. Scenario complet bloque par manque d'information puis repris

Interactions:

- `Jira -> Orchestrator`: ticket cree sans assez d'information
- `Orchestrator -> Jira`: commentaire de blocage
- `Orchestrator -> Postgres`: workflow `WAITING_EXTERNAL_INPUT`
- `Jira -> Orchestrator`: nouveau commentaire sur le ticket bloque
- `Orchestrator -> Postgres`: resout le blocker
- `Orchestrator -> Agent`: relance `INFORMATION_COLLECTION`
- `Agent -> Orchestrator`: `COMPLETED`
- `Orchestrator -> Agent`: `IMPLEMENTATION`

## 44. Scenario complet avec validation metier

Interactions:

- `Jira -> Orchestrator`: ticket cree avec assez d'information
- `Orchestrator -> Agent`: `INFORMATION_COLLECTION`
- `Agent -> Orchestrator`: `COMPLETED`
- `Orchestrator -> Agent`: `IMPLEMENTATION`
- `Agent -> Orchestrator`: `COMPLETED`
- `Orchestrator -> GitHub`: push + PR
- `GitHub -> Orchestrator`: PR mergee sur la branche de validation
- `Orchestrator -> Jira`: commentaire "pret pour validation metier"
- `Systeme externe -> Orchestrator`: `BUSINESS_VALIDATION_REPORTED`
- `Orchestrator -> Postgres`: workflow `DONE`

## Resume simple

Les branches metier aujourd'hui sont:

- ticket ignore
- ticket bloque en attente d'information
- ticket en information collection
- ticket en implementation
- ticket en validation technique
- ticket en validation metier
- ticket termine
- ticket annule
- ticket en echec
- ticket bloque car PR fermee sans merge
