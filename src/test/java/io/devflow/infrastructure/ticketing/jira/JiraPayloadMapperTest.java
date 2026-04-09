package io.devflow.infrastructure.ticketing.jira;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.devflow.domain.model.ticketing.IncomingComment;
import io.devflow.support.ReflectionTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JiraPayloadMapperTest {

    @Test
    @DisplayName("Given a Jira issue payload when it is mapped then DevFlow keeps the ticket meaning, browse URL and update timestamp")
    void givenJiraIssuePayload_whenItIsMapped_thenDevFlowKeepsTheTicketMeaningBrowseUrlAndUpdateTimestamp() {
        JiraPayloadMapper mapper = mapper("https://jira.example/");

        var workItem = mapper.toWorkItem(Map.of(
            "key", "SCRUM-10",
            "fields", Map.of(
                "issuetype", Map.of("name", "Story"),
                "summary", "Improve onboarding",
                "description", Map.of(
                    "content", List.of(
                        Map.of("content", List.of(Map.of("text", "Clarify the business goal"))),
                        Map.of("content", List.of(Map.of("text", "Add acceptance criteria")))
                    )
                ),
                "status", Map.of("name", "To Do"),
                "labels", List.of("ux", "onboarding"),
                "updated", "2026-04-09T10:11:12.123+0200"
            )
        ));

        assertEquals("SCRUM-10", workItem.key());
        assertEquals("Story", workItem.type());
        assertEquals("Improve onboarding", workItem.title());
        assertEquals("Clarify the business goal\nAdd acceptance criteria", workItem.description());
        assertEquals("To Do", workItem.status());
        assertEquals("https://jira.example/browse/SCRUM-10", workItem.url());
        assertEquals(List.of("ux", "onboarding"), workItem.labels());
        assertEquals(Instant.parse("2026-04-09T08:11:12.123Z"), workItem.updatedAt());
    }

    @Test
    @DisplayName("Given a Jira comment payload when it is mapped then DevFlow keeps the author, body and timestamps regardless of Jira date format")
    void givenJiraCommentPayload_whenItIsMapped_thenDevFlowKeepsTheAuthorBodyAndTimestampsRegardlessOfJiraDateFormat() {
        JiraPayloadMapper mapper = mapper("https://jira.example");

        IncomingComment comment = mapper.toComment(Map.of(
            "id", "comment-1",
            "author", Map.of("accountId", "acct-123"),
            "body", Map.of(
                "content", List.of(
                    Map.of("content", List.of(Map.of("text", "Please cover the business rule"))),
                    Map.of("content", List.of(Map.of("text", "Also add a regression test")))
                )
            ),
            "created", 1_744_190_400_000L,
            "updated", "2026-04-09T10:15:30Z"
        ), "SCRUM-10");

        assertEquals("comment-1", comment.id());
        assertEquals("WORK_ITEM", comment.parentType());
        assertEquals("SCRUM-10", comment.parentId());
        assertEquals("acct-123", comment.authorId());
        assertEquals("Please cover the business rule\nAlso add a regression test", comment.body());
        assertEquals(Instant.ofEpochMilli(1_744_190_400_000L), comment.createdAt());
        assertEquals(Instant.parse("2026-04-09T10:15:30Z"), comment.updatedAt());
    }

    @Test
    @DisplayName("Given Jira search payloads when pagination helpers are used then DevFlow keeps only valid issues and comments")
    void givenJiraSearchPayloads_whenPaginationHelpersAreUsed_thenDevFlowKeepsOnlyValidIssuesAndComments() {
        JiraPayloadMapper mapper = mapper("https://jira.example");
        Map<String, Object> payload = Map.of(
            "issues", List.of(
                Map.of("key", "SCRUM-1", "fields", Map.of()),
                "ignored"
            ),
            "comments", List.of(
                Map.of(
                    "id", "comment-1",
                    "author", Map.of("accountId", "acct-1"),
                    "body", "Useful comment"
                ),
                "ignored"
            ),
            "nextPageToken", "page-2",
            "isLast", true
        );

        assertEquals(1, mapper.extractIssues(payload).size());
        assertEquals(1, mapper.extractComments(payload, "SCRUM-1").size());
        assertEquals("page-2", mapper.extractNextPageToken(payload));
        assertTrue(mapper.isLastPage(payload));
        assertFalse(mapper.isLastPage(Map.of()));
        assertEquals(List.of(), mapper.extractIssues(Map.of("issues", "ignored")));
        assertEquals(List.of(), mapper.extractComments(Map.of("comments", "ignored"), "SCRUM-1"));
    }

    @Test
    @DisplayName("Given optional Jira fields when they are absent then DevFlow safely returns empty values instead of failing")
    void givenOptionalJiraFields_whenTheyAreAbsent_thenDevFlowSafelyReturnsEmptyValuesInsteadOfFailing() {
        JiraPayloadMapper mapper = mapper("https://jira.example");

        assertNull(mapper.extractIssueUpdatedAt(Map.of("fields", Map.of())));
        assertNull(mapper.toWorkItem(Map.of("key", "SCRUM-11", "fields", Map.of())).description());
        assertNull(mapper.toComment(Map.of("author", Map.of(), "body", Map.of()), "SCRUM-11").body());
    }

    @Test
    @DisplayName("Given a Jira issue has no key when it is mapped then DevFlow keeps the business data but leaves the browse URL empty")
    void givenAJiraIssueHasNoKey_whenItIsMapped_thenDevFlowKeepsTheBusinessDataButLeavesTheBrowseUrlEmpty() {
        JiraPayloadMapper mapper = mapper("https://jira.example");

        var workItem = mapper.toWorkItem(Map.of(
            "fields", Map.of(
                "summary", "Improve onboarding",
                "description", "Clarify the business goal"
            )
        ));

        assertNull(workItem.url());
        assertEquals("Improve onboarding", workItem.title());
    }

    @Test
    @DisplayName("Given Jira uses ISO offsets and nested text blocks when the payload is mapped then DevFlow keeps only the meaningful text")
    void givenJiraUsesIsoOffsetsAndNestedTextBlocks_whenThePayloadIsMapped_thenDevFlowKeepsOnlyTheMeaningfulText() {
        JiraPayloadMapper mapper = mapper("https://jira.example");

        var workItem = mapper.toWorkItem(Map.of(
            "key", "SCRUM-12",
            "fields", Map.of(
                "description", Map.of(
                    "content", List.of(
                        "Direct business context",
                        Map.of("text", "Headline"),
                        Map.of("content", List.of(Map.of("text", "Nested detail")))
                    )
                ),
                "updated", "2026-04-09T10:15:30+02:00"
            )
        ));

        assertEquals("Direct business context\nHeadline\nNested detail", workItem.description());
        assertEquals(Instant.parse("2026-04-09T08:15:30Z"), workItem.updatedAt());
    }

    private JiraPayloadMapper mapper(String baseUrl) {
        JiraPayloadMapper mapper = new JiraPayloadMapper();
        ReflectionTestSupport.setField(mapper, "jiraConfig", new JiraConfig() {
            @Override
            public String baseUrl() {
                return baseUrl;
            }

            @Override
            public String userEmail() {
                return "devflow@example.com";
            }

            @Override
            public String apiToken() {
                return "token";
            }

            @Override
            public String epicKey() {
                return "SCRUM";
            }

            @Override
            public String todoStatus() {
                return "To Do";
            }

            @Override
            public String inProgressStatus() {
                return "In Progress";
            }

            @Override
            public String blockedStatus() {
                return "Blocked";
            }

            @Override
            public String reviewStatus() {
                return "To Review";
            }

            @Override
            public String validateStatus() {
                return "To Validate";
            }

            @Override
            public String doneStatus() {
                return "Done";
            }

            @Override
            public int pollIntervalMinutes() {
                return 1;
            }

            @Override
            public int pollMaxResults() {
                return 50;
            }
        });
        return mapper;
    }
}
