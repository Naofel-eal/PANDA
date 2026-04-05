package io.devflow.domain.messaging;

public enum OutboxCommandStatus {
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED,
    CANCELLED
}
