package io.nud.domain.model.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nud.domain.model.codehost.CodeChangeRef;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkflowTest {

    @Test
    @DisplayName("Given a workflow when it changes phase then the business context is preserved and the new run is tracked")
    void givenWorkflow_whenItChangesPhase_thenBusinessContextIsPreservedAndNewRunIsTracked() throws Exception {
        UUID workflowId = UUID.randomUUID();
        UUID firstRunId = UUID.randomUUID();
        Workflow workflow = Workflow.start(
            workflowId,
            firstRunId,
            "jira",
            "SCRUM-1",
            WorkflowPhase.INFORMATION_COLLECTION,
            "Analyze ticket"
        );
        CodeChangeRef codeChange = new CodeChangeRef("github", "repo#1", "repo", "https://example.com/pr/1", "head", "main");
        workflow.addPublishedPR(codeChange);

        UUID secondRunId = UUID.randomUUID();
        Workflow chained = workflow.chainToPhase(WorkflowPhase.IMPLEMENTATION, secondRunId);

        assertEquals(workflowId, chained.workflowId());
        assertEquals(secondRunId, chained.agentRunId());
        assertEquals("SCRUM-1", chained.ticketKey());
        assertEquals(WorkflowPhase.IMPLEMENTATION, chained.phase());
        assertEquals(1, chained.publishedPRs().size());
        assertEquals(codeChange, chained.publishedPRs().getFirst());
        assertTrue(chained.belongsTo(secondRunId));
        assertFalse(chained.belongsTo(firstRunId));
    }

    @Test
    @DisplayName("Given a workflow when freshness is checked then stale runs are detected and null pull requests are rejected")
    void givenWorkflow_whenFreshnessIsChecked_thenStaleRunsAreDetectedAndNullPullRequestsAreRejected() throws Exception {
        Workflow workflow = Workflow.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "jira",
            "SCRUM-1",
            WorkflowPhase.IMPLEMENTATION,
            "Implement ticket"
        );

        assertFalse(workflow.isStale(Duration.ofMinutes(1)));
        Thread.sleep(5);
        assertTrue(workflow.isStale(Duration.ZERO));
        assertThrows(NullPointerException.class, () -> workflow.addPublishedPR(null));
    }
}
