# Scenarios

This file lists the concrete scenarios handled by the current implementation. It documents what the code does today, not an aspirational design.

## Components

- Jira is polled periodically.
- GitHub is polled periodically.
- The orchestrator is a Quarkus app with one in-memory active `Workflow`.
- The agent runtime is Node.js plus OpenCode.

## Global rules

- `JiraTicketPollingJob` polls Jira every minute by default.
- `GitHubPollingJob` polls GitHub every minute by default.
- The orchestrator launches agent runs via direct HTTP.
- OpenCode reports lifecycle events through NUD callback tools.
- The orchestrator alone comments on Jira and publishes to GitHub.
- Only one workflow can be active at a time.
- There is no persistent state store; Jira and GitHub are re-read every cycle.

## Ticket intake

### 1. Eligible "To Do" ticket

Conditions: the ticket is in the configured "To Do" status inside the configured epic, has the required fields, and at least one configured repository is available.

Flow: `JiraTicketPollingJob` loads the ticket and comments -> `StartInfoCollectionUseCase` starts a workflow in phase `INFORMATION_COLLECTION` -> `POST /internal/agent-runs` -> agent sends `RUN_STARTED` -> ticket transitions to "In Progress".

### 2. Ineligible "To Do" ticket

Conditions: title, description, or repository configuration is missing.

Flow: Jira polling detects missing fields -> ticket transitions to "Blocked" -> NUD posts a Jira comment listing the missing information.

### 3. Ineligible ticket already assessed

Conditions: NUD already posted the eligibility-blocking comment and there has been no later user comment and no later ticket update after the grace period.

Flow: `shouldSkipIneligibleTicket()` returns `true` -> the ticket is ignored for this poll cycle.

### 4. Ineligible ticket updated by the user

Conditions: NUD already blocked the ticket, but the user added a comment or updated the ticket after the last NUD comment.

Flow: the ticket is re-evaluated -> if now eligible, `INFORMATION_COLLECTION` starts; otherwise NUD blocks it again with an updated missing-info comment.

## Blocked ticket handling

### 5. Blocked ticket with new user comment

Conditions: the ticket is in "Blocked" and the latest user comment is newer than the latest NUD comment.

Flow: Jira polling resumes the workflow in phase `INFORMATION_COLLECTION`.

### 6. Blocked ticket updated after the last NUD comment

Conditions: the ticket is in "Blocked", there is no newer user comment, but the ticket `updated` timestamp is later than the last NUD comment plus the reassessment grace period.

Flow: Jira polling resumes the workflow in phase `INFORMATION_COLLECTION`.

### 7. Blocked ticket without new feedback

Flow: nothing happens.

## To Validate ticket handling

### 8. To Validate ticket with new user comment

Conditions: the ticket is in "To Validate" and the latest user comment is newer than the latest NUD comment.

Flow: Jira polling resumes the workflow in phase `BUSINESS_VALIDATION`.

### 9. To Validate ticket updated after the last NUD comment

Conditions: the ticket is in "To Validate", there is no newer user comment, but the ticket `updated` timestamp is later than the last NUD comment plus the reassessment grace period.

Flow: Jira polling resumes the workflow in phase `BUSINESS_VALIDATION`.

### 10. To Validate ticket without new feedback

Flow: nothing happens.

## Agent lifecycle

### 11. Run started

Flow: `POST /internal/agent-runs` -> the runtime spawns OpenCode -> `RUN_STARTED` is sent -> the ticket transitions to "In Progress".

### 12. Progress reported

Flow: the agent calls `nud_report_progress` -> `PROGRESS_REPORTED` is logged only.

### 13. Input required

Flow: the agent calls `nud_request_input` -> `INPUT_REQUIRED` -> ticket transitions to "Blocked" -> suggested Jira comment is posted -> active workflow is cleared.

### 14. Information collection completed

Conditions: the active phase is `INFORMATION_COLLECTION`.

Flow: the agent calls `nud_complete_run` -> `COMPLETED` -> `InMemoryWorkflowHolder.replacePhase(IMPLEMENTATION, newAgentRunId)` -> a new implementation run is dispatched asynchronously after 3 seconds.

### 15. Implementation completed

Conditions: the active phase is `IMPLEMENTATION`.

Flow: the agent calls `nud_complete_run` -> `COMPLETED` -> the orchestrator publishes code changes -> ticket moves to "To Review" -> Jira comment with PR links is posted -> workflow is cleared.

### 16. Technical validation completed

Conditions: the active phase is `TECHNICAL_VALIDATION`.

