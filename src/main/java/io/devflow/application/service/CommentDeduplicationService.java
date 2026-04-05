package io.devflow.application.service;

import io.devflow.application.port.persistence.ExternalCommentStore;
import io.devflow.application.port.support.HashGenerator;
import io.devflow.domain.ticketing.CommentFreshness;
import io.devflow.domain.ticketing.ExternalComment;
import io.devflow.domain.ticketing.IncomingComment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class CommentDeduplicationService {

    @Inject
    ExternalCommentStore externalCommentStore;

    @Inject
    HashGenerator hashGenerator;

    public CommentFreshness register(String sourceSystem, IncomingComment comment) {
        String payloadHash = hashGenerator.sha256(sourceSystem + "|" + comment.id() + "|" +
            normalize(comment.body()) + "|" + normalize(comment.updatedAt()));

        Instant now = Instant.now();
        return externalCommentStore.findByComment(sourceSystem, comment.id())
            .map(existing -> updateExisting(existing, comment, payloadHash, now))
            .orElseGet(() -> createNew(sourceSystem, comment, payloadHash, now));
    }

    private CommentFreshness updateExisting(
        ExternalComment existing,
        IncomingComment comment,
        String payloadHash,
        Instant now
    ) {
        if (payloadHash.equals(existing.payloadHash())) {
            ExternalComment duplicateSeen = existing.markDuplicateSeen(now);
            externalCommentStore.save(duplicateSeen);
            return CommentFreshness.DUPLICATE;
        }

        ExternalComment updated = existing.update(comment.authorId(), payloadHash, comment.updatedAt(), now);
        externalCommentStore.save(updated);
        return CommentFreshness.UPDATED;
    }

    private CommentFreshness createNew(
        String sourceSystem,
        IncomingComment comment,
        String payloadHash,
        Instant now
    ) {
        ExternalComment entity = ExternalComment.register(UUID.randomUUID(), sourceSystem, comment, payloadHash, now);
        externalCommentStore.save(entity);
        return CommentFreshness.NEW;
    }

    private String normalize(Object value) {
        return value == null ? "" : value.toString();
    }
}
