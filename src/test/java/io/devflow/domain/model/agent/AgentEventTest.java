package io.devflow.domain.model.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.devflow.domain.model.codehost.CodeChangeRef;
import io.devflow.domain.model.workflow.BlockerType;
import io.devflow.domain.model.workflow.RequestedFrom;
import io.devflow.domain.model.workflow.ResumeTrigger;
import io.devflow.domain.model.workflow.WorkflowPhase;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentEventTest {

    @Test
    @DisplayName("Given missing ticket information when the clarification request is normalized then DevFlow asks the business for the missing context")
    void givenMissingTicketInformation_whenClarificationRequestIsNormalized_thenDevFlowAsksBusinessForTheMissingContext() {
        LinkedHashMap<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put(null, "ignored");
        artifacts.put("repoChanges", List.of(
            Map.of("repository", "acme/platform"),
            Map.of("repository", " "),
            Map.of("other", "ignored")
        ));

        AgentEvent event = event(
            AgentEventType.INPUT_REQUIRED,
            null,
            null,
            null,
            null,
            " ",
            null,
            artifacts,
            Map.of("missingInformation", List.of("  business value  ", " ", "acceptance criteria"))
        );

        assertEquals(BlockerType.MISSING_TICKET_INFORMATION, event.resolvedBlockerType());
        assertEquals("TASK_CONTEXT_UNCLEAR", event.normalizedReasonCode(WorkflowPhase.INFORMATION_COLLECTION));
        assertEquals(RequestedFrom.BUSINESS, event.resolvedRequestedFrom(WorkflowPhase.INFORMATION_COLLECTION));
        assertEquals(ResumeTrigger.WORK_ITEM_COMMENT_RECEIVED, event.resolvedResumeTrigger(WorkflowPhase.INFORMATION_COLLECTION));
        assertEquals("Missing information: business value", event.normalizedSummary());
        assertEquals("Clarification needed before continuing: business value", event.normalizedSuggestedComment());
        assertEquals(List.of("acme/platform"), event.extractRepoChangesRepositories());
        assertFalse(event.artifacts().containsKey(null));
        assertThrows(UnsupportedOperationException.class, () -> event.artifacts().put("other", "value"));
        assertThrows(UnsupportedOperationException.class, () -> event.details().put("other", "value"));
    }

    @Test
    @DisplayName("Given a very long missing requirement when clarification is prepared then DevFlow truncates the message for Jira readability")
    void givenVeryLongMissingRequirement_whenClarificationIsPrepared_thenDevFlowTruncatesTheMessageForJiraReadability() {
        String longRequirement = "customer expectation ".repeat(30);
        AgentEvent event = event(
            AgentEventType.INPUT_REQUIRED,
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            Map.of("missingInformation", List.of(longRequirement))
        );

        assertTrue(event.normalizedSuggestedComment().endsWith("..."));
        assertTrue(event.normalizedSuggestedComment().length() <= 320);
        assertTrue(event.normalizedSummary().endsWith("..."));
        assertTrue(event.normalizedSummary().length() <= 280);
    }

    @Test
    @DisplayName("Given a technical review blocker when metadata is absent then DevFlow routes the clarification back to developers")
    void givenTechnicalReviewBlocker_whenMetadataIsAbsent_thenDevFlowRoutesTheClarificationBackToDevelopers() {
        AgentEvent event = event(
            AgentEventType.INPUT_REQUIRED,
            BlockerType.WAITING_TECHNICAL_REVIEW,
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            Map.of()
        );

        assertEquals("WAITING_TECHNICAL_REVIEW", event.normalizedReasonCode(WorkflowPhase.TECHNICAL_VALIDATION));
        assertEquals(RequestedFrom.DEV, event.resolvedRequestedFrom(WorkflowPhase.TECHNICAL_VALIDATION));
        assertEquals(ResumeTrigger.CODE_CHANGE_REVIEW_COMMENT_RECEIVED, event.resolvedResumeTrigger(WorkflowPhase.TECHNICAL_VALIDATION));
    }

    @Test
    @DisplayName("Given a system setup blocker when metadata is absent then DevFlow requires a manual resume")
    void givenSystemSetupBlocker_whenMetadataIsAbsent_thenDevFlowRequiresAManualResume() {
        AgentEvent event = event(
            AgentEventType.INPUT_REQUIRED,
            BlockerType.MISSING_REPOSITORY_MAPPING,
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            Map.of()
        );

        assertEquals("NO_REPOSITORIES_FOUND", event.normalizedReasonCode(WorkflowPhase.IMPLEMENTATION));
        assertEquals(RequestedFrom.SYSTEM, event.resolvedRequestedFrom(WorkflowPhase.IMPLEMENTATION));
        assertEquals(ResumeTrigger.MANUAL_RESUME_REQUESTED, event.resolvedResumeTrigger(WorkflowPhase.IMPLEMENTATION));
    }

    @Test
    @DisplayName("Given a business validation blocker when metadata is absent then DevFlow waits for a validation reply from the business")
    void givenBusinessValidationBlocker_whenMetadataIsAbsent_thenDevFlowWaitsForAValidationReplyFromTheBusiness() {
        AgentEvent event = event(
            AgentEventType.INPUT_REQUIRED,
            BlockerType.WAITING_BUSINESS_FEEDBACK,
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            Map.of()
        );

        assertEquals("WAITING_BUSINESS_FEEDBACK", event.normalizedReasonCode(WorkflowPhase.BUSINESS_VALIDATION));
        assertEquals(RequestedFrom.BUSINESS, event.resolvedRequestedFrom(WorkflowPhase.BUSINESS_VALIDATION));
        assertEquals(ResumeTrigger.BUSINESS_VALIDATION_REPORTED, event.resolvedResumeTrigger(WorkflowPhase.BUSINESS_VALIDATION));
    }

    @Test
    @DisplayName("Given explicit clarification metadata when the event is normalized then DevFlow keeps the intended owner and resume trigger")
    void givenExplicitClarificationMetadata_whenTheEventIsNormalized_thenDevFlowKeepsTheIntendedOwnerAndResumeTrigger() {
        AgentEvent event = event(
            AgentEventType.INPUT_REQUIRED,
            BlockerType.NOT_ELIGIBLE,
            "  Need manual review !! ",
            RequestedFrom.DEV,
            ResumeTrigger.MANUAL_RESUME_REQUESTED,
            "  custom   summary  ",
            "  custom   comment  ",
            Map.of(),
            Map.of()
        );

        assertEquals("NEED_MANUAL_REVIEW", event.normalizedReasonCode(WorkflowPhase.INFORMATION_COLLECTION));
        assertEquals(RequestedFrom.DEV, event.resolvedRequestedFrom(WorkflowPhase.INFORMATION_COLLECTION));
        assertEquals(ResumeTrigger.MANUAL_RESUME_REQUESTED, event.resolvedResumeTrigger(WorkflowPhase.INFORMATION_COLLECTION));
        assertEquals("custom summary", event.normalizedSummary());
        assertEquals("custom comment", event.normalizedSuggestedComment());
    }

    @Test
    @DisplayName("Given an agent failure with error details when DevFlow explains it then the blocked comment contains the summary and root cause")
    void givenAgentFailureWithErrorDetails_whenDevFlowExplainsIt_thenTheBlockedCommentContainsTheSummaryAndRootCause() {
        AgentEvent event = event(
            AgentEventType.FAILED,
            null,
            "  provider outage  ",
            null,
            null,
            "  Could not publish the change  ",
            null,
            Map.of(),
            Map.of("error", "  remote rejected  ")
        );

        assertEquals("PROVIDER_OUTAGE", event.normalizedReasonCode(WorkflowPhase.IMPLEMENTATION));
        assertTrue(event.buildFailureComment().contains("Could not publish the change"));
        assertTrue(event.buildFailureComment().contains("Detail: remote rejected"));
        assertTrue(event.buildFailureComment().contains("Devflow marked this ticket as blocked"));
    }

    @Test
    @DisplayName("Given an agent failure without a reason code when DevFlow explains it then a generic failure reason is used")
    void givenAgentFailureWithoutAReasonCode_whenDevFlowExplainsIt_thenAGenericFailureReasonIsUsed() {
        AgentEvent event = event(
            AgentEventType.FAILED,
            null,
            "   ",
            null,
            null,
            null,
            null,
            Map.of(),
            Map.of("solution", "  retry later  ")
        );

        assertEquals("AGENT_RUN_FAILED", event.normalizedReasonCode(WorkflowPhase.IMPLEMENTATION));
        assertTrue(event.buildFailureComment().contains("Detail: retry later"));
    }

    @Test
    @DisplayName("Given published pull requests when the technical validation message is built then every repository is listed for review")
    void givenPublishedPullRequests_whenTheTechnicalValidationMessageIsBuilt_thenEveryRepositoryIsListedForReview() {
        AgentEvent event = event(
            AgentEventType.COMPLETED,
            null,
            null,
            null,
            null,
            "Ready for review",
            null,
            Map.of(),
            Map.of()
        );

        String comment = event.buildTechnicalValidationComment(List.of(
            new CodeChangeRef("github", "1", "acme/api", "https://github.com/acme/api/pull/1", "feature", "main"),
            new CodeChangeRef("github", "2", "acme/web", "", "feature", "main")
        ));

        assertTrue(comment.startsWith("Ready for review"));
        assertTrue(comment.contains("Pull requests created:"));
        assertTrue(comment.contains("- acme/api: https://github.com/acme/api/pull/1"));
        assertTrue(comment.contains("- acme/web"));
    }

    @Test
    @DisplayName("Given no completion summary when the technical validation message is built then DevFlow uses its default review message")
    void givenNoCompletionSummary_whenTheTechnicalValidationMessageIsBuilt_thenDevFlowUsesItsDefaultReviewMessage() {
        AgentEvent event = event(
            AgentEventType.COMPLETED,
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            Map.of()
        );

        String comment = event.buildTechnicalValidationComment(List.of(
            new CodeChangeRef("github", "1", "acme/api", "https://github.com/acme/api/pull/1", "feature", "main")
        ));

        assertTrue(comment.startsWith("Implementation completed and ready for technical validation."));
        assertTrue(comment.contains("Pull request created:"));
    }

    @Test
    @DisplayName("Given no pull requests were published when the technical validation message is built then DevFlow only reports the implementation outcome")
    void givenNoPullRequestsWerePublished_whenTheTechnicalValidationMessageIsBuilt_thenDevFlowOnlyReportsTheImplementationOutcome() {
        AgentEvent event = event(
            AgentEventType.COMPLETED,
            null,
            null,
            null,
            null,
            "Implementation completed",
            null,
            Map.of(),
            Map.of()
        );

        String comment = event.buildTechnicalValidationComment(List.of());

        assertEquals("Implementation completed", comment);
    }

    @Test
    @DisplayName("Given the failure detail repeats the summary when DevFlow explains a failed run then the detail is not duplicated")
    void givenTheFailureDetailRepeatsTheSummary_whenDevFlowExplainsAFailedRun_thenTheDetailIsNotDuplicated() {
        AgentEvent event = event(
            AgentEventType.FAILED,
            null,
            "provider outage",
            null,
            null,
            "remote rejected",
            null,
            Map.of(),
            Map.of("error", "remote rejected")
        );

        String comment = event.buildFailureComment();

        assertTrue(comment.contains("remote rejected"));
        assertFalse(comment.contains("Detail: remote rejected"));
    }

    @Test
    @DisplayName("Given unusable clarification details when the event is normalized then DevFlow falls back to its default business guidance")
    void givenUnusableClarificationDetails_whenTheEventIsNormalized_thenDevFlowFallsBackToItsDefaultBusinessGuidance() {
        AgentEvent event = event(
            AgentEventType.INPUT_REQUIRED,
            BlockerType.MISSING_TICKET_INFORMATION,
            null,
            null,
            null,
            null,
            null,
            Map.of("repoChanges", "not-a-list"),
            Map.of("missingInformation", List.of(" ", 42))
        );

        assertEquals("Additional clarification is required before the agent can continue.", event.normalizedSummary());
        assertEquals("Clarification is needed before the agent can continue this ticket.", event.normalizedSuggestedComment());
        assertTrue(event.extractRepoChangesRepositories().isEmpty());
    }

    @Test
    @DisplayName("Given no execution environment when clarification is normalized then DevFlow asks the system for manual recovery")
    void givenNoExecutionEnvironment_whenClarificationIsNormalized_thenDevFlowAsksTheSystemForManualRecovery() {
        AgentEvent event = event(
            AgentEventType.INPUT_REQUIRED,
            BlockerType.NO_EXECUTION_ENVIRONMENT,
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            Map.of()
        );

        assertEquals("NO_EXECUTION_ENVIRONMENT", event.normalizedReasonCode(WorkflowPhase.IMPLEMENTATION));
        assertEquals(RequestedFrom.SYSTEM, event.resolvedRequestedFrom(WorkflowPhase.IMPLEMENTATION));
        assertEquals(ResumeTrigger.MANUAL_RESUME_REQUESTED, event.resolvedResumeTrigger(WorkflowPhase.IMPLEMENTATION));
    }

    private AgentEvent event(
        AgentEventType type,
        BlockerType blockerType,
        String reasonCode,
        RequestedFrom requestedFrom,
        ResumeTrigger resumeTrigger,
        String summary,
        String suggestedComment,
        Map<String, Object> artifacts,
        Map<String, Object> details
    ) {
        return new AgentEvent(
            "event-1",
            UUID.randomUUID(),
            UUID.randomUUID(),
            type,
            Instant.parse("2026-04-09T10:00:00Z"),
            "provider-1",
            summary,
            blockerType,
            reasonCode,
            requestedFrom,
            resumeTrigger,
            suggestedComment,
            artifacts,
            details
        );
    }
}
