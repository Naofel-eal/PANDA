# Devflow Workflow

Canonical end-to-end workflow for the orchestrator.

Dans l'implementation actuelle (v0 stateless):

- tout nouveau ticket eligible passe d'abord par un run agent `INFORMATION_COLLECTION`
- ce run ne code pas encore: il valide la comprehension, detecte les ambiguites
- il emet soit `INPUT_REQUIRED` (ticket bloque), soit `COMPLETED` (chaine vers `IMPLEMENTATION`)
- l'orchestrateur est stateless: pas de base de donnees, un seul `volatile currentRun`
- l'intake est polling-only depuis Jira et GitHub

```mermaid
flowchart TD
    start([Polling cycle])
    ignored([No action])
    done([To Validate])

    subgraph polling["Polling Discovery"]
        jiraPoll{Jira: ticket in To Do or Blocked?}
        githubPoll{GitHub: new review comment or merged PR?}
        busy{Agent busy?}
    end

    subgraph info["Information Collection"]
        eligible{Work item eligible?}
        alreadyAssessed{Already assessed and no new info?}
        enoughInfo{Enough information?}
        postEligibility[Post eligibility comment on ticket]
    end

    subgraph impl["Implementation"]
        implement[Agent implements solution]
        publishChanges[Orchestrator: commit, push, create PR]
        moveToReview[Transition ticket to To Review]
    end

    subgraph tech["Technical Validation"]
        reviewComment{New review comment on devflow PR?}
        commentAfterCommit{Comment after last commit?}
    end

    subgraph merge["Merge Detection"]
        merged{devflow PR merged?}
        ticketInReview{Ticket in To Review?}
        moveToValidate[Transition ticket to To Validate]
    end

    subgraph blocked["Blocked Handling"]
        inputRequired[Agent: INPUT_REQUIRED]
        moveToBlocked[Transition ticket to Blocked + comment]
        newUserComment{New user comment after DevFlow comment?}
    end

    start --> busy
    busy -- yes --> ignored
    busy -- no --> jiraPoll

    jiraPoll -- To Do ticket found --> eligible
    jiraPoll -- Blocked ticket found --> newUserComment
    jiraPoll -- nothing --> githubPoll

    eligible -- no --> alreadyAssessed
    alreadyAssessed -- yes --> ignored
    alreadyAssessed -- no --> postEligibility --> ignored
    eligible -- yes --> enoughInfo

    enoughInfo -- yes --> implement
    enoughInfo -- no --> inputRequired

    implement -- COMPLETED --> publishChanges --> moveToReview
    implement -- INPUT_REQUIRED --> inputRequired
    implement -- FAILED --> moveToBlocked

    inputRequired --> moveToBlocked

    newUserComment -- yes --> implement
    newUserComment -- no --> ignored

    githubPoll -- review comment --> reviewComment
    githubPoll -- merged PR --> merged
    githubPoll -- nothing --> ignored

    reviewComment -- yes --> commentAfterCommit
    commentAfterCommit -- yes --> implement
    commentAfterCommit -- no --> ignored

    merged -- yes --> ticketInReview
    ticketInReview -- yes --> moveToValidate --> done
    ticketInReview -- no --> ignored
```
