# Workflow

End-to-end workflow for the DevFlow orchestrator.

## Flow summary

1. Every eligible ticket starts with an `INFORMATION_COLLECTION` agent run — the agent explores the codebase, validates its understanding, and detects ambiguities.
2. If the agent needs clarification, it calls `devflow_request_input` and the ticket is blocked with a Jira comment.
3. If the agent has enough context, it calls `devflow_complete_run` and the orchestrator chains directly to an `IMPLEMENTATION` run.
4. After implementation, the orchestrator commits, pushes, and creates PRs. The ticket moves to "To Review".
5. Review comments on PRs trigger new agent runs to address feedback.
6. Once all PRs are merged, the ticket moves to "To Validate".
7. A new Jira comment on a ticket in "To Validate" triggers a fresh implementation run.

## Diagram

```mermaid
flowchart TD
    start([Poll cycle])
    ignored([No action])
    done([To Validate])

    subgraph polling["Polling"]
        busy{Agent busy?}
        jiraPoll{Jira: To Do, Blocked, or To Validate ticket?}
        githubPoll{GitHub: review comment or merged PR?}
    end

    subgraph info["Information Collection"]
        eligible{Eligible?}
        alreadySkipped{Already assessed?}
        postEligibility[Post eligibility comment]
        enoughInfo{Enough info?}
    end

    subgraph impl["Implementation"]
        implement[Agent implements]
        publish[Commit, push, create PR]
        toReview[Ticket -> To Review]
    end

    subgraph review["Review"]
        reviewComment{New review comment?}
        afterCommit{After last commit?}
    end

    subgraph mergeDetection["Merge"]
        merged{PR merged?}
        inReview{Ticket in To Review?}
        toValidate[Ticket -> To Validate]
    end

    subgraph blocked["Blocked"]
        inputRequired[INPUT_REQUIRED]
        toBlocked[Ticket -> Blocked + comment]
        newComment{New user comment?}
    end

    subgraph validation["To Validate"]
        validationComment{New user comment?}
    end

    start --> busy
    busy -- yes --> ignored
    busy -- no --> jiraPoll

    jiraPoll -- To Do --> eligible
    jiraPoll -- Blocked --> newComment
    jiraPoll -- To Validate --> validationComment
    jiraPoll -- nothing --> githubPoll

    eligible -- no --> alreadySkipped
    alreadySkipped -- yes --> ignored
    alreadySkipped -- no --> postEligibility --> ignored
    eligible -- yes --> enoughInfo

    enoughInfo -- yes --> implement
    enoughInfo -- no --> inputRequired

    implement -- COMPLETED --> publish --> toReview
    implement -- INPUT_REQUIRED --> inputRequired
    implement -- FAILED --> toBlocked

    inputRequired --> toBlocked

    newComment -- yes --> implement
    newComment -- no --> ignored

    validationComment -- yes --> implement
    validationComment -- no --> ignored

    githubPoll -- review --> reviewComment
    githubPoll -- merged --> merged
    githubPoll -- nothing --> ignored

    reviewComment -- yes --> afterCommit
    afterCommit -- yes --> implement
    afterCommit -- no --> ignored

    merged -- yes --> inReview
    inReview -- yes --> toValidate --> done
    inReview -- no --> ignored
```

## Jira status transitions

| From | To | Trigger |
|------|----|---------|
| To Do | In Progress | Agent run starts (`RUN_STARTED`) |
| In Progress | To Review | Implementation completed, PRs created |
| In Progress | Blocked | Agent needs input or fails |
| Blocked | In Progress | New user comment triggers a new agent run |
| To Review | In Progress | Review comment triggers a fix run |
| To Review | To Validate | All PRs merged |
| To Validate | In Progress | New ticket comment triggers a new implementation run |

## Phase chaining

When `INFORMATION_COLLECTION` completes, the orchestrator chains directly to `IMPLEMENTATION`:

1. `AgentEventService` receives `COMPLETED` for info collection
2. `DevFlowRuntime.replacePhase(IMPLEMENTATION, newAgentRunId)` updates the volatile reference
3. A new agent run is dispatched asynchronously with a 3-second delay (to avoid deadlock on the callback thread)
4. The agent starts implementing on the same workspace
