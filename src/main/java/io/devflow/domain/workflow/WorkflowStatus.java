package io.devflow.domain.workflow;

public enum WorkflowStatus {
    ACTIVE,
    WAITING_EXTERNAL_INPUT,
    WAITING_SYSTEM,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED
}
