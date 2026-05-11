package io.panda.application.codehost.publish;

import io.panda.application.codehost.port.CodeHostPort;
import io.panda.application.command.codehost.PublishCodeChangesCommand;
import io.panda.application.command.ticketing.CommentWorkItemCommand;
import io.panda.application.command.ticketing.TransitionWorkItemCommand;
import io.panda.application.ticketing.port.TicketingPort;
import io.panda.application.workflow.port.WorkflowHolder;
import io.panda.domain.model.agent.AgentEvent;
import io.panda.domain.model.codehost.CodeChangeRef;
import io.panda.domain.model.ticketing.WorkItem;
import io.panda.domain.model.ticketing.WorkItemTransitionTarget;
import io.panda.domain.model.workflow.Workflow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PublishCodeChangesUseCase {

    private static final Logger LOG = Logger.getLogger(PublishCodeChangesUseCase.class);

    @Inject
    CodeHostPort codeHostPort;

    @Inject
    TicketingPort ticketingPort;

    @Inject
    WorkflowHolder workflowHolder;

    public void execute(Workflow workflow, AgentEvent event, boolean skipTransitionToReview) {
        try {
            PublishCodeChangesCommand publishCommand = buildPublishCommand(workflow, event);
            LOG.infof(
                "Publish command: ticket=%s, repositories=%s, workItemTitle=%s",
                publishCommand.workItemKey(), publishCommand.repositories(), publishCommand.workItemTitle()
            );
            List<CodeChangeRef> codeChanges = codeHostPort.publish(publishCommand);
            LOG.infof("Published %d pull request(s) for ticket %s", codeChanges.size(), workflow.ticketKey());

            for (CodeChangeRef codeChange : codeChanges) {
                LOG.infof(
                    "Published PR: repository=%s, branch=%s, externalId=%s, url=%s",
                    codeChange.repository(), codeChange.sourceBranch(), codeChange.externalId(), codeChange.url()
                );
            }

            for (CodeChangeRef codeChange : codeChanges) {
                workflowHolder.addPublishedPR(codeChange);
            }

            if (!skipTransitionToReview) {
                transition(workflow, WorkItemTransitionTarget.TO_REVIEW, "IMPLEMENTATION_COMPLETED");
            }
            String reasonCode = skipTransitionToReview ? "REVIEW_ADDRESSED" : "IMPLEMENTATION_COMPLETED";
            postComment(workflow, event.buildTechnicalValidationComment(codeChanges), reasonCode);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to publish code changes for ticket %s", workflow.ticketKey());
            transition(workflow, WorkItemTransitionTarget.BLOCKED, "PUBLISH_FAILED");
            postComment(workflow, "PANDA marked this ticket as blocked due to a publish failure: " + e.getMessage(), "PUBLISH_FAILED");
        }
        workflowHolder.clear();
    }

    private PublishCodeChangesCommand buildPublishCommand(Workflow workflow, AgentEvent event) {
        WorkItem workItem = safeLoadWorkItem(workflow);
        String title = workItem != null && workItem.title() != null ? workItem.title() : workflow.ticketKey();
        List<String> repositories = event.extractRepoChangesRepositories();
        if (repositories.isEmpty()) {
            repositories = codeHostPort.configuredRepositories();
        }
        return new PublishCodeChangesCommand(
            workflow.workflowId(),
            workflow.ticketKey(),
            title,
            repositories,
            event.summary(),
            event.artifacts()
        );
    }

    private WorkItem safeLoadWorkItem(Workflow workflow) {
        try {
            return ticketingPort.loadWorkItem(workflow.ticketSystem(), workflow.ticketKey()).orElse(null);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Unable to load work item %s", workflow.ticketKey());
            return null;
        }
    }

    private void transition(Workflow workflow, WorkItemTransitionTarget target, String reasonCode) {
        try {
            ticketingPort.transition(new TransitionWorkItemCommand(
                workflow.ticketSystem(), workflow.ticketKey(), target, reasonCode));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to transition ticket %s to %s", workflow.ticketKey(), target);
        }
    }

    private void postComment(Workflow workflow, String comment, String reasonCode) {
        try {
            ticketingPort.comment(new CommentWorkItemCommand(
                workflow.ticketSystem(), workflow.ticketKey(), comment, reasonCode));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to post comment to ticket %s", workflow.ticketKey());
        }
    }
}
