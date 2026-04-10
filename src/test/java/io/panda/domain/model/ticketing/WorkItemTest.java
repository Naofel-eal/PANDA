package io.panda.domain.model.ticketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.panda.infrastructure.ticketing.jira.JiraSystem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkItemTest {

    @Test
    @DisplayName("Given missing metadata when a work item is created then collections are normalized and missing business fields are reported")
    void givenMissingMetadata_whenWorkItemIsCreated_thenCollectionsAreNormalizedAndMissingBusinessFieldsAreReported() {
        WorkItem workItem = new WorkItem(
            "SCRUM-1",
            "Task",
            " ",
            null,
            "To Do",
            "https://jira.example/browse/SCRUM-1",
            null,
            null,
            Instant.parse("2026-04-09T10:00:00Z")
        );

        assertTrue(workItem.labels().isEmpty());
        assertTrue(workItem.repositories().isEmpty());
        assertIterableEquals(List.of("title", "description"), workItem.missingFields());
        assertEquals(new WorkItemRef(JiraSystem.ID, "SCRUM-1", "https://jira.example/browse/SCRUM-1"), workItem.toReference(JiraSystem.ID));
    }

    @Test
    @DisplayName("Given a fully described work item when eligibility is checked then the ticket is considered actionable")
    void givenFullyDescribedWorkItem_whenEligibilityIsChecked_thenTicketIsConsideredActionable() {
        WorkItem workItem = new WorkItem(
            "SCRUM-1",
            "Task",
            "Implement helper text",
            "Add a helper text below the button",
            "To Do",
            "https://jira.example/browse/SCRUM-1",
            List.of("ui"),
            List.of("Naofel-eal/front-test"),
            Instant.parse("2026-04-09T10:00:00Z")
        );

        assertTrue(workItem.isEligible());
        assertTrue(workItem.missingFields().isEmpty());
    }

    @Test
    @DisplayName("Given a ticket with a title but no usable description when eligibility is checked then PANDA keeps it blocked on business context")
    void givenATicketWithATitleButNoUsableDescription_whenEligibilityIsChecked_thenPANDAKeepsItBlockedOnBusinessContext() {
        WorkItem workItem = new WorkItem(
            "SCRUM-2",
            "Task",
            "Implement helper text",
            "   ",
            "To Do",
            "https://jira.example/browse/SCRUM-2",
            List.of(),
            List.of(),
            Instant.parse("2026-04-09T10:00:00Z")
        );

        assertTrue(!workItem.isEligible());
        assertEquals(List.of("description"), workItem.missingFields());
    }
}
