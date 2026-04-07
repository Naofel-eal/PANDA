package io.devflow.domain.model.workflow;

public enum ResumeTrigger {
    WORK_ITEM_COMMENT_RECEIVED,
    CODE_CHANGE_REVIEW_COMMENT_RECEIVED,
    BUSINESS_VALIDATION_REPORTED,
    MANUAL_RESUME_REQUESTED
}
