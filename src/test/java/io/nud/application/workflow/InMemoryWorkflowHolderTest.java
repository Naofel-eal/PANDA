package io.nud.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nud.domain.model.codehost.CodeChangeRef;
import io.nud.domain.model.workflow.Workflow;
import io.nud.domain.model.workflow.WorkflowPhase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryWorkflowHolderTest {

    @Test
    @DisplayName("Given an active workflow when the holder tracks it then the current business run is exposed and can evolve")
    void givenActiveWorkflow_whenHolderTracksIt_thenCurrentBusinessRunIsExposedAndCanEvolve() throws Exception {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        Workflow workflow = Workflow.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "jira",
            "SCRUM-1",
            WorkflowPhase.INFORMATION_COLLECTION,
            "Analyze"
        );

        assertSame(workflow, holder.start(workflow));
        assertTrue(holder.isBusy());
        assertSame(workflow, holder.current());
        Thread.sleep(5);
        assertTrue(holder.isStale(Duration.ZERO));

        Workflow replaced = holder.replacePhase(WorkflowPhase.IMPLEMENTATION, UUID.randomUUID());
        holder.addPublishedPR(new CodeChangeRef("github", "repo#1", "repo", "https://example.com/pr/1", "head", "main"));

        assertEquals(WorkflowPhase.IMPLEMENTATION, replaced.phase());
        assertEquals(1, holder.current().publishedPRs().size());
    }

    @Test
    @DisplayName("Given holder lifecycle rules when concurrent or stale cleanup actions happen then only the matching run is cleared")
    void givenHolderLifecycleRules_whenConcurrentOrStaleCleanupActionsHappen_thenOnlyMatchingRunIsCleared() {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        Workflow workflow = Workflow.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "jira",
            "SCRUM-2",
            WorkflowPhase.IMPLEMENTATION,
            "Implement"
        );
        holder.start(workflow);

        assertThrows(IllegalStateException.class, () -> holder.start(workflow));
        holder.clearIfMatches(UUID.randomUUID());
        assertTrue(holder.isBusy());

        holder.clearIfMatches(workflow.agentRunId());
        assertFalse(holder.isBusy());
        assertNull(holder.current());
        holder.clear();
        assertThrows(IllegalStateException.class, () -> holder.replacePhase(WorkflowPhase.DONE, UUID.randomUUID()));
    }

    @Test
    @DisplayName("Given no active workflow when a pull request is reported then NUD ignores it without recreating state")
    void givenNoActiveWorkflow_whenAPullRequestIsReported_thenNUDIgnoresItWithoutRecreatingState() {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();

        holder.addPublishedPR(new CodeChangeRef("github", "repo#1", "repo", "https://example.com/pr/1", "head", "main"));
        holder.clearIfMatches(UUID.randomUUID());

        assertFalse(holder.isBusy());
        assertNull(holder.current());
    }
}
