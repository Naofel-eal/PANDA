package io.devflow.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.devflow.application.command.agent.StartAgentRunCommand;
import io.devflow.application.command.codehost.PublishCodeChangesCommand;
import io.devflow.application.command.workspace.PrepareWorkspaceCommand;
import io.devflow.domain.exception.DomainException;
import io.devflow.domain.exception.WorkflowNotFoundException;
import io.devflow.domain.model.agent.AgentCommandType;
import io.devflow.domain.model.ticketing.ExternalCommentParentType;
import io.devflow.domain.model.ticketing.WorkItemRef;
import io.devflow.domain.model.workflow.RequestedFrom;
import io.devflow.domain.model.workflow.ResumeTrigger;
import io.devflow.domain.model.workflow.WorkflowPhase;
import io.devflow.infrastructure.agent.opencode.AgentRuntimeException;
import io.devflow.infrastructure.codehost.github.GitHubSystem;
import io.devflow.infrastructure.ticketing.jira.JiraSystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SharedValueObjectsTest {

    @Test
    @DisplayName("Given an agent start request when the command is created then DevFlow freezes the snapshot and marks it as a start intent")
    void givenAnAgentStartRequest_whenTheCommandIsCreated_thenDevFlowFreezesTheSnapshotAndMarksItAsAStartIntent() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ticket", "SCRUM-20");

        StartAgentRunCommand command = StartAgentRunCommand.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            WorkflowPhase.INFORMATION_COLLECTION,
            "Understand SCRUM-20",
            snapshot
        );
        snapshot.put("ticket", "changed");

        assertEquals(AgentCommandType.START_RUN, command.type());
        assertEquals("SCRUM-20", command.inputSnapshot().get("ticket"));
        assertThrows(UnsupportedOperationException.class, () -> command.inputSnapshot().put("other", "value"));
    }

    @Test
    @DisplayName("Given no initial agent snapshot when the command is created then DevFlow starts from an immutable empty context")
    void givenNoInitialAgentSnapshot_whenTheCommandIsCreated_thenDevFlowStartsFromAnImmutableEmptyContext() {
        StartAgentRunCommand command = StartAgentRunCommand.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            WorkflowPhase.IMPLEMENTATION,
            "Implement SCRUM-21",
            null
        );

        assertTrue(command.inputSnapshot().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> command.inputSnapshot().put("other", "value"));
    }

    @Test
    @DisplayName("Given repository preferences when the workspace command is created then DevFlow keeps a safe copy of repositories and preferred branches")
    void givenRepositoryPreferences_whenTheWorkspaceCommandIsCreated_thenDevFlowKeepsASafeCopyOfRepositoriesAndPreferredBranches() {
        List<String> repositories = new ArrayList<>(List.of("acme/api"));
        Map<String, String> branches = new LinkedHashMap<>(Map.of("acme/api", "feature"));

        PrepareWorkspaceCommand command = new PrepareWorkspaceCommand(UUID.randomUUID(), repositories, branches);
        repositories.add("acme/web");
        branches.put("acme/api", "changed");

        assertEquals(List.of("acme/api"), command.repositories());
        assertEquals(Map.of("acme/api", "feature"), command.preferredBranches());
    }

    @Test
    @DisplayName("Given no workspace preferences when the command is created then DevFlow normalizes the request to empty collections")
    void givenNoWorkspacePreferences_whenTheCommandIsCreated_thenDevFlowNormalizesTheRequestToEmptyCollections() {
        PrepareWorkspaceCommand command = new PrepareWorkspaceCommand(UUID.randomUUID(), null, null);

        assertTrue(command.repositories().isEmpty());
        assertTrue(command.preferredBranches().isEmpty());
    }

    @Test
    @DisplayName("Given mixed publication hints when the publish command is created then DevFlow keeps only meaningful repositories and a safe artifact copy")
    void givenMixedPublicationHints_whenThePublishCommandIsCreated_thenDevFlowKeepsOnlyMeaningfulRepositoriesAndASafeArtifactCopy() {
        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("summary", "Ready");
        List<String> repositories = new ArrayList<>(java.util.Arrays.asList("acme/api", " ", null, "acme/web"));

        PublishCodeChangesCommand command = new PublishCodeChangesCommand(
            UUID.randomUUID(),
            "SCRUM-21",
            "Publish",
            repositories,
            "Summary",
            artifacts
        );
        repositories.add("acme/mobile");
        artifacts.put("summary", "Changed");

        assertEquals(List.of("acme/api", "acme/web"), command.repositories());
        assertEquals("Ready", command.artifacts().get("summary"));
    }

    @Test
    @DisplayName("Given no publication hints when the publish command is created then DevFlow normalizes repositories and artifacts to empty values")
    void givenNoPublicationHints_whenThePublishCommandIsCreated_thenDevFlowNormalizesRepositoriesAndArtifactsToEmptyValues() {
        PublishCodeChangesCommand command = new PublishCodeChangesCommand(
            UUID.randomUUID(),
            "SCRUM-22",
            "Publish",
            null,
            "Summary",
            null
        );

        assertTrue(command.repositories().isEmpty());
        assertTrue(command.artifacts().isEmpty());
    }

    @Test
    @DisplayName("Given cross-system identifiers when they are compared then DevFlow recognizes Jira comments and workflow sources consistently")
    void givenCrossSystemIdentifiers_whenTheyAreCompared_thenDevFlowRecognizesJiraCommentsAndWorkflowSourcesConsistently() {
        assertTrue(ExternalCommentParentType.WORK_ITEM.matches("work_item"));
        assertFalse(ExternalCommentParentType.CODE_CHANGE.matches("ticket"));
        assertEquals("CODE_CHANGE", ExternalCommentParentType.CODE_CHANGE.id());
        assertTrue(JiraSystem.matches("JIRA"));
        assertFalse(JiraSystem.matches("github"));
        assertEquals("github", GitHubSystem.ID);
        assertEquals(List.of(RequestedFrom.BUSINESS, RequestedFrom.DEV, RequestedFrom.SYSTEM), List.of(RequestedFrom.values()));
        assertEquals(
            List.of(
                ResumeTrigger.WORK_ITEM_COMMENT_RECEIVED,
                ResumeTrigger.CODE_CHANGE_REVIEW_COMMENT_RECEIVED,
                ResumeTrigger.BUSINESS_VALIDATION_REPORTED,
                ResumeTrigger.MANUAL_RESUME_REQUESTED
            ),
            List.of(ResumeTrigger.values())
        );
    }

    @Test
    @DisplayName("Given workflow references and errors when they are created then DevFlow preserves the business context for debugging")
    void givenWorkflowReferencesAndErrors_whenTheyAreCreated_thenDevFlowPreservesTheBusinessContextForDebugging() {
        Throwable cause = new IllegalStateException("cause");
        DomainException domainException = new DomainException("Domain failure", cause);
        WorkflowNotFoundException workflowNotFoundException = new WorkflowNotFoundException("Workflow missing");
        AgentRuntimeException runtimeException = new AgentRuntimeException("Runtime failure", cause);
        WorkItemRef reference = new WorkItemRef("jira", "SCRUM-20", "https://jira.example/browse/SCRUM-20");

        assertEquals("Domain failure", domainException.getMessage());
        assertSame(cause, domainException.getCause());
        assertEquals("Workflow missing", workflowNotFoundException.getMessage());
        assertEquals("Runtime failure", runtimeException.getMessage());
        assertSame(cause, runtimeException.getCause());
        assertEquals("jira", reference.system());
        assertEquals("SCRUM-20", reference.key());
        assertEquals("https://jira.example/browse/SCRUM-20", reference.url());
    }
}
