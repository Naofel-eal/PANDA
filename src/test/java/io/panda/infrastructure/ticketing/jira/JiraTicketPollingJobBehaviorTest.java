package io.panda.infrastructure.ticketing.jira;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.panda.application.ticketing.port.TicketingPort;
import io.panda.application.workflow.cancel.CancelStaleRunUseCase;
import io.panda.application.workflow.port.WorkflowHolder;
import io.panda.application.workflow.resume.ResumeWorkflowUseCase;
import io.panda.application.workflow.start.StartInfoCollectionUseCase;
import io.panda.domain.model.codehost.CodeChangeRef;
import io.panda.domain.model.ticketing.IncomingComment;
import io.panda.domain.model.ticketing.WorkItem;
import io.panda.domain.model.ticketing.WorkItemTransitionTarget;
import io.panda.domain.model.workflow.Workflow;
import io.panda.domain.model.workflow.WorkflowPhase;
import io.panda.infrastructure.agent.opencode.OpenCodeRuntimeConfig;
import io.panda.support.ReflectionTestSupport;
import io.panda.support.StubHttpServer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JiraTicketPollingJobBehaviorTest {

    @Test
    @DisplayName("Given an incomplete to do ticket when Jira is polled then PANDA blocks it and explains which business information is missing")
    void givenAnIncompleteToDoTicket_whenJiraIsPolled_thenPANDABlocksItAndExplainsWhichBusinessInformationIsMissing() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/rest/api/3/myself", 200, "{\"accountId\":\"panda-account\"}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, """
                {
                  "issues":[
                    {
                      "key":"SCRUM-70",
                      "fields":{
                        "summary":"Clarify rollout",
                        "description":"   ",
                        "status":{"name":"To Do"},
                        "issuetype":{"name":"Story"},
                        "labels":[],
                        "updated":"2026-04-09T10:00:00Z"
                      }
                    }
                  ],
                  "isLast":true
                }
                """);
            server.enqueue("POST", "/rest/api/3/search/jql", 200, "{\"issues\":[],\"isLast\":true}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, "{\"issues\":[],\"isLast\":true}");

            RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
            RecordingStartInfoCollectionUseCase startUseCase = new RecordingStartInfoCollectionUseCase();
            RecordingResumeWorkflowUseCase resumeUseCase = new RecordingResumeWorkflowUseCase();
            RecordingCancelStaleRunUseCase cancelUseCase = new RecordingCancelStaleRunUseCase();
            JiraTicketPollingJob job = job(server.baseUrl(), ticketingPort, new IdleWorkflowHolder(), startUseCase, resumeUseCase, cancelUseCase);

            job.pollEpicTickets();

            assertEquals(20, cancelUseCase.maxDurationMinutes);
            assertEquals(WorkItemTransitionTarget.BLOCKED, ticketingPort.transitions.getFirst().target());
            assertTrue(ticketingPort.comments.getFirst().comment().contains("missing: description"));
            assertNull(startUseCase.workItem);
            assertNull(resumeUseCase.workItem);
        }
    }

    @Test
    @DisplayName("Given an eligible to do ticket when Jira is polled then PANDA starts information collection with the ticket comments")
    void givenAnEligibleToDoTicket_whenJiraIsPolled_thenPANDAStartsInformationCollectionWithTheTicketComments() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/rest/api/3/myself", 200, "{\"accountId\":\"panda-account\"}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, """
                {
                  "issues":[
                    {
                      "key":"SCRUM-71",
                      "fields":{
                        "summary":"Launch the new workflow",
                        "description":"Implement the approved change",
                        "status":{"name":"To Do"},
                        "issuetype":{"name":"Story"},
                        "labels":["workflow"],
                        "updated":"2026-04-09T10:00:00Z"
                      }
                    }
                  ],
                  "isLast":true
                }
                """);

            RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
            ticketingPort.commentsByKey.put("SCRUM-71", List.of(comment("user-1", "Business clarification", "2026-04-09T10:01:00Z")));
            RecordingStartInfoCollectionUseCase startUseCase = new RecordingStartInfoCollectionUseCase();
            JiraTicketPollingJob job = job(server.baseUrl(), ticketingPort, new IdleWorkflowHolder(), startUseCase, new RecordingResumeWorkflowUseCase(), new RecordingCancelStaleRunUseCase());

            job.pollEpicTickets();

            assertEquals("SCRUM-71", startUseCase.workItem.key());
            assertEquals(1, startUseCase.comments.size());
        }
    }

    @Test
    @DisplayName("Given a blocked ticket with fresh business feedback when Jira is polled then PANDA resumes information collection")
    void givenABlockedTicketWithFreshBusinessFeedback_whenJiraIsPolled_thenPANDAResumesInformationCollection() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/rest/api/3/myself", 200, "{\"accountId\":\"panda-account\"}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, "{\"issues\":[],\"isLast\":true}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, """
                {
                  "issues":[
                    {
                      "key":"SCRUM-72",
                      "fields":{
                        "summary":"Clarify rollout",
                        "description":"Add business details",
                        "status":{"name":"Blocked"},
                        "issuetype":{"name":"Story"},
                        "labels":[],
                        "updated":"2026-04-09T10:06:00Z"
                      }
                    }
                  ],
                  "isLast":true
                }
                """);

            RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
            ticketingPort.commentsByKey.put("SCRUM-72", List.of(
                comment("panda-account", "PANDA marked this ticket as blocked because it is missing: description", "2026-04-09T10:00:00Z"),
                comment("user-1", "Here is the missing description", "2026-04-09T10:05:00Z")
            ));
            RecordingResumeWorkflowUseCase resumeUseCase = new RecordingResumeWorkflowUseCase();
            JiraTicketPollingJob job = job(server.baseUrl(), ticketingPort, new IdleWorkflowHolder(), new RecordingStartInfoCollectionUseCase(), resumeUseCase, new RecordingCancelStaleRunUseCase());

            job.pollEpicTickets();

            assertEquals("SCRUM-72", resumeUseCase.workItem.key());
            assertEquals(WorkflowPhase.INFORMATION_COLLECTION, resumeUseCase.phase);
        }
    }

    @Test
    @DisplayName("Given a validation ticket updated after the last PANDA comment when Jira is polled then PANDA resumes business validation")
    void givenAValidationTicketUpdatedAfterTheLastPANDAComment_whenJiraIsPolled_thenPANDAResumesBusinessValidation() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/rest/api/3/myself", 200, "{\"accountId\":\"panda-account\"}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, "{\"issues\":[],\"isLast\":true}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, "{\"issues\":[],\"isLast\":true}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, """
                {
                  "issues":[
                    {
                      "key":"SCRUM-73",
                      "fields":{
                        "summary":"Validate release",
                        "description":"Business validation pending",
                        "status":{"name":"To Validate"},
                        "issuetype":{"name":"Story"},
                        "labels":[],
                        "updated":"2026-04-09T10:10:00Z"
                      }
                    }
                  ],
                  "isLast":true
                }
                """);

            RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
            ticketingPort.commentsByKey.put("SCRUM-73", List.of(
                comment("panda-account", "Pull request merged and ready for validation.", "2026-04-09T10:00:00Z"),
                comment("user-1", "I tested it and found an issue with the layout", "2026-04-09T10:05:00Z")
            ));
            RecordingResumeWorkflowUseCase resumeUseCase = new RecordingResumeWorkflowUseCase();
            JiraTicketPollingJob job = job(server.baseUrl(), ticketingPort, new IdleWorkflowHolder(), new RecordingStartInfoCollectionUseCase(), resumeUseCase, new RecordingCancelStaleRunUseCase());

            job.pollEpicTickets();

            assertEquals("SCRUM-73", resumeUseCase.workItem.key());
            assertEquals(WorkflowPhase.INFORMATION_COLLECTION, resumeUseCase.phase);
        }
    }

    @Test
    @DisplayName("Given Jira polling is not configured when the job runs then PANDA exits without querying Jira")
    void givenJiraPollingIsNotConfigured_whenTheJobRuns_thenPANDAExitsWithoutQueryingJira() {
        JiraTicketPollingJob job = new JiraTicketPollingJob();
        ReflectionTestSupport.setField(job, "config", new JiraConfig() {
            @Override public String baseUrl() { return ""; }
            @Override public String apiToken() { return ""; }
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
        RecordingCancelStaleRunUseCase cancelUseCase = new RecordingCancelStaleRunUseCase();
        ReflectionTestSupport.setField(job, "cancelStaleRunUseCase", cancelUseCase);
        ReflectionTestSupport.setField(job, "workflowHolder", new IdleWorkflowHolder());

        job.pollEpicTickets();

        assertEquals(0, cancelUseCase.maxDurationMinutes);
    }

    @Test
    @DisplayName("Given an ineligible ticket already blocked recently by PANDA when Jira is polled then PANDA does not post the same explanation twice")
    void givenAnIneligibleTicketAlreadyBlockedRecentlyByPANDA_whenJiraIsPolled_thenPANDADoesNotPostTheSameExplanationTwice() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/rest/api/3/myself", 200, "{\"accountId\":\"panda-account\"}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, """
                {
                  "issues":[
                    {
                      "key":"SCRUM-74",
                      "fields":{
                        "summary":"Clarify rollout",
                        "description":"   ",
                        "status":{"name":"To Do"},
                        "issuetype":{"name":"Story"},
                        "labels":[],
                        "updated":"2026-04-09T10:00:30Z"
                      }
                    }
                  ],
                  "isLast":true
                }
                """);
            server.enqueue("POST", "/rest/api/3/search/jql", 200, "{\"issues\":[],\"isLast\":true}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, "{\"issues\":[],\"isLast\":true}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, "{\"issues\":[],\"isLast\":true}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, "{\"issues\":[],\"isLast\":true}");

            RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
            ticketingPort.commentsByKey.put("SCRUM-74", List.of(
                comment("panda-account", "PANDA marked this ticket as blocked because it is missing: description", "2026-04-09T10:00:00Z")
            ));
            JiraTicketPollingJob job = job(server.baseUrl(), ticketingPort, new IdleWorkflowHolder(), new RecordingStartInfoCollectionUseCase(), new RecordingResumeWorkflowUseCase(), new RecordingCancelStaleRunUseCase());

            job.pollEpicTickets();

            assertTrue(ticketingPort.transitions.isEmpty());
            assertTrue(ticketingPort.comments.isEmpty());
        }
    }

    @Test
    @DisplayName("Given Jira cannot load comments for an incomplete ticket when Jira is polled then PANDA still blocks the ticket with a fresh explanation")
    void givenJiraCannotLoadCommentsForAnIncompleteTicket_whenJiraIsPolled_thenPANDAStillBlocksTheTicketWithAFreshExplanation() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/rest/api/3/myself", 200, "{\"accountId\":\"panda-account\"}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, """
                {
                  "issues":[
                    {
                      "key":"SCRUM-75",
                      "fields":{
                        "summary":"Clarify rollout",
                        "description":"   ",
                        "status":{"name":"To Do"},
                        "issuetype":{"name":"Story"},
                        "labels":[],
                        "updated":"2026-04-09T10:00:00Z"
                      }
                    }
                  ],
                  "isLast":true
                }
                """);
            server.enqueue("POST", "/rest/api/3/search/jql", 200, "{\"issues\":[],\"isLast\":true}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, "{\"issues\":[],\"isLast\":true}");

            RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
            ticketingPort.failListComments = true;
            JiraTicketPollingJob job = job(server.baseUrl(), ticketingPort, new IdleWorkflowHolder(), new RecordingStartInfoCollectionUseCase(), new RecordingResumeWorkflowUseCase(), new RecordingCancelStaleRunUseCase());

            job.pollEpicTickets();

            assertEquals(WorkItemTransitionTarget.BLOCKED, ticketingPort.transitions.getFirst().target());
            assertTrue(ticketingPort.comments.getFirst().comment().contains("missing: description"));
        }
    }

    @Test
    @DisplayName("Given Jira side effects fail while blocking an incomplete ticket when Jira is polled then PANDA keeps polling instead of crashing")
    void givenJiraSideEffectsFailWhileBlockingAnIncompleteTicket_whenJiraIsPolled_thenPANDAKeepsPollingInsteadOfCrashing() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/rest/api/3/myself", 200, "{\"accountId\":\"panda-account\"}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, """
                {
                  "issues":[
                    {
                      "key":"SCRUM-76",
                      "fields":{
                        "summary":"Clarify rollout",
                        "description":"   ",
                        "status":{"name":"To Do"},
                        "issuetype":{"name":"Story"},
                        "labels":[],
                        "updated":"2026-04-09T10:00:00Z"
                      }
                    }
                  ],
                  "isLast":true
                }
                """);
            server.enqueue("POST", "/rest/api/3/search/jql", 200, "{\"issues\":[],\"isLast\":true}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, "{\"issues\":[],\"isLast\":true}");

            RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
            ticketingPort.failTransition = true;
            ticketingPort.failComment = true;
            JiraTicketPollingJob job = job(server.baseUrl(), ticketingPort, new IdleWorkflowHolder(), new RecordingStartInfoCollectionUseCase(), new RecordingResumeWorkflowUseCase(), new RecordingCancelStaleRunUseCase());

            job.pollEpicTickets();

            assertTrue(ticketingPort.transitions.isEmpty());
            assertTrue(ticketingPort.comments.isEmpty());
        }
    }

    @Test
    @DisplayName("Given Jira account lookup is unavailable when fresh user feedback arrives then PANDA still resumes from comment markers alone")
    void givenJiraAccountLookupIsUnavailable_whenFreshUserFeedbackArrives_thenPANDAStillResumesFromCommentMarkersAlone() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/rest/api/3/myself", 500, "{\"error\":\"forbidden\"}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, "{\"issues\":[],\"isLast\":true}");
            server.enqueue("POST", "/rest/api/3/search/jql", 200, """
                {
                  "issues":[
                    {
                      "key":"SCRUM-77",
                      "fields":{
                        "summary":"Clarify rollout",
                        "description":"Add business details",
                        "status":{"name":"Blocked"},
                        "issuetype":{"name":"Story"},
                        "labels":[],
                        "updated":"2026-04-09T10:06:00Z"
                      }
                    }
                  ],
                  "isLast":true
                }
                """);

            RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
            ticketingPort.commentsByKey.put("SCRUM-77", List.of(
                comment("shared-account", "PANDA marked this ticket as blocked because it is missing: description", "2026-04-09T10:00:00Z"),
                comment("user-1", "Here is the missing description", "2026-04-09T10:05:00Z")
            ));
            RecordingResumeWorkflowUseCase resumeUseCase = new RecordingResumeWorkflowUseCase();
            JiraTicketPollingJob job = job(server.baseUrl(), ticketingPort, new IdleWorkflowHolder(), new RecordingStartInfoCollectionUseCase(), resumeUseCase, new RecordingCancelStaleRunUseCase());

            job.pollEpicTickets();

            assertEquals("SCRUM-77", resumeUseCase.workItem.key());
            assertEquals(WorkflowPhase.INFORMATION_COLLECTION, resumeUseCase.phase);
        }
    }

    @Test
    @DisplayName("Given Jira search fails when the job runs then PANDA surfaces the polling failure instead of hiding it")
    void givenJiraSearchFails_whenTheJobRuns_thenPANDASurfacesThePollingFailureInsteadOfHidingIt() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/rest/api/3/myself", 200, "{\"accountId\":\"panda-account\"}");
            server.enqueue("POST", "/rest/api/3/search/jql", 500, "{\"error\":\"jira down\"}");

            JiraTicketPollingJob job = job(server.baseUrl(), new RecordingTicketingPort(), new IdleWorkflowHolder(), new RecordingStartInfoCollectionUseCase(), new RecordingResumeWorkflowUseCase(), new RecordingCancelStaleRunUseCase());

            IllegalStateException exception = assertThrows(IllegalStateException.class, job::pollEpicTickets);

            assertTrue(exception.getMessage().contains("Jira polling failed"));
        }
    }

    @Test
    @DisplayName("Given an active workflow when Jira is polled then PANDA only checks stale runs and skips ticket scanning")
    void givenAnActiveWorkflow_whenJiraIsPolled_thenPANDAOnlyChecksStaleRunsAndSkipsTicketScanning() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/rest/api/3/myself", 200, "{\"accountId\":\"panda-account\"}");

            IdleWorkflowHolder workflowHolder = new IdleWorkflowHolder();
            workflowHolder.busy = true;
            RecordingCancelStaleRunUseCase cancelUseCase = new RecordingCancelStaleRunUseCase();
            JiraTicketPollingJob job = job(server.baseUrl(), new RecordingTicketingPort(), workflowHolder, new RecordingStartInfoCollectionUseCase(), new RecordingResumeWorkflowUseCase(), cancelUseCase);

            job.pollEpicTickets();

            assertEquals(20, cancelUseCase.maxDurationMinutes);
        }
    }

    private JiraTicketPollingJob job(
        String baseUrl,
        RecordingTicketingPort ticketingPort,
        IdleWorkflowHolder workflowHolder,
        RecordingStartInfoCollectionUseCase startUseCase,
        RecordingResumeWorkflowUseCase resumeUseCase,
        RecordingCancelStaleRunUseCase cancelUseCase
    ) {
        JiraTicketPollingJob job = new JiraTicketPollingJob();
        JiraConfig config = new JiraConfig() {
            @Override public String baseUrl() { return baseUrl; }
            @Override public String apiToken() { return "jira-token"; }
            @Override public String projectKey() { return "SCRUM"; }
            @Override public Optional<String> serviceAccountId() { return Optional.of("panda-account"); }
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
        };
        JiraPayloadMapper payloadMapper = new JiraPayloadMapper();
        ReflectionTestSupport.setField(payloadMapper, "jiraConfig", config);
        ReflectionTestSupport.setField(job, "config", config);
        ReflectionTestSupport.setField(job, "jiraPayloadMapper", payloadMapper);
        ReflectionTestSupport.setField(job, "ticketingPort", ticketingPort);
        ReflectionTestSupport.setField(job, "workflowHolder", workflowHolder);
        startUseCase.workflowHolder = workflowHolder;
        resumeUseCase.workflowHolder = workflowHolder;
        ReflectionTestSupport.setField(job, "startInfoCollectionUseCase", startUseCase);
        ReflectionTestSupport.setField(job, "resumeWorkflowUseCase", resumeUseCase);
        ReflectionTestSupport.setField(job, "cancelStaleRunUseCase", cancelUseCase);
        ReflectionTestSupport.setField(job, "agentRuntimeConfig", new OpenCodeRuntimeConfig() {
            @Override public String baseUrl() { return "http://agent"; }
            @Override public int hardTimeoutMinutes() { return 15; }
            @Override public int staleTimeoutBufferMinutes() { return 5; }
        });
        ReflectionTestSupport.setField(job, "objectMapper", new ObjectMapper());
        return job;
    }

    private IncomingComment comment(String authorId, String body, String createdAt) {
        return new IncomingComment(
            "comment-" + createdAt,
            "WORK_ITEM",
            "SCRUM",
            authorId,
            body,
            Instant.parse(createdAt),
            null
        );
    }

    private static final class RecordingTicketingPort implements TicketingPort {
        private final List<io.panda.application.command.ticketing.CommentWorkItemCommand> comments = new ArrayList<>();
        private final List<io.panda.application.command.ticketing.TransitionWorkItemCommand> transitions = new ArrayList<>();
        private final Map<String, List<IncomingComment>> commentsByKey = new HashMap<>();
        private boolean failListComments;
        private boolean failComment;
        private boolean failTransition;

        @Override
        public void comment(io.panda.application.command.ticketing.CommentWorkItemCommand command) {
            if (failComment) {
                throw new IllegalStateException("jira comment unavailable");
            }
            comments.add(command);
        }

        @Override
        public void transition(io.panda.application.command.ticketing.TransitionWorkItemCommand command) {
            if (failTransition) {
                throw new IllegalStateException("jira transition unavailable");
            }
            transitions.add(command);
        }

        @Override
        public Optional<WorkItem> loadWorkItem(String workItemSystem, String workItemKey) {
            return Optional.empty();
        }

        @Override
        public List<IncomingComment> listComments(String workItemSystem, String workItemKey) {
            if (failListComments) {
                throw new IllegalStateException("jira comments unavailable");
            }
            return commentsByKey.getOrDefault(workItemKey, List.of());
        }
    }

    private static final class IdleWorkflowHolder implements WorkflowHolder {
        private boolean busy;

        @Override public boolean isBusy() { return busy; }
        @Override public boolean isStale(Duration maxDuration) { return false; }
        @Override public Workflow current() { return null; }
        @Override public Workflow start(Workflow workflow) { busy = true; return workflow; }
        @Override public void clear() { busy = false; }
        @Override public void clearIfMatches(UUID agentRunId) { busy = false; }
        @Override public Workflow replacePhase(WorkflowPhase newPhase, UUID newAgentRunId) { throw new UnsupportedOperationException(); }
        @Override public void addPublishedPR(CodeChangeRef codeChange) { }
    }

    private static final class RecordingStartInfoCollectionUseCase extends StartInfoCollectionUseCase {
        private WorkItem workItem;
        private List<IncomingComment> comments = List.of();
        private IdleWorkflowHolder workflowHolder;

        @Override
        public void execute(String ticketSystem, WorkItem workItem, List<IncomingComment> comments) {
            this.workItem = workItem;
            this.comments = comments;
            workflowHolder.busy = true;
        }
    }

    private static final class RecordingResumeWorkflowUseCase extends ResumeWorkflowUseCase {
        private WorkItem workItem;
        private WorkflowPhase phase;
        private IdleWorkflowHolder workflowHolder;

        @Override
        public void execute(String ticketSystem, WorkItem workItem, List<IncomingComment> comments, WorkflowPhase phase) {
            this.workItem = workItem;
            this.phase = phase;
            workflowHolder.busy = true;
        }
    }

    private static final class RecordingCancelStaleRunUseCase extends CancelStaleRunUseCase {
        private long maxDurationMinutes;

        @Override
        public void execute(long maxDurationMinutes) {
            this.maxDurationMinutes = maxDurationMinutes;
        }
    }
}
