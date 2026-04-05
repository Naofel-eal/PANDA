package io.devflow.domain.agent;

public enum AgentRunStatus {
    PENDING,
    STARTING,
    RUNNING,
    WAITING_INPUT,
    COMPLETED,
    FAILED,
    CANCELLED
}
