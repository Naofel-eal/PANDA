package io.panda.domain.exception;

public class WorkflowNotFoundException extends DomainException {

    public WorkflowNotFoundException(String message) {
        super(message);
    }
}
