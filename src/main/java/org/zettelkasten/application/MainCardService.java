package org.zettelkasten.application;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.zettelkasten.application.port.UnitOfWork;
import org.zettelkasten.domain.BodyRun;
import org.zettelkasten.domain.CardAnnotation;
import org.zettelkasten.domain.MainCard;
import org.zettelkasten.domain.NumericAlphaAddress;
import org.zettelkasten.domain.PlacementSnapshot;
import org.zettelkasten.domain.StaleDraftRevisionException;

/** Application commands and exact/ordered queries for the Desk and Main box. */
public final class MainCardService {
  private final UnitOfWork unitOfWork;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public MainCardService(UnitOfWork unitOfWork, Clock clock, Supplier<UUID> ids) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  public MainCard createDraft(List<BodyRun> initialBody) {
    MainCard draft = MainCard.createDraft(ids.get(), clock.instant(), initialBody);
    unitOfWork.transact(
        tx -> {
          tx.mainCards().insertDraft(draft);
          return null;
        });
    return draft;
  }

  public MainCard editDraft(UUID cardId, long expectedRevision, List<BodyRun> newBody) {
    Objects.requireNonNull(cardId, "cardId");
    return unitOfWork.transact(
        tx -> {
          MainCard current = requireCard(tx.mainCards().findById(cardId), cardId);
          MainCard edited = current.editDraft(expectedRevision, newBody);
          tx.mainCards().updateDraft(edited, expectedRevision);
          return edited;
        });
  }

  /** Validate the manually chosen dialect before opening a transaction; never assign an address. */
  public MainCard installMainCard(UUID cardId, long expectedRevision, String addressLiteral) {
    Objects.requireNonNull(cardId, "cardId");
    NumericAlphaAddress address = NumericAlphaAddress.parse(addressLiteral);
    return unitOfWork.transact(
        tx -> {
          MainCard current = requireCard(tx.mainCards().findById(cardId), cardId);
          if (current.revision() != expectedRevision) {
            throw new StaleDraftRevisionException(expectedRevision, current.revision());
          }
          MainCard installed = current.install(address, clock.instant());
          List<MainCard> ordered = tx.mainCards().findInstalledInAddressOrder();
          UUID predecessor = null;
          UUID successor = null;
          for (MainCard neighbor : ordered) {
            int comparison = neighbor.address().compareTo(address);
            if (comparison < 0) predecessor = neighbor.id();
            else if (comparison > 0) {
              successor = neighbor.id();
              break;
            }
          }
          PlacementSnapshot placement =
              new PlacementSnapshot(
                  cardId, address, predecessor, successor, installed.installedAt());
          tx.mainCards().install(installed, placement, expectedRevision);
          return installed;
        });
  }

  public CardAnnotation appendAnnotation(UUID cardId, List<BodyRun> runs) {
    Objects.requireNonNull(cardId, "cardId");
    CardAnnotation annotation = new CardAnnotation(ids.get(), clock.instant(), runs);
    unitOfWork.transact(
        tx -> {
          MainCard current = requireCard(tx.mainCards().findById(cardId), cardId);
          current.appendAnnotation(annotation);
          tx.mainCards().appendAnnotation(cardId, annotation);
          return null;
        });
    return annotation;
  }

  public List<MainCard> getDrafts() {
    return unitOfWork.transact(tx -> tx.mainCards().findDrafts());
  }

  public Optional<MainCard> getMainByExactAddress(String addressLiteral) {
    NumericAlphaAddress address = NumericAlphaAddress.parse(addressLiteral);
    return unitOfWork.transact(tx -> tx.mainCards().findByAddress(address));
  }

  public List<MainCard> getInstalledInAddressOrder() {
    return unitOfWork.transact(tx -> tx.mainCards().findInstalledInAddressOrder());
  }

  public OrderedNeighbors getOrderedNeighbors(String addressLiteral) {
    NumericAlphaAddress address = NumericAlphaAddress.parse(addressLiteral);
    return unitOfWork.transact(
        tx -> {
          MainCard current =
              tx.mainCards()
                  .findByAddress(address)
                  .orElseThrow(() -> new MainCardNotFoundException(addressLiteral));
          List<MainCard> ordered = tx.mainCards().findInstalledInAddressOrder();
          int index = -1;
          for (int candidate = 0; candidate < ordered.size(); candidate++) {
            if (ordered.get(candidate).id().equals(current.id())) {
              index = candidate;
              break;
            }
          }
          if (index < 0)
            throw new PersistenceException("Installed card disappeared from ordered query");
          return new OrderedNeighbors(
              index == 0 ? Optional.empty() : Optional.of(ordered.get(index - 1)),
              index + 1 >= ordered.size() ? Optional.empty() : Optional.of(ordered.get(index + 1)));
        });
  }

  private static MainCard requireCard(Optional<MainCard> card, UUID id) {
    return card.orElseThrow(() -> new MainCardNotFoundException(id));
  }
}
