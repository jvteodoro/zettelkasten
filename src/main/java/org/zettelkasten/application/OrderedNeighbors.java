package org.zettelkasten.application;

import java.util.Optional;
import org.zettelkasten.domain.MainCard;

/** Physical predecessor/successor in filing order; it carries no semantic link meaning. */
public record OrderedNeighbors(Optional<MainCard> predecessor, Optional<MainCard> successor) {
  public OrderedNeighbors {
    predecessor = predecessor == null ? Optional.empty() : predecessor;
    successor = successor == null ? Optional.empty() : successor;
  }
}
