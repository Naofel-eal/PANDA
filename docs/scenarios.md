# Devflow Scenarios

Ce document decrit 100% des scenarios geres par l'implementation actuelle.

Il ne decrit pas une cible theorique. Il decrit ce que le code fait aujourd'hui.

## Composants

- `Jira` (polled periodiquement)
- `GitHub` (polled periodiquement)
- `Orchestrator` (stateless, volatile `currentRun`)
- `Agent` (OpenCode)

## Regles globales

- `JiraTicketPollingJob` interroge Jira chaque minute.
- `GitHubPollingJob` interroge GitHub chaque minute.
- `Orchestrator` lance `Agent` en HTTP direct (pas d'outbox).
- `Agent` lance `OpenCode`.
- `OpenCode` remonte ses evenements au `Orchestrator` via les tools Devflow.
- `Orchestrator` est le seul composant qui commente Jira et publie sur GitHub.
- Un seul `agent_run` peut etre actif a la fois (volatile `currentRun`).
- Pas de base de donnees. L'etat est re-decouvert depuis Jira/GitHub a chaque cycle.

## Sources d'entree

- `JiraTicketPollingJob` (polling Jira, toutes les minutes)
- `GitHubPollingJob` (polling GitHub, toutes les minutes)
- `POST /internal/agent-events` (callbacks agent)

## 1. Ticket "To Do" avec assez d'information

Conditions:

- le ticket est dans le statut "To Do" dans l'epic configure
- le type de ticket est autorise
- `title`, `description` et au moins un `repository` sont presents

Interactions:

- `JiraTicketPollingJob`: detecte le ticket en statut "To Do"
- `EligibilityService`: evalue l'eligibilite
- `JiraTicketPollingJob -> DevFlowRuntime`: `startRun()` avec `INFORMATION_COLLECTION`
- `JiraTicketPollingJob -> Agent`: `POST /internal/agent-runs`
- `Agent -> Orchestrator`: `RUN_STARTED`
- `Orchestrator -> Jira`: transition vers "In Progress"

## 2. Ticket "To Do" sans assez d'information

Conditions:

- il manque au moins `title`, `description` ou `repository`

Interactions:

- `JiraTicketPollingJob`: detecte le ticket
- `EligibilityService`: retourne les champs manquants
- `JiraTicketPollingJob -> Jira`: poste un commentaire "Devflow marked this ticket as blocked because it is missing: ..."

Etat final: le ticket reste en "To Do" avec un commentaire d'eligibilite.

## 3. Ticket "To Do" deja marque comme non eligible (skip)

Conditions:

- DevFlow a deja poste un commentaire d'eligibilite
- aucun nouveau commentaire utilisateur depuis
- aucune mise a jour du ticket au-dela de la grace period (60 secondes)

Interactions:

- `JiraTicketPollingJob`: detecte le ticket, appelle `shouldSkipIneligibleTicket()`
- resultat: `true` → le ticket est silencieusement ignore

## 4. Ticket "To Do" non eligible mais mis a jour

Conditions:

- DevFlow a deja poste un commentaire d'eligibilite
- le ticket a ete mis a jour (description modifiee ou nouveau commentaire)

Interactions:

- `JiraTicketPollingJob`: `shouldSkipIneligibleTicket()` retourne `false`
- `EligibilityService`: re-evalue
- si maintenant eligible: demarre un run
- sinon: poste un nouveau commentaire d'eligibilite

## 5. Ticket "Blocked" avec nouveau commentaire utilisateur

Conditions:

- le ticket est en statut "Blocked"
- le dernier commentaire utilisateur est posterieur au dernier commentaire DevFlow

Interactions:

- `JiraTicketPollingJob`: detecte le ticket en "Blocked"
- `JiraTicketPollingJob`: compare les timestamps des commentaires
- `JiraTicketPollingJob -> DevFlowRuntime`: `startRun()` avec `INFORMATION_COLLECTION`
- `JiraTicketPollingJob -> Agent`: `POST /internal/agent-runs`

## 6. Ticket "Blocked" sans nouveau commentaire

Interactions:

- `JiraTicketPollingJob`: detecte le ticket en "Blocked"
- le dernier commentaire utilisateur precede le dernier commentaire DevFlow
- resultat: rien ne se passe

## 7. Run agent demarre

Interactions:

- `Orchestrator -> Agent`: `POST /internal/agent-runs`
- `Agent -> OpenCode`: lance `opencode run`
- `Agent -> Orchestrator`: `RUN_STARTED`
- `AgentEventService -> Jira`: transition vers "In Progress"

## 8. Run agent envoie un progres

Interactions:

- `OpenCode -> Agent`: appelle `devflow_report_progress`
- `Agent -> Orchestrator`: `PROGRESS_REPORTED`
- `AgentEventService`: log seulement, aucune transition

## 9. Run agent demande une information externe

Interactions:

- `OpenCode -> Agent`: appelle `devflow_request_input`
- `Agent -> Orchestrator`: `INPUT_REQUIRED`
- `AgentEventService -> Jira`: transition vers "Blocked"
- `AgentEventService -> Jira`: poste le commentaire suggere
- `AgentEventService -> DevFlowRuntime`: `clearRun()`

## 10. Run agent termine la collecte d'information

Conditions:

- le run est en phase `INFORMATION_COLLECTION`

Interactions:

- `OpenCode -> Agent`: appelle `devflow_complete_run`
- `Agent -> Orchestrator`: `COMPLETED`
- `AgentEventService -> DevFlowRuntime`: `replacePhase(IMPLEMENTATION, newAgentRunId)`
- `AgentEventService -> Agent`: `POST /internal/agent-runs` (chaine direct)

## 11. Run agent termine l'implementation

Conditions:

- le run est en phase `IMPLEMENTATION`

Interactions:

- `OpenCode -> Agent`: appelle `devflow_complete_run`
- `Agent -> Orchestrator`: `COMPLETED`
- `AgentEventService -> GitHub`: inspecte les repositories, commit, push, cree/reutilise les PRs
- `AgentEventService -> DevFlowRuntime`: `addPublishedPR()`
- `AgentEventService -> Jira`: transition vers "To Review"
- `AgentEventService -> Jira`: poste un commentaire avec les liens PR et le resume
- `AgentEventService -> DevFlowRuntime`: `clearRun()`

Point important:

- `devflow_complete_run` veut dire "l'agent a fini le travail local"
- c'est l'orchestrateur qui cree les branches `devflow/...` et les PRs

## 12. Run agent termine en validation technique ou metier

Conditions:

- le run est en phase `TECHNICAL_VALIDATION` ou `BUSINESS_VALIDATION`

Interactions:

- `OpenCode -> Agent`: appelle `devflow_complete_run`
- `Agent -> Orchestrator`: `COMPLETED`
- `AgentEventService -> DevFlowRuntime`: `clearRun()`

## 13. Run agent echoue

Interactions:

- `OpenCode -> Agent`: appelle `devflow_fail_run`
- `Agent -> Orchestrator`: `FAILED`
- `AgentEventService -> Jira`: transition vers "Blocked"
- `AgentEventService -> Jira`: poste un commentaire d'erreur
- `AgentEventService -> DevFlowRuntime`: `clearRun()`

## 14. Processus OpenCode sort sans evenement terminal

Interactions:

- `OpenCode -> Agent`: le process se termine sans callback terminal
- `Agent -> Orchestrator`: envoie `FAILED` en fallback
- `AgentEventService`: traite comme un echec (scenario 13)

## 15. Annulation d'un run actif

Interactions:

- `Orchestrator -> Agent`: `POST /internal/agent-runs/{id}/cancel`
- `Agent`: envoie `SIGTERM`, puis `SIGKILL` si besoin
- `Agent -> Orchestrator`: `CANCELLED`
- `AgentEventService -> DevFlowRuntime`: `clearRun()`

## 16. Nouveau commentaire de review GitHub

Conditions:

- un commentaire de review existe sur une PR `devflow/*`
- le commentaire n'est pas d'un bot
- le `created_at` du commentaire est posterieur au dernier commit sur la branche

Interactions:

- `GitHubPollingJob`: detecte le commentaire de review
- `GitHubPollingJob`: verifie la deduplication (comparaison avec date du dernier commit)
- `GitHubPollingJob -> DevFlowRuntime`: `startRun()` avec `IMPLEMENTATION`
- `GitHubPollingJob -> Agent`: `POST /internal/agent-runs`

## 17. Commentaire de review GitHub deja traite

Conditions:

- le commentaire `created_at` precede ou est egal au dernier commit sur la branche

Interactions:

- `GitHubPollingJob`: detecte le commentaire
- `GitHubPollingJob`: `created_at <= lastCommitDate` → skip

## 18. Pull request GitHub mergee

Conditions:

- une PR fermee sur une branche `devflow/*` a `merged_at != null`
- le ticket est en statut "To Review"

Interactions:

- `GitHubPollingJob`: detecte la PR mergee via l'API closed PRs
- `GitHubPollingJob`: extrait le ticket key depuis le nom de branche
- `GitHubPollingJob`: verifie que le ticket est en "To Review"
- `GitHubPollingJob -> Jira`: transition vers "To Validate" (avant le commentaire pour l'idempotence)
- `GitHubPollingJob -> Jira`: poste un commentaire resume avec le titre et le corps de la PR

## 19. Pull request GitHub mergee — ticket pas en "To Review"

Conditions:

- la PR est mergee mais le ticket n'est pas en "To Review"

Interactions:

- `GitHubPollingJob`: charge le ticket, verifie le statut
- resultat: ignore (idempotence)

## 20. Polling Jira pendant un run actif

Interactions:

- `JiraTicketPollingJob`: `runtime.isBusy()` retourne `true`
- resultat: le cycle de polling est saute entierement

## 21. Polling GitHub Phase 2 pendant un run actif

Interactions:

- `GitHubPollingJob`: Phase 1 (merged PRs) s'execute normalement
- `GitHubPollingJob`: Phase 2 (review comments) → `runtime.isBusy()` retourne `true` → skip

## 22. Agent recoit une demande alors qu'un run est deja actif

Interactions:

- `Orchestrator -> Agent`: `POST /internal/agent-runs`
- `Agent`: detecte `activeRun`
- `Agent -> Orchestrator`: reponse HTTP `409 another_run_is_active`
- `Orchestrator`: le run echoue, `clearRun()` dans le catch

## 23. Run agent depasse la duree maximale (agent runtime timeout)

Conditions:

- le process OpenCode tourne depuis plus de `maxRunDurationMs` (15 minutes par defaut)
- aucun evenement terminal n'a ete envoye

Interactions:

- `Agent runtime`: le timer expire
- `Agent runtime -> OpenCode`: `SIGTERM`
- si le process ne se termine pas dans les 5 secondes: `SIGKILL`
- `Agent runtime`: le process exit declenche `handleProcessExit()`
- `Agent runtime -> Orchestrator`: `FAILED` en fallback (summary: "exceeded maximum duration")
- `AgentEventService`: traite comme un echec (scenario 13)

## 24. Run agent depasse la duree maximale (orchestrator stale-run detection)

Conditions:

- le run est actif depuis plus de `max-run-duration-minutes` (20 minutes par defaut)
- le scenario 23 n'a pas suffi (agent runtime injoignable ou bloque)

Interactions:

- `JiraTicketPollingJob` ou `GitHubPollingJob`: `runtime.isStale()` retourne `true`
- `Orchestrator -> Agent`: `POST /internal/agent-runs/{id}/cancel`
- `Orchestrator -> DevFlowRuntime`: `clearRunIfMatches()` (nettoyage local meme si cancel echoue)
- resultat: le polling reprend normalement au cycle suivant

## 25. Scenario nominal complet

Interactions:

- `JiraTicketPollingJob`: detecte un ticket "To Do" eligible
- `Orchestrator -> Agent`: run `INFORMATION_COLLECTION`
- `Agent -> Orchestrator`: `RUN_STARTED` → transition "In Progress"
- `Agent -> Orchestrator`: `COMPLETED` → chaine vers `IMPLEMENTATION`
- `Orchestrator -> Agent`: run `IMPLEMENTATION`
- `Agent -> Orchestrator`: `RUN_STARTED`
- `Agent -> Orchestrator`: `COMPLETED` → publish PR → transition "To Review"
- `GitHubPollingJob`: detecte la PR mergee → transition "To Validate"

## 26. Scenario complet avec review technique

Interactions:

- ticket eligible → info collection → implementation → PR creee
- `GitHubPollingJob`: detecte un commentaire de review
- `Orchestrator -> Agent`: nouveau run `IMPLEMENTATION`
- `Agent -> Orchestrator`: `COMPLETED` → push sur la branche existante
- `GitHubPollingJob`: detecte la PR mergee

## 27. Scenario complet bloque par manque d'information puis repris

Interactions:

- `JiraTicketPollingJob`: detecte un ticket "To Do" non eligible
- `JiraTicketPollingJob -> Jira`: commentaire de blocage d'eligibilite
- (cycle suivant): ticket toujours "To Do", skip (scenario 3)
- un utilisateur ajoute de l'information au ticket
- (cycle suivant): `shouldSkipIneligibleTicket()` retourne `false`, re-evaluation
- si eligible maintenant: demarre un run

## Resume

Les branches metier aujourd'hui sont:

- ticket ignore (non eligible, skip silencieux)
- ticket bloque en attente d'information (commentaire d'eligibilite poste)
- ticket en information collection (run agent actif)
- ticket en implementation (run agent actif)
- ticket en validation technique (PR ouverte, polling review comments)
- ticket valide (PR mergee, transition "To Validate")
- ticket en echec (run echoue, transition "Blocked")
- ticket bloque car input requis (agent a demande de l'information)
- run bloque detecte et annule (timeout agent runtime ou stale-run orchestrateur)
