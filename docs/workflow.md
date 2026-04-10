# Workflow

End-to-end workflow for the current PANDA implementation.

## Flow summary

1. Eligible tickets in "To Do" start with an `INFORMATION_COLLECTION` run.
2. Ineligible tickets are moved to "Blocked" with a Jira comment that lists the missing information.
3. `INFORMATION_COLLECTION` either requests clarification or chains automatically into `IMPLEMENTATION`.
4. `IMPLEMENTATION` publishes code changes and moves the ticket to "To Review".
5. New human feedback on an open PANDA pull request triggers `TECHNICAL_VALIDATION` on the reviewed branch.
6. Merged PANDA pull requests move the ticket to "To Validate".
7. New Jira feedback on a ticket in "To Validate" triggers `BUSINESS_VALIDATION`.
8. Blocked and validation tickets can resume from either a new Jira comment or a later ticket update after the last PANDA comment.

## Diagram

```mermaid
flowchart TD
    start([Poll cycle])
    ignored([No action])
    done([Ticket in To Validate])

    subgraph polling["Polling"]
        busy{Workflow active?}
        jiraPoll{Jira ticket to process?}
        githubPoll{GitHub event to process?}
    end

    subgraph intake["Intake"]
        eligible{Eligible To Do ticket?}
        skipBlocked{Already blocked by eligibility comment and unchanged?}
        blockMissing[Ticket -> Blocked + missing info comment]
        infoCollect[INFORMATION_COLLECTION]
        chainImpl[Chain to IMPLEMENTATION]
    end

    subgraph implementation["Delivery"]
        implement[IMPLEMENTATION]
        publishImpl[Publish PRs]
        toReview[Ticket -> To Review]
    end

    subgraph review["Review"]
        reviewFeedback{New human PR feedback after last commit?}
        technicalValidation[TECHNICAL_VALIDATION]
        republishReview[Republish fixes]
    end

    subgraph mergeDetection["Merge"]
        merged{Merged panda PR?}
        inReview{Ticket currently in To Review?}
        toValidate[Ticket -> To Validate]
    end

    subgraph jiraFeedback["Jira feedback"]
        blockedFeedback{Blocked ticket has new comment or later update?}
        validateFeedback{To Validate ticket has new comment or later update?}
        businessValidation[BUSINESS_VALIDATION]
        inputRequired[INPUT_REQUIRED]
        failed[FAILED]
        toBlocked[Ticket -> Blocked + comment]
    end

    start --> busy
    busy -- yes --> ignored
    busy -- no --> jiraPoll

    jiraPoll -- To Do --> eligible
    jiraPoll -- Blocked --> blockedFeedback
    jiraPoll -- To Validate --> validateFeedback
    jiraPoll -- nothing --> githubPoll

    eligible -- no --> skipBlocked
    skipBlocked -- yes --> ignored
    skipBlocked -- no --> blockMissing --> ignored
    eligible -- yes --> infoCollect

    infoCollect -- COMPLETED --> chainImpl --> implement
    infoCollect -- INPUT_REQUIRED --> toBlocked
    infoCollect -- FAILED --> toBlocked

    implement -- COMPLETED --> publishImpl --> toReview
    implement -- INPUT_REQUIRED --> toBlocked
    implement -- FAILED --> toBlocked

    blockedFeedback -- yes --> infoCollect
    blockedFeedback -- no --> ignored

    validateFeedback -- yes --> businessValidation
    validateFeedback -- no --> githubPoll

    businessValidation -- COMPLETED --> publishImpl
    businessValidation -- INPUT_REQUIRED --> toBlocked
    businessValidation -- FAILED --> toBlocked

    githubPoll -- review feedback --> reviewFeedback
    githubPoll -- merged PR --> merged
    githubPoll -- nothing --> ignored

    reviewFeedback -- yes --> technicalValidation
    reviewFeedback -- no --> ignored

    technicalValidation -- COMPLETED --> republishReview --> toReview
    technicalValidation -- INPUT_REQUIRED --> toBlocked
    technicalValidation -- FAILED --> toBlocked

    merged -- yes --> inReview
    merged -- no --> ignored
    inReview -- yes --> toValidate --> done
    inReview -- no --> ignored

    inputRequired --> toBlocked
    failed --> toBlocked
```

## Jira status transitions

| From | To | Trigger |
|------|----|---------|
| To Do | In Progress | Eligible ticket starts an agent run (`RUN_STARTED`) |
| To Do | Blocked | Ticket is ineligible and PANDA posts the missing-info comment |
| Blocked | In Progress | Jira feedback resumes `INFORMATION_COLLECTION` |
| In Progress | Blocked | Agent requests input, fails, or publication fails |
| In Progress | To Review | `IMPLEMENTATION`, `TECHNICAL_VALIDATION`, or `BUSINESS_VALIDATION` publishes changes successfully |
| To Review | In Progress | GitHub review feedback starts `TECHNICAL_VALIDATION` |
| To Review | To Validate | A PANDA pull request merge is detected |
| To Validate | In Progress | Jira feedback starts `BUSINESS_VALIDATION` |

## Phase chaining

When `INFORMATION_COLLECTION` completes, the orchestrator chains directly to `IMPLEMENTATION`:

1. `HandleAgentEventUseCase` receives `COMPLETED` for the current `INFORMATION_COLLECTION` run.
2. `InMemoryWorkflowHolder.replacePhase(IMPLEMENTATION, newAgentRunId)` swaps the active phase while keeping the same workflow ID.
3. A new agent run is dispatched asynchronously after a 3-second delay.
4. The implementation run starts on the same shared workspace.
