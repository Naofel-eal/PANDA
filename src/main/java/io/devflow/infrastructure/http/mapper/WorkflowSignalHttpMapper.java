package io.devflow.infrastructure.http.mapper;

import io.devflow.application.command.workflow.WorkflowSignalCommand;
import io.devflow.domain.validation.BusinessValidationReport;
import io.devflow.domain.codehost.CodeChangeRef;
import io.devflow.domain.validation.DeploymentInfo;
import io.devflow.domain.ticketing.IncomingComment;
import io.devflow.domain.ticketing.WorkItem;
import io.devflow.infrastructure.http.request.WorkflowSignalHttpRequest;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class WorkflowSignalHttpMapper {

    public WorkflowSignalCommand toCommand(WorkflowSignalHttpRequest request) {
        return new WorkflowSignalCommand(
            request.type(),
            request.sourceSystem(),
            request.sourceEventId(),
            request.workflowId(),
            request.occurredAt(),
            request.workItem() == null ? null : new WorkItem(
                request.workItem().key(),
                request.workItem().type(),
                request.workItem().title(),
                request.workItem().description(),
                request.workItem().status(),
                request.workItem().url(),
                request.workItem().labels(),
                request.workItem().repositories()
            ),
            request.comment() == null ? null : new IncomingComment(
                request.comment().id(),
                request.comment().parentType(),
                request.comment().parentId(),
                request.comment().authorId(),
                request.comment().body(),
                request.comment().createdAt(),
                request.comment().updatedAt()
            ),
            request.codeChange() == null ? null : new CodeChangeRef(
                request.codeChange().system(),
                request.codeChange().externalId(),
                request.codeChange().repository(),
                request.codeChange().url(),
                request.codeChange().sourceBranch(),
                request.codeChange().targetBranch()
            ),
            request.deployment() == null ? null : new DeploymentInfo(
                request.deployment().environment(),
                request.deployment().branch(),
                request.deployment().url()
            ),
            request.businessValidation() == null ? null : new BusinessValidationReport(
                request.businessValidation().result(),
                request.businessValidation().summary()
            )
        );
    }
}
