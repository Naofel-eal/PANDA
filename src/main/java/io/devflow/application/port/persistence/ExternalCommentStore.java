package io.devflow.application.port.persistence;

import io.devflow.domain.ticketing.ExternalComment;
import java.util.Optional;

public interface ExternalCommentStore {

    Optional<ExternalComment> findByComment(String sourceSystem, String commentId);

    ExternalComment save(ExternalComment comment);
}
