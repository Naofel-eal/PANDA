# Scenarios

Exhaustive list of every scenario handled by the current implementation. This is not a design target — it describes what the code does today.

## Components

- **Jira** — polled periodically
- **GitHub** — polled periodically
- **Orchestrator** — stateless Quarkus app, volatile `currentRun`
- **Agent** — Node.js runtime + OpenCode

## Global rules

- `JiraTicketPollingJob` polls Jira every minute
- `GitHubPollingJob` polls GitHub every minute
- The orchestrator launches agent runs via direct HTTP (no outbox)
- OpenCode sends events to the orchestrator via Devflow tools
- The orchestrator is the only component that comments on Jira and publishes to GitHub
- One agent run at a time (volatile `currentRun`)
- No database — state is re-discovered from Jira/GitHub each cycle

## Ticket intake

### 1. Eligible "To Do" ticket

Conditions: ticket is in "To Do" in the configured epic, has title, description, and at least one repository is configured.

Flow: `JiraTicketPollingJob` → `EligibilityService` evaluates → `DevFlowRuntime.startRun()` → `POST /internal/agent-runs` (phase `INFORMATION_COLLECTION`) → agent sends `RUN_STARTED` → ticket transitions to "In Progress".

### 2. Ineligible "To Do" ticket

Conditions: missing title, description, or no repository configured.

Flow: `JiraTicketPollingJob` → `EligibilityService` returns missing fields → post Jira comment listing what's missing. Ticket stays in "To Do".

### 3. Ineligible ticket already assessed (skip)

Conditions: DevFlow already posted an eligibility comment, no new user comment or ticket update since then (beyond 60-second grace period).

Flow: `shouldSkipIneligibleTicket()` returns `true` → ticket is silently ignored.

### 4. Ineligible ticket updated by user

Conditions: DevFlow posted an eligibility comment, but the ticket was updated (description changed or new comment).

Flow: `shouldSkipIneligibleTicket()` returns `false` → re-evaluate → start run if now eligible, or post new eligibility comment.

## Blocked ticket handling

### 5. Blocked ticket with new user comment

Conditions: ticket in "Blocked" status, latest user comment is newer than latest DevFlow comment.

Flow: `JiraTicketPollingJob` detects new user comment → `startRun()` with `INFORMATION_COLLECTION` → agent resumes work.

Comment author identification uses the Jira account ID resolved via `/rest/api/3/myself`, with content-based fallback.

### 6. Blocked ticket without new comment

Flow: No new user comment detected → nothing happens.

## Agent lifecycle

### 7. Run started

Flow: `POST /internal/agent-runs` → agent spawns OpenCode → sends `RUN_STARTED` → orchestrator transitions ticket to "In Progress".

### 8. Progress reported

Flow: agent calls `devflow_report_progress` → sends `PROGRESS_REPORTED` → orchestrator logs only, no transition.

### 9. Input required

Flow: agent calls `devflow_request_input` → sends `INPUT_REQUIRED` → orchestrator transitions to "Blocked" + posts suggested comment + `clearRun()`.

### 10. Info collection completed

Conditions: run is in phase `INFORMATION_COLLECTION`.

Flow: agent calls `devflow_complete_run` → `COMPLETED` → `replacePhase(IMPLEMENTATION, newRunId)` → dispatch new agent run asynchronously (3s delay).

### 11. Implementation completed

Conditions: run is in phase `IMPLEMENTATION`.

Flow: agent calls `devflow_complete_run` → `COMPLETED` → orchestrator inspects repositories → commit + push + create/reuse PRs → transition to "To Review" + post Jira comment with PR links → `clearRun()`.

Key: `devflow_complete_run` means "local work is done". The orchestrator creates all branches and PRs.

### 12. Validation completed

Conditions: run is in phase `TECHNICAL_VALIDATION` or `BUSINESS_VALIDATION`.

Flow: agent calls `devflow_complete_run` → `COMPLETED` → `clearRun()`.

### 13. Run failed

Flow: agent calls `devflow_fail_run` → `FAILED` → transition to "Blocked" + post error comment + `clearRun()`.

### 14. Agent exits without terminal event

Flow: OpenCode process exits without calling any terminal tool → agent runtime sends `FAILED` as fallback (with diagnostic stdout/stderr tails) → treated as scenario 13.

### 15. Run cancelled

Flow: orchestrator sends `POST /internal/agent-runs/{id}/cancel` → agent sends `SIGTERM` → `SIGKILL` after 5s if needed → sends `CANCELLED` → `clearRun()`.

## GitHub integration

### 16. New review comment detected

Conditions: inline review comment on a `devflow/*` PR, not from a bot, `created_at` is after the last commit date on the branch.

Flow: `GitHubPollingJob` detects comment → `startRun()` with `IMPLEMENTATION` → agent addresses feedback → push to existing branch → reuse existing PR → transition to "To Review".

### 17. Review comment already addressed (skip)

Conditions: comment `created_at` is before or equal to the last commit date on the branch.

Flow: Comment is skipped (already addressed by a previous commit).

### 18. PR merged

Conditions: closed PR on a `devflow/*` branch with `merged_at != null`, ticket is in "To Review".

Flow: `GitHubPollingJob` detects merge → extracts ticket key from branch name → verifies ticket is in "To Review" → transitions to "To Validate" (before comment for idempotency) → posts merge comment.

### 19. PR merged but ticket not in "To Review"

Flow: Merge detected but ticket is not in "To Review" → ignored (idempotent).

## Busy/skip scenarios

### 20. Jira poll while agent is busy

Flow: `runtime.isBusy()` → `true` → entire Jira poll cycle is skipped.

### 21. GitHub Phase 2 while agent is busy

Flow: Phase 1 (merge detection) always runs. Phase 2 (review comments) → `runtime.isBusy()` → skip.

### 22. Agent receives run while another is active

Flow: `POST /internal/agent-runs` → agent detects `activeRun` → responds `409 another_run_is_active` → orchestrator clears run.

## Timeout scenarios

### 23. Agent runtime timeout (Layer 1)

Conditions: OpenCode runs longer than `maxRunDurationMs` (15 minutes), no terminal event sent.

Flow: timer fires → `SIGTERM` → 5s grace → `SIGKILL` if needed → `handleProcessExit()` → `FAILED` fallback with "exceeded maximum duration" → treated as scenario 13.

### 24. Orchestrator stale-run detection (Layer 2)

Conditions: run active longer than `max-run-duration-minutes` (20 minutes), Layer 1 did not resolve it.

Flow: polling job detects `runtime.isStale()` → sends cancel to agent → `clearRunIfMatches()` locally → polling resumes.

## End-to-end scenarios

### 25. Happy path

To Do → info collection → implementation → 2 PRs created → To Review → PRs merged → To Validate.

### 26. Happy path with review feedback

To Do → info collection → implementation → PRs created → review comment → agent fixes → push to branch → PRs merged → To Validate.

### 27. Ineligible then resumed

To Do (missing description) → eligibility comment → user adds description → re-evaluation → eligible → info collection → implementation.
