package io.devflow.application.port.persistence;

import io.devflow.domain.messaging.OutboxCommand;
import java.util.Optional;

public interface OutboxCommandStore {

    Optional<OutboxCommand> findNextPending();

    OutboxCommand save(OutboxCommand outboxCommand);
}
