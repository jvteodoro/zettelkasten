# Architecture — durable local modular monolith

## Decisions

- **Runtime:** Java 25 LTS, JDK 25 build/run, Maven 3.9+. Target `maven.compiler.release=25` and pin compiler/Surefire/Failsafe plugin versions compatible with JDK 25. No preview features by default; compiler target changes require an ADR. Maven Toolchains may be used to ensure JDK 25 is selected deterministically. Favor package-separated modular monolith over premature multi-module Maven overhead. Package names below are stable boundaries.
- **UI:** Lanterna terminal screen and explicit modal state machine. No UI code calls JDBC directly.
- **Storage:** embedded SQLite via JDBC; single canonical user database per slip-box, no server. LadybugDB is an optional future **derived graph projection**, never a second source of truth.
- **Library integrations:** BibTeX import; optional future Zotero adapter at a port. No remote dependencies for normal operation.
- **Compatibility:** database schema migrations, versioned address schemes and events, stable UTF-8 JSONL export with a manifest, tested import/recovery.

## Dependency rule

org.zettelkasten.domain -> Java standard library only.
org.zettelkasten.application -> domain + port interfaces only.
org.zettelkasten.infrastructure -> application ports + domain + external libraries.
org.zettelkasten.tui -> application use cases + terminal lib; never JDBC.
org.zettelkasten.cli -> wiring/bootstrap, privileged maintenance commands.

Interfaces are in application. No persistence entities leak into public domain API.

## Logical diagram

~~~text
             Terminal keys / ncurses-like Lanterna
                          |
                    tui: screens
                          |
                  application: commands
                  application: queries
                          |
                  domain + repository ports
                          |
               infrastructure: JDBC SQLite
                          |
                   slipbox.db (canonical)

BibTeX parser --> adapter --> application.importSource
Zotero local API (later) --> adapter --> same port
Maintenance CLI --> application/export/integrity --> SQLite
Optional graph projection <-- versioned export/events
~~~

## Core use cases and transaction boundaries

Each command is one application service method with input validation, permissions via state rules and one SQLite transaction. Domain events and state updates commit atomically. Queries are read-only. Asynchronous events are unnecessary.

- CreateDraft(cardId, initialBlocks) -> draft + CardCreated event.
- EditDraft(cardId, newBlocks, expectedRevision) -> revision + event; reject if installed.
- InstallMainCard(cardId, chosenAddress, explicitBefore/After optional) -> lock/write transaction; validate parser, unique address, coherent filing anchors; placement + state + event; no automatic address mutation.
- AddAnnotation(installedCardId, appendBlocks) -> new immutable annotation record, event.
- AddCardReference(cardId, spanId, literal, kind, target?) -> reference stored with literal and current resolution; unresolved allowed.
- CreateIndexEntry(indexCardId, authoredText, targetLiteral) -> inserted entry with local sequence; no auto-populate.
- ImportBibTeX(importBatchId, rawFile, options) -> per-entry idempotent reconcile; preserve raw and user notes; emit import events.
- CreateBibNote(sourceId, locator, text) -> immutable authored note.
- Backup/Export/Doctor -> maintenance path, never implicit UI autosync.

## Type distinctions

- Card: MAIN_DRAFT, MAIN_INSTALLED, INDEX_LIST, INDEX_KEYTERM, BIB_CARD (or separate specialized aggregate mapped to card records).
- Source: bibliographic identity; NOT the same as BibCard.
- CardBody: ordered, immutable typed spans; references point into spans with stable span IDs rather than UTF-16 character offsets.
- Reference: CARD, INDEX_KEYTERM, BIB_SOURCE, EXTERNAL_RESOURCE; rendering and resolution separated.
- Placement: address scheme/version + literal address + sortable position + captured insertion neighbors. Spatial adjacency does not imply semantic relation.
- Events: immutable envelope with eventId, aggregateId/type, UTC time, schemaVersion, sequence, payload, correlationId.

## Cross cutting requirements

- Inject Clock, UUID generator, filesystem and transaction manager; repeatable tests.
- Respect SQLite busy_timeout, foreign_keys=ON for EVERY connection, deterministic locking policy, with a single app writer.
- Validate UTF-8, normalize only where documented; preserve user literals. Avoid destructive sanitization.
- No online logging/telemetry; logs avoid card text and bibliographic secrets.
- All file operations that mutate canonical data use atomic replacement or transaction, plus fsync where applicable.
- Terminal resizes, escape sequences, wide Unicode and grapheme input are covered by tests.
- Version upgrades never infer missing references by full-text extraction; migration preserves historical literals.

## Contracts that require ADR before changing

Address grammar/comparator, canonical database format, reference representation, post-install immutability, citation-key mapping, event versioning. See docs/adr/.

## Source files and tests layout

~~~text
src/main/java/org/zettelkasten/{domain,application,infrastructure,tui,cli}
src/main/resources/db/migration/
src/test/java/org/zettelkasten/...
src/test/resources/fixtures/{addresses,bibtex,db,tui}
~~~

Use pure unit tests for domain, integration tests with temporary SQLite files (not :memory: for crash/reopen scenarios), and terminal harness tests with synthetic keypresses. No test contacts Zotero or the internet.
