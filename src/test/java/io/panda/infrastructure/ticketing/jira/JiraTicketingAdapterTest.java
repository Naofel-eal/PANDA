package io.panda.infrastructure.ticketing.jira;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.panda.application.command.ticketing.CommentWorkItemCommand;
import io.panda.application.command.ticketing.TransitionWorkItemCommand;
import io.panda.domain.exception.DomainException;
import io.panda.domain.model.ticketing.WorkItemTransitionTarget;
import io.panda.infrastructure.support.JsonSupport;
import io.panda.support.ReflectionTestSupport;
import io.panda.support.StubHttpServer;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JiraTicketingAdapterTest {

    @Test
    @DisplayName("Given a Jira ticket comment when PANDA publishes it then the request contains Jira auth and ADF content")
    void givenAJiraTicketComment_whenPANDAPublishesIt_thenTheRequestContainsJiraAuthAndAdfContent() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("POST", "/rest/api/3/issue/SCRUM-30/comment", 201, "{}");
            JiraTicketingAdapter adapter = adapter(server.baseUrl());

            adapter.comment(new CommentWorkItemCommand("jira", "SCRUM-30", "# Validation\n- confirm outcome", "VALIDATION"));

            var request = server.lastRequest();
            assertEquals("POST", request.method());
            assertEquals("/rest/api/3/issue/SCRUM-30/comment", request.path());
            assertTrue(request.header("Authorization").startsWith("Bearer "));
            assertTrue(request.body().contains("\"type\":\"doc\""));
            assertTrue(request.body().contains("\"heading\""));
            assertTrue(request.body().contains("\"bulletList\""));
        }
    }

    @Test
    @DisplayName("Given a non Jira ticket system when PANDA tries to comment then the request is rejected immediately")
    void givenANonJiraTicketSystem_whenPANDATriesToComment_thenTheRequestIsRejectedImmediately() {
        JiraTicketingAdapter adapter = adapter("http://127.0.0.1:9");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> adapter.comment(
            new CommentWorkItemCommand("github", "SCRUM-30", "Comment", "REASON")
        ));

        assertTrue(exception.getMessage().contains("Unsupported ticketing system"));
    }

    @Test
    @DisplayName("Given a non Jira ticket system when PANDA tries to transition then the request is rejected immediately")
    void givenANonJiraTicketSystem_whenPANDATriesToTransition_thenTheRequestIsRejectedImmediately() {
        JiraTicketingAdapter adapter = adapter("http://127.0.0.1:9");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> adapter.transition(
            new TransitionWorkItemCommand("github", "SCRUM-30", WorkItemTransitionTarget.IN_PROGRESS, "READY")
        ));

        assertTrue(exception.getMessage().contains("Unsupported ticketing system"));
    }

    @Test
    @DisplayName("Given a Jira comment API failure when PANDA publishes a comment then the ticketing adapter surfaces the business failure")
    void givenAJiraCommentApiFailure_whenPANDAPublishesAComment_thenTheTicketingAdapterSurfacesTheBusinessFailure() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("POST", "/rest/api/3/issue/SCRUM-31/comment", 400, "{\"error\":\"bad request\"}");
            JiraTicketingAdapter adapter = adapter(server.baseUrl());

            DomainException exception = assertThrows(DomainException.class, () -> adapter.comment(
                new CommentWorkItemCommand("jira", "SCRUM-31", "Comment", "REASON")
            ));

            assertTrue(exception.getMessage().contains("Jira comment failed"));
            assertTrue(exception.getMessage().contains("HTTP 400"));
        }
    }

    @Test
    @DisplayName("Given a ticket already in the target status when PANDA transitions it then Jira is not asked for a second transition")
    void givenATicketAlreadyInTheTargetStatus_whenPANDATransitionsIt_thenJiraIsNotAskedForASecondTransition() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueueExact("GET", "/rest/api/3/issue/SCRUM-32?fields=status", 200, """
                {"fields":{"status":{"name":"In Progress"}}}
                """);
            JiraTicketingAdapter adapter = adapter(server.baseUrl());

            adapter.transition(new TransitionWorkItemCommand("jira", "SCRUM-32", WorkItemTransitionTarget.IN_PROGRESS, "READY"));

            assertEquals(1, server.requests().size());
            assertEquals("GET", server.lastRequest().method());
        }
    }

    @Test
    @DisplayName("Given a ticket that can move forward when PANDA transitions it then Jira receives the matching transition id")
    void givenATicketThatCanMoveForward_whenPANDATransitionsIt_thenJiraReceivesTheMatchingTransitionId() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueueExact("GET", "/rest/api/3/issue/SCRUM-33?fields=status", 200, """
                {"fields":{"status":{"name":"Blocked"}}}
                """);
            server.enqueue("GET", "/rest/api/3/issue/SCRUM-33/transitions", 200, """
                {"transitions":[
                  {"id":"21","to":{"name":"In Progress"}},
                  {"id":"22","to":{"name":"To Review"}}
                ]}
                """);
            server.enqueue("POST", "/rest/api/3/issue/SCRUM-33/transitions", 204, "");
            JiraTicketingAdapter adapter = adapter(server.baseUrl());

            adapter.transition(new TransitionWorkItemCommand("jira", "SCRUM-33", WorkItemTransitionTarget.IN_PROGRESS, "READY"));

            assertEquals(3, server.requests().size());
            assertTrue(server.lastRequest().body().contains("\"id\":\"21\""));
        }
    }

    @Test
    @DisplayName("Given no Jira transition for the requested status when PANDA transitions a ticket then the adapter explains which targets are available")
    void givenNoJiraTransitionForTheRequestedStatus_whenPANDATransitionsATicket_thenTheAdapterExplainsWhichTargetsAreAvailable() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueueExact("GET", "/rest/api/3/issue/SCRUM-34?fields=status", 200, """
                {"fields":{"status":{"name":"Blocked"}}}
                """);
            server.enqueue("GET", "/rest/api/3/issue/SCRUM-34/transitions", 200, """
                {"transitions":[{"id":"22","to":{"name":"To Review"}}]}
                """);
            JiraTicketingAdapter adapter = adapter(server.baseUrl());

            DomainException exception = assertThrows(DomainException.class, () -> adapter.transition(
                new TransitionWorkItemCommand("jira", "SCRUM-34", WorkItemTransitionTarget.IN_PROGRESS, "READY")
            ));

            assertTrue(exception.getMessage().contains("No Jira transition"));
            assertTrue(exception.getMessage().contains("To Review"));
        }
    }

    @Test
    @DisplayName("Given a Jira issue lookup when PANDA loads the ticket then the work item is mapped from the Jira payload")
    void givenAJiraIssueLookup_whenPANDALoadsTheTicket_thenTheWorkItemIsMappedFromTheJiraPayload() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueueExact("GET", "/rest/api/3/issue/SCRUM-35?fields=summary,description,status,issuetype,labels,updated", 200, """
                {
                  "key":"SCRUM-35",
                  "fields":{
                    "summary":"Validate the workflow",
                    "description":"Collect business feedback",
                    "status":{"name":"To Do"},
                    "issuetype":{"name":"Story"},
                    "labels":["workflow"],
                    "updated":"2026-04-09T10:15:30Z"
                  }
                }
                """);
            JiraTicketingAdapter adapter = adapter(server.baseUrl());

            var workItem = adapter.loadWorkItem("jira", "SCRUM-35");

            assertTrue(workItem.isPresent());
            assertEquals("Validate the workflow", workItem.get().title());
            assertEquals("Story", workItem.get().type());
            assertEquals("workflow", workItem.get().labels().getFirst());
        }
    }

    @Test
    @DisplayName("Given a non Jira issue lookup when PANDA loads the ticket then the adapter returns an empty result")
    void givenANonJiraIssueLookup_whenPANDALoadsTheTicket_thenTheAdapterReturnsAnEmptyResult() {
        JiraTicketingAdapter adapter = adapter("http://127.0.0.1:9");

        Optional<?> workItem = adapter.loadWorkItem("github", "SCRUM-35");

        assertTrue(workItem.isEmpty());
    }

    @Test
    @DisplayName("Given a Jira issue lookup failure when PANDA loads the ticket then the adapter surfaces the Jira read error")
    void givenAJiraIssueLookupFailure_whenPANDALoadsTheTicket_thenTheAdapterSurfacesTheJiraReadError() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueueExact("GET", "/rest/api/3/issue/SCRUM-35?fields=summary,description,status,issuetype,labels,updated", 400, "{\"error\":\"bad request\"}");
            JiraTicketingAdapter adapter = adapter(server.baseUrl());

            DomainException exception = assertThrows(DomainException.class, () -> adapter.loadWorkItem("jira", "SCRUM-35"));

            assertTrue(exception.getMessage().contains("Unable to read Jira issue"));
        }
    }

    @Test
    @DisplayName("Given Jira comments across multiple pages when PANDA loads them then every comment is collected in order")
    void givenJiraCommentsAcrossMultiplePages_whenPANDALoadsThem_thenEveryCommentIsCollectedInOrder() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueueExact("GET", "/rest/api/3/issue/SCRUM-36/comment?startAt=0&maxResults=100", 200, """
                {
                  "startAt":0,
                  "maxResults":1,
                  "total":2,
                  "comments":[
                    {"id":"1","author":{"accountId":"acct-1"},"body":"First","created":"2026-04-09T10:00:00Z"}
                  ]
                }
                """);
            server.enqueueExact("GET", "/rest/api/3/issue/SCRUM-36/comment?startAt=1&maxResults=100", 200, """
                {
                  "startAt":1,
                  "maxResults":1,
                  "total":2,
                  "comments":[
                    {"id":"2","author":{"accountId":"acct-2"},"body":"Second","created":"2026-04-09T10:05:00Z"}
                  ]
                }
                """);
            JiraTicketingAdapter adapter = adapter(server.baseUrl());

            var comments = adapter.listComments("jira", "SCRUM-36");

            assertEquals(2, comments.size());
            assertEquals("1", comments.get(0).id());
            assertEquals("2", comments.get(1).id());
        }
    }

    @Test
    @DisplayName("Given a non Jira comment lookup when PANDA loads comments then the adapter returns no comments")
    void givenANonJiraCommentLookup_whenPANDALoadsComments_thenTheAdapterReturnsNoComments() {
        JiraTicketingAdapter adapter = adapter("http://127.0.0.1:9");

        assertFalse(adapter.listComments("github", "SCRUM-36").iterator().hasNext());
    }

    @Test
    @DisplayName("Given Jira comment pagination metadata as strings when PANDA loads comments then pagination still advances safely")
    void givenJiraCommentPaginationMetadataAsStrings_whenPANDALoadsComments_thenPaginationStillAdvancesSafely() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueueExact("GET", "/rest/api/3/issue/SCRUM-37/comment?startAt=0&maxResults=100", 200, """
                {
                  "startAt":"0",
                  "maxResults":"1",
                  "total":"1",
                  "comments":[
                    {"id":"1","author":{"accountId":"acct-1"},"body":"Only comment","created":"2026-04-09T10:00:00Z"}
                  ]
                }
                """);
            JiraTicketingAdapter adapter = adapter(server.baseUrl());

            var comments = adapter.listComments("jira", "SCRUM-37");

            assertEquals(1, comments.size());
            assertEquals("Only comment", comments.getFirst().body());
        }
    }

    @Test
    @DisplayName("Given Jira pagination metadata is malformed when PANDA loads comments then it falls back to safe defaults instead of looping forever")
    void givenJiraPaginationMetadataIsMalformed_whenPANDALoadsComments_thenItFallsBackToSafeDefaultsInsteadOfLoopingForever() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueueExact("GET", "/rest/api/3/issue/SCRUM-38/comment?startAt=0&maxResults=100", 200, """
                {
                  "startAt":"oops",
                  "maxResults":"oops",
                  "total":"1",
                  "comments":[
                    {"id":"1","author":{"accountId":"acct-1"},"body":"Only comment","created":"2026-04-09T10:00:00Z"}
                  ]
                }
                """);
            server.enqueueExact("GET", "/rest/api/3/issue/SCRUM-38/comment?startAt=100&maxResults=100", 200, """
                {"comments":[]}
                """);
            JiraTicketingAdapter adapter = adapter(server.baseUrl());

            var comments = adapter.listComments("jira", "SCRUM-38");

            assertEquals(1, comments.size());
            assertEquals("1", comments.getFirst().id());
        }
    }

    @Test
    @DisplayName("Given Jira confirms a done transition when PANDA closes a ticket then the matching Jira transition is posted")
    void givenJiraConfirmsADoneTransition_whenPANDAClosesATicket_thenTheMatchingJiraTransitionIsPosted() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueueExact("GET", "/rest/api/3/issue/SCRUM-39?fields=status", 200, """
                {"fields":{"status":{"name":"To Validate"}}}
                """);
            server.enqueue("GET", "/rest/api/3/issue/SCRUM-39/transitions", 200, """
                {"transitions":[{"id":"31","to":{"name":"Done"}}]}
                """);
            server.enqueue("POST", "/rest/api/3/issue/SCRUM-39/transitions", 204, "");
            JiraTicketingAdapter adapter = adapter(server.baseUrl());

            adapter.transition(new TransitionWorkItemCommand("jira", "SCRUM-39", WorkItemTransitionTarget.DONE, "VALIDATED"));

            assertTrue(server.lastRequest().body().contains("\"id\":\"31\""));
        }
    }

    @Test
    @DisplayName("Given Jira transport is interrupted when PANDA posts a comment then the adapter surfaces the interruption cleanly")
    void givenJiraTransportIsInterrupted_whenPANDAPostsAComment_thenTheAdapterSurfacesTheInterruptionCleanly() throws Exception {
        JiraTicketingAdapter adapter = adapter("http://jira.example");
        HttpClient client = mock(HttpClient.class);
        ReflectionTestSupport.setField(adapter, "client", client);
        doThrow(new InterruptedException("stop")).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        DomainException exception = assertThrows(DomainException.class, () -> adapter.comment(
            new CommentWorkItemCommand("jira", "SCRUM-40", "Comment", "REASON")
        ));

        assertTrue(exception.getMessage().contains("Interrupted while calling Jira"));
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
    }

    @Test
    @DisplayName("Given Jira comment loading fails on transport when PANDA reads comments then the adapter surfaces the read failure")
    void givenJiraCommentLoadingFailsOnTransport_whenPANDAReadsComments_thenTheAdapterSurfacesTheReadFailure() throws Exception {
        JiraTicketingAdapter adapter = adapter("http://jira.example");
        HttpClient client = mock(HttpClient.class);
        ReflectionTestSupport.setField(adapter, "client", client);
        doThrow(new IOException("network down")).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        DomainException exception = assertThrows(DomainException.class, () -> adapter.listComments("jira", "SCRUM-41"));

        assertTrue(exception.getMessage().contains("Unable to read Jira comments"));
    }

    @Test
    @DisplayName("Given Jira becomes unavailable during a transition when PANDA moves a ticket then the adapter surfaces the transport failure")
    void givenJiraBecomesUnavailableDuringATransition_whenPANDAMovesATicket_thenTheAdapterSurfacesTheTransportFailure() throws Exception {
        JiraTicketingAdapter adapter = adapter("http://jira.example");
        HttpClient client = mock(HttpClient.class);
        ReflectionTestSupport.setField(adapter, "client", client);
        HttpResponse<String> currentStatus = response(200, "{\"fields\":{\"status\":{\"name\":\"Blocked\"}}}");
        HttpResponse<String> transitions = response(200, "{\"transitions\":[{\"id\":\"21\",\"to\":{\"name\":\"In Progress\"}}]}");

        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(currentStatus)
            .thenReturn(transitions)
            .thenThrow(new IOException("network down"));

        DomainException exception = assertThrows(DomainException.class, () -> adapter.transition(
            new TransitionWorkItemCommand("jira", "SCRUM-42", WorkItemTransitionTarget.IN_PROGRESS, "READY")
        ));

        assertTrue(exception.getMessage().contains("Jira transition failed"));
    }

    private JiraTicketingAdapter adapter(String baseUrl) {
        JiraTicketingAdapter adapter = new JiraTicketingAdapter();
        ReflectionTestSupport.setField(adapter, "config", new JiraConfig() {
            @Override
            public String baseUrl() {
                return baseUrl;
            }

            @Override
            public String apiToken() {
                return "jira-token";
            }

            @Override
            public String projectKey() {
                return "SCRUM";
            }

            @Override
            public Optional<String> serviceAccountId() {
                return Optional.empty();
            }

            @Override
            public String backlogStatus() {
                return "Backlog";
            }

            @Override
            public String sprintField() {
                return "customfield_10020";
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
                return 100;
            }
        });
        JsonSupport jsonSupport = new JsonSupport();
        ReflectionTestSupport.setField(jsonSupport, "objectMapper", new ObjectMapper());
        ReflectionTestSupport.setField(adapter, "jsonCodec", jsonSupport);
        ReflectionTestSupport.setField(adapter, "jiraPayloadMapper", payloadMapper(baseUrl));
        return adapter;
    }

    private JiraPayloadMapper payloadMapper(String baseUrl) {
        JiraPayloadMapper mapper = new JiraPayloadMapper();
        ReflectionTestSupport.setField(mapper, "jiraConfig", new JiraConfig() {
            @Override public String baseUrl() { return baseUrl; }
            @Override public String apiToken() { return "jira-token"; }
            @Override public String projectKey() { return "SCRUM"; }
            @Override public Optional<String> serviceAccountId() { return Optional.empty(); }
            @Override public String backlogStatus() { return "Backlog"; }
            @Override public String sprintField() { return "customfield_10020"; }
            @Override public String todoStatus() { return "To Do"; }
            @Override public String inProgressStatus() { return "In Progress"; }
            @Override public String blockedStatus() { return "Blocked"; }
            @Override public String reviewStatus() { return "To Review"; }
            @Override public String validateStatus() { return "To Validate"; }
            @Override public String doneStatus() { return "Done"; }
            @Override public int pollIntervalMinutes() { return 1; }
            @Override public int pollMaxResults() { return 100; }
        });
        return mapper;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int statusCode, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        return response;
    }
}
