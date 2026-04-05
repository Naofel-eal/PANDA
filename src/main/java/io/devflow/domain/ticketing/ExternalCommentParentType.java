package io.devflow.domain.ticketing;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public enum ExternalCommentParentType {
    WORK_ITEM("WORK_ITEM"),
    CODE_CHANGE("CODE_CHANGE");

    private final String id;

    ExternalCommentParentType(String id) {
        this.id = id;
    }

    public boolean matches(String value) {
        return value != null && name().equalsIgnoreCase(value);
    }
}
