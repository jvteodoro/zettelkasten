package org.zettelkasten.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.zettelkasten.application.port.BibTeXFileParser;
import org.zettelkasten.application.port.ParsedBibTeXEntry;
import org.zettelkasten.application.port.UnitOfWork;
import org.zettelkasten.domain.BibCard;
import org.zettelkasten.domain.BibNote;
import org.zettelkasten.domain.BibliographicSource;
import org.zettelkasten.domain.SourceExternalId;

/** User-authored bibliography workflows and explicit per-file BibTeX imports. */
public final class BibliographyService {
  private static final String PROVIDER = "BIBTEX_FILE";

  private final UnitOfWork unitOfWork;
  private final Clock clock;
  private final Supplier<UUID> ids;
  private final BibTeXFileParser parser;

  public BibliographyService(
      UnitOfWork unitOfWork, Clock clock, Supplier<UUID> ids, BibTeXFileParser parser) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.parser = Objects.requireNonNull(parser, "parser");
  }

  public BibliographicSource createSource(BibliographicSource source) {
    Objects.requireNonNull(source, "source");
    unitOfWork.transact(
        tx -> {
          tx.bibliography().insertSource(source);
          return null;
        });
    return source;
  }

  public void addExternalAlias(UUID sourceId, String provider, String scope, String externalId) {
    SourceExternalId alias = new SourceExternalId(ids.get(), sourceId, provider, scope, externalId);
    unitOfWork.transact(
        tx -> {
          if (tx.bibliography().findSource(sourceId).isEmpty())
            throw new IllegalArgumentException("Bibliographic source does not exist: " + sourceId);
          tx.bibliography().insertSourceExternalId(alias);
          return null;
        });
  }

  public BibCard createBibCard(UUID sourceId, String readingGoal, String sourceOverview) {
    BibCard card = new BibCard(ids.get(), sourceId, clock.instant(), readingGoal, sourceOverview);
    unitOfWork.transact(
        tx -> {
          if (tx.bibliography().findSource(sourceId).isEmpty())
            throw new IllegalArgumentException("Bibliographic source does not exist: " + sourceId);
          tx.bibliography().insertBibCard(card);
          return null;
        });
    return card;
  }

  public BibNote addBibNote(
      UUID bibCardId, BibNote.LocatorType locatorType, String locatorLiteral, String authoredText) {
    return unitOfWork.transact(
        tx -> {
          if (tx.bibliography().findBibCard(bibCardId).isEmpty())
            throw new IllegalArgumentException("BibCard does not exist: " + bibCardId);
          int ordinal = tx.bibliography().findBibNotes(bibCardId).size();
          BibNote note =
              new BibNote(
                  ids.get(),
                  bibCardId,
                  ordinal,
                  locatorType,
                  locatorLiteral,
                  authoredText,
                  clock.instant(),
                  null);
          tx.bibliography().insertBibNote(note);
          return note;
        });
  }

  public List<BibliographicSource> getSources() {
    return unitOfWork.transact(tx -> tx.bibliography().findSources());
  }

  public List<BibNote> getBibNotes(UUID bibCardId) {
    return unitOfWork.transact(tx -> tx.bibliography().findBibNotes(bibCardId));
  }

  public BibTeXImportResult dryRunImport(String namespace, String sourceName, String utf8File) {
    String cleanNamespace = required(namespace, "namespace");
    String cleanSourceName = required(sourceName, "sourceName");
    Objects.requireNonNull(utf8File, "utf8File");
    ParsedFile parsed = parse(utf8File);
    UUID batchId = ids.get();
    return unitOfWork.transact(
        tx -> plan(tx, batchId, cleanNamespace, parsed, BibTeXImportResult.Status.DRY_RUN));
  }

  public BibTeXImportResult applyImport(String namespace, String sourceName, String utf8File) {
    String cleanNamespace = required(namespace, "namespace");
    String cleanSourceName = required(sourceName, "sourceName");
    Objects.requireNonNull(utf8File, "utf8File");
    ParsedFile parsed = parse(utf8File);
    UUID batchId = ids.get();
    return unitOfWork.transact(
        tx -> {
          BibTeXImportResult proposal =
              plan(tx, batchId, cleanNamespace, parsed, BibTeXImportResult.Status.DRY_RUN);
          boolean rejected =
              proposal.entries().stream()
                  .anyMatch(
                      entry ->
                          entry.outcome() == BibTeXImportResult.Outcome.CONFLICT
                              || entry.outcome() == BibTeXImportResult.Outcome.CHANGED
                              || entry.outcome() == BibTeXImportResult.Outcome.ERROR);
          String batchStatus = rejected ? "REJECTED" : "APPLIED";
          tx.bibliography()
              .insertImportBatch(
                  batchId,
                  cleanNamespace,
                  cleanSourceName,
                  sha256(utf8File),
                  batchStatus,
                  clock.instant());

          List<BibTeXImportResult.EntryResult> savedResults = new ArrayList<>();
          if (parsed.error() != null) {
            tx.bibliography()
                .insertImportEntry(
                    ids.get(), batchId, null, "[file]", utf8File, "ERROR", parsed.error());
            savedResults.add(
                new BibTeXImportResult.EntryResult(
                    "[file]", BibTeXImportResult.Outcome.ERROR, null, parsed.error()));
          } else {
            Map<String, Integer> duplicateKeys = new LinkedHashMap<>();
            for (int index = 0; index < parsed.entries().size(); index++) {
              ParsedBibTeXEntry entry = parsed.entries().get(index);
              int duplicateOrdinal = duplicateKeys.merge(entry.citationKey(), 1, Integer::sum);
              BibTeXImportResult.EntryResult planned = proposal.entries().get(index);
              UUID sourceId = planned.sourceId();
              if (!rejected && planned.outcome() == BibTeXImportResult.Outcome.NEW) {
                BibliographicSource source = toSource(sourceId, entry, clock.instant());
                tx.bibliography().insertSource(source);
                tx.bibliography()
                    .insertSourceExternalId(
                        new SourceExternalId(
                            ids.get(), sourceId, PROVIDER, cleanNamespace, entry.citationKey()));
              }
              tx.bibliography()
                  .insertImportEntry(
                      ids.get(),
                      batchId,
                      !rejected
                              || planned.outcome() == BibTeXImportResult.Outcome.UNCHANGED
                              || planned.outcome() == BibTeXImportResult.Outcome.CHANGED
                          ? sourceId
                          : null,
                      duplicateOrdinal == 1
                          ? entry.citationKey()
                          : entry.citationKey() + "#duplicate-" + duplicateOrdinal,
                      entry.rawFile(),
                      planned.outcome().name(),
                      planned.diagnostic());
              savedResults.add(
                  new BibTeXImportResult.EntryResult(
                      planned.citationKey(),
                      planned.outcome(),
                      rejected && planned.outcome() == BibTeXImportResult.Outcome.NEW
                          ? null
                          : planned.sourceId(),
                      planned.diagnostic()));
            }
          }
          return new BibTeXImportResult(
              batchId,
              rejected ? BibTeXImportResult.Status.REJECTED : BibTeXImportResult.Status.APPLIED,
              savedResults);
        });
  }

  private BibTeXImportResult plan(
      UnitOfWork.Transaction tx,
      UUID batchId,
      String namespace,
      ParsedFile parsed,
      BibTeXImportResult.Status status) {
    if (parsed.error() != null) {
      return new BibTeXImportResult(
          batchId,
          status,
          List.of(
              new BibTeXImportResult.EntryResult(
                  "[file]", BibTeXImportResult.Outcome.ERROR, null, parsed.error())));
    }
    Map<String, Integer> counts = new LinkedHashMap<>();
    parsed.entries().forEach(entry -> counts.merge(entry.citationKey(), 1, Integer::sum));
    Map<String, Integer> doiCounts = new LinkedHashMap<>();
    parsed.entries().stream()
        .map(entry -> field(entry, "doi"))
        .filter(Objects::nonNull)
        .map(doi -> doi.toLowerCase(Locale.ROOT))
        .forEach(doi -> doiCounts.merge(doi, 1, Integer::sum));
    List<BibTeXImportResult.EntryResult> result = new ArrayList<>();
    for (ParsedBibTeXEntry entry : parsed.entries()) {
      if (counts.get(entry.citationKey()) > 1) {
        result.add(
            new BibTeXImportResult.EntryResult(
                entry.citationKey(),
                BibTeXImportResult.Outcome.CONFLICT,
                null,
                "Duplicate citation key in this file; no entry was imported."));
        continue;
      }
      var existingId =
          tx.bibliography().findSourceIdByExternalId(PROVIDER, namespace, entry.citationKey());
      if (existingId.isPresent()) {
        BibliographicSource existing = tx.bibliography().findSource(existingId.get()).orElseThrow();
        BibliographicSource candidate = toSource(existing.id(), entry, existing.createdAt());
        boolean same =
            existing.sourceType().equals(candidate.sourceType())
                && existing.rawFields().equals(candidate.rawFields());
        result.add(
            new BibTeXImportResult.EntryResult(
                entry.citationKey(),
                same ? BibTeXImportResult.Outcome.UNCHANGED : BibTeXImportResult.Outcome.CHANGED,
                existing.id(),
                same
                    ? "Already imported in this namespace."
                    : "Changed metadata requires explicit conflict review."));
        continue;
      }
      String doi = field(entry, "doi");
      if (doi != null
          && (doiCounts.getOrDefault(doi.toLowerCase(Locale.ROOT), 0) > 1
              || !tx.bibliography().findSourcesByDoi(doi).isEmpty())) {
        result.add(
            new BibTeXImportResult.EntryResult(
                entry.citationKey(),
                BibTeXImportResult.Outcome.CONFLICT,
                null,
                "DOI already exists on another source; records were not merged."));
      } else {
        result.add(
            new BibTeXImportResult.EntryResult(
                entry.citationKey(),
                BibTeXImportResult.Outcome.NEW,
                ids.get(),
                "New source; no BibCard or BibNote will be generated."));
      }
    }
    return new BibTeXImportResult(batchId, status, result);
  }

  private ParsedFile parse(String utf8File) {
    try {
      return new ParsedFile(parser.parse(utf8File), null);
    } catch (RuntimeException exception) {
      String diagnostic =
          exception.getMessage() == null ? "Could not parse BibTeX file." : exception.getMessage();
      return new ParsedFile(List.of(), diagnostic);
    }
  }

  private static BibliographicSource toSource(
      UUID id, ParsedBibTeXEntry entry, java.time.Instant now) {
    Map<String, String> fields = new LinkedHashMap<>();
    entry.fields().forEach((key, value) -> fields.put(key.toLowerCase(Locale.ROOT), value));
    String creators = first(fields, "author", "editor");
    return new BibliographicSource(
        id,
        entry.entryType(),
        value(fields, "title"),
        creators,
        field(entry, "year"),
        first(fields, "journal", "booktitle", "journaltitle"),
        field(entry, "publisher"),
        field(entry, "edition"),
        field(entry, "volume"),
        first(fields, "number", "issue"),
        field(entry, "pages"),
        field(entry, "doi"),
        field(entry, "isbn"),
        field(entry, "url"),
        entry.fields(),
        entry.rawFile(),
        now,
        now);
  }

  private static String field(ParsedBibTeXEntry entry, String name) {
    return entry.fields().entrySet().stream()
        .filter(item -> item.getKey().equalsIgnoreCase(name))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  private static String first(Map<String, String> fields, String... names) {
    for (String name : names) {
      String found = fields.get(name);
      if (found != null) return found;
    }
    return "";
  }

  private static String value(Map<String, String> fields, String name) {
    return fields.getOrDefault(name, "");
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String required(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
    return value;
  }

  private record ParsedFile(List<ParsedBibTeXEntry> entries, String error) {
    private ParsedFile {
      entries = List.copyOf(entries);
    }
  }
}