Flow: the agent calls `nud_complete_run` -> `COMPLETED` -> the orchestrator publishes review fixes, typically to the same branch and PR -> the ticket remains or returns to "To Review" -> workflow is cleared.

### 17. Business validation completed

Conditions: the active phase is `BUSINESS_VALIDATION`.

Flow: the agent calls `nud_complete_run` -> `COMPLETED` -> the orchestrator publishes the business follow-up changes -> ticket transitions to "To Review" -> workflow is cleared.

### 18. Completed event but no publishable changes exist

Conditions: the agent emits `COMPLETED`, but `GitHubCodeHostAdapter.publish()` finds no local changes across the workflow repositories.

Flow: publication throws -> the ticket transitions to "Blocked" with a publish-failure comment -> workflow is cleared.

### 19. Run failed

Flow: the agent calls `nud_fail_run` -> `FAILED` -> the ticket transitions to "Blocked" -> a failure comment is posted -> workflow is cleared.

### 20. Agent exits without a terminal event

Flow: OpenCode exits without `INPUT_REQUIRED`, `COMPLETED`, or `FAILED` -> the runtime sends fallback `FAILED` with diagnostic tails -> handled as scenario 19.

### 21. Run cancelled

Flow: the orchestrator sends `POST /internal/agent-runs/{id}/cancel` -> the runtime sends `SIGTERM`, then `SIGKILL` after 5 seconds if needed -> `CANCELLED` is emitted -> workflow is cleared.

## GitHub integration

### 22. New review feedback detected

Conditions: a human review comment or regular pull-request issue comment exists on an open `nud/*` PR, and its `created_at` timestamp is later than the latest commit on the PR branch.

Flow: `GitHubPollingJob` gathers all relevant comments -> builds a `CodeChangeRef` for the PR -> `HandleReviewCommentUseCase` starts `TECHNICAL_VALIDATION` scoped to the reviewed repository and source branch.

### 23. Review feedback already addressed

Conditions: the feedback comment is older than or equal to the latest commit on the branch.

Flow: the comment is ignored as already addressed.

### 24. Pull request merged

Conditions: a closed `nud/*` pull request has `merged_at != null`, the related Jira ticket is still in "To Review", and the merge happened after the ticket's latest update timestamp.

Flow: `GitHubPollingJob` detects the merge -> extracts the ticket key from the branch name -> `HandleMergedPullRequestUseCase` transitions the ticket to "To Validate" and posts a Jira comment.

### 25. Pull request merged but ticket is not in "To Review"

Flow: the merge is ignored for idempotency.

## Busy and skip scenarios

### 26. Jira poll while a workflow is active

Flow: `workflowHolder.isBusy()` is `true` -> the entire Jira poll cycle is skipped.

### 27. GitHub review-feedback phase while a workflow is active

Flow: merge detection still runs, but review-feedback polling is skipped.

### 28. Agent runtime receives a start request while another run is active

Flow: `POST /internal/agent-runs` returns `409 another_run_is_active` -> the orchestrator treats the dispatch as failed and clears the workflow locally.

## Timeout scenarios

### 29. Agent runtime timeout

Conditions: OpenCode exceeds `AGENT_MAX_RUN_DURATION_MINUTES` (default `15`) without sending a terminal event.

Flow: timer fires -> `SIGTERM` -> 5-second grace period -> `SIGKILL` if needed -> fallback `FAILED` event -> handled as scenario 19.

### 30. Orchestrator stale-run cancellation

Conditions: the active workflow exceeds `AGENT_MAX_RUN_DURATION_MINUTES + AGENT_STALE_TIMEOUT_BUFFER_MINUTES` (default `20` total minutes) and the runtime timeout did not resolve it first.

Flow: a polling job detects staleness -> the orchestrator sends a cancel command -> it clears the workflow locally -> normal polling resumes.

## End-to-end examples

### 31. Happy path

To Do -> information collection -> implementation -> pull requests created -> To Review -> pull requests merged -> To Validate.

### 32. Happy path with technical review feedback

To Do -> information collection -> implementation -> To Review -> GitHub review feedback -> technical validation -> same PR updated -> PR merged -> To Validate.

### 33. Happy path with business validation feedback

To Do -> information collection -> implementation -> To Review -> PR merged -> To Validate -> Jira feedback -> business validation -> new or reused PR -> To Review.

### 34. Ineligible then resumed

To Do with missing fields -> Blocked with eligibility comment -> user updates the ticket -> re-evaluation -> information collection -> implementation.
