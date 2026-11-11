package org.zettelkasten.application.port;

import java.util.List;
import java.util.UUID;
import org.zettelkasten.domain.DomainEvent;

/** Read access to the append-only event journal. Mutations are emitted with repository writes. */
public interface DomainEventRepository {
  /** Append one event inside the active unit-of-work transaction. */
  void append(String eventType, String aggregateType, UUID aggregateId, String payloadJson);

  List<DomainEvent> findAfter(long sequenceExclusive, int limit);
}
