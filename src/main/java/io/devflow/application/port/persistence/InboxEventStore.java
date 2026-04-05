package io.devflow.application.port.persistence;

import io.devflow.domain.messaging.InboxEvent;
import java.util.Optional;

public interface InboxEventStore {

    Optional<InboxEvent> findBySourceEvent(String sourceSystem, String sourceEventType, String sourceEventId);

    InboxEvent save(InboxEvent inboxEvent);
}
