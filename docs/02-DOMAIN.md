# Domain specification and invariants

The pure Java implementation in `org.zettelkasten.domain` starts with `MainCard`,
immutable typed body runs, append-only `CardAnnotation`, and stable Index/Bib card
identities. Lists are copied at aggregate boundaries; order in a body is authored
run order. Dedicated address parsing and reference-resolution types are added in
ZK-03 and ZK-04; persistence and UI remain outside this package.

ZK-04 records each reference as an authored occurrence tied to its source card
and run. Relative address references parse before filing and resolve to an
address only when source placement exists. Target existence and stable-ID lookup
belong to application/storage; domain values do not expose follow or backlink
operations.

ZK-07 adds `MainCardService` as the Desk/Main application boundary. It owns
transactions for draft creation/edit, intentional install, and annotations, and
exposes exact-address and physical-order queries. Install snapshots the immediate
ordered predecessor and successor. These neighbors describe filing order only;
the service offers no reference traversal, automatic address selection, or
semantic relationship query.

## Aggregates

### MainCard
- id: UUID (stable), type MAIN, state DRAFT | INSTALLED.
- draftRevision monotonically increases for desk editing.
- authoredBody: ordered list of block/run records (text, literal card reference, keyterm reference, external reference); stable run ID and sequence; Unicode allowed.
- createdAt UTC, installedAt nullable, chosenAddress nullable for DRAFT; installedBodySnapshot revision immutable.
- annotations: append-only, separately timestamped, each with ordered runs. An installed card is never silently rewritten, renumbered or discarded.

### Placement
- mainCardId unique, address originalLiteral unique within a slipbox, canonicalAddress, schemeId/version, filingSortKey, insertedBeforeCardId?, insertedAfterCardId?, installedAt.
- Position in main box is a non-semantic physical ordering, NOT conceptual superiority. Reshuffling filingSortKeys on rebalancing must not mutate the address, identity or original placement event.
- Existing card addresses are stable. Corrections require explicit audited recovery workflow, **not** a normal user command.

### Reference
- id, sourceCardId, sourceRunId, kind, originalLiteral, targetLiteral, targetCardId? or targetSourceId? or targetIndexId?, resolutionStatus PENDING|RESOLVED|AMBIGUOUS|BROKEN, createdAt.
- Recorded in typed editor runs, not automatically harvested from arbitrary matching text.
- Preserve abbreviations /1, ~/1/... and full cardlink as written. Resolution relies on source filing context; unresolved before installation is normal.
- Multiple identical references in a card are distinct authored occurrences (multigraph); never dedupe by source+target.
- Reference direction is authored, not automatically reciprocal. No visual backlinks.
- Typed external references have source ID, locator (page, timecode, section) and display literal; raw exact reference retained.

### IndexBox
- A–Z dividers; ListIndexCard per letter and overflow number, entries in **temporal insertion order** rather than alphabetically within each card.
- IndexEntry may refer to literal Main address or another Index keyterm via a manual see-also/redirect, with optional linked stable target.
- Dedicated KeytermIndexCard holds manually curated multiple addresses and related terms; sorted alphabetically among dedicated keyterms, but list entries keep creation order.
- Index is sparse and manually curated. No automatic index generation from maincard body.

### BibBox
- BibliographicSource is distinct from BibCard. Internal UUID, imported raw record, normalized known fields, zero/many external identifiers (DOI, ISBN, Zotero item+library keys, BibTeX key scoped to import namespace), sourceType, merge status.
- BibCard is a research card associated with a source: readingGoal, outline/overview, multiple short BibNotes with raw locator, authoredText, order and provenance.
- Re-import updates imported metadata according to explicit policy only; never overwrites bibnotes, goal, user corrections or maincard ExRefs.
- Material can be book, journal article, lecture, podcast, site, video; locator is not restricted to integer page.

## Lifecycle and allowed commands

~~~text
DRAFT (Desk) --Install(address)--> INSTALLED (Main)
  |                              |
  +--Edit/Save/Discard*          +--AppendAnnotation
  +--AddReference                +--AppendReference
                                 +--Read (never edit original)

* Discard is a user-confirmed draft-only operation with auditable tombstone;
  database-level maintenance deletion is restricted and explicitly separate.
~~~

Index and Bib cards use their own lifecycle/ordering rules; do not force a Main numeric-alpha address onto them.

## Rules by operation

- Install atomic: validate current state, address format, uniqueness, anchor consistency, snapshot revision and write state+placement+event in one transaction; duplicate exception returns safe error, leaves draft intact.
- Changing installedBodySnapshot is forbidden; only append annotations. If a typo must be corrected, add a correction annotation; integrity maintenance explicitly distinct.
- Link insertion stores the *exact user-authored literal* and a typed run; no preview, suggestions or navigation action.
- Each typed card, keyterm and external-reference run also has a normalized reference projection keyed to that run. Repeated occurrences stay separate; draft edits replace only the editable draft projection, while annotations append new projections.
- Link target existence is not required; when resolvable, store resolved stable ID; when not, PENDING. If a referenced card is installed later, a maintenance resolver may update the resolution projection, without changing the original source literal.
- A typed keyterm literal resolves only to one exact dedicated Index keyterm; duplicate exact terms are AMBIGUOUS. A bibliographic literal of the form `b.<citation-key>, <locator>` resolves against scoped BibTeX aliases only when it identifies one source UUID across the archive; multiple matching source UUIDs are AMBIGUOUS. The complete authored literal, including locator, remains unchanged.
- Address references are rechecked as drafts are saved and cards are installed. Resolution changes and reference rows commit with the corresponding card operation; migration V004 backfills typed runs from older archives and records a backfill event for each occurrence.
- IDs never reused. A BibTeX entry with same citation key is **not** automatically treated as same publication without evidence.

## Failures / edge cases

- Unicode combining marks, CJK double-width, German diacritics, Portuguese accents, multiline, embedded slash tilde punctuation.
- A draft with stemlink /1 has no source address yet: keep pending until installed.
- Self-references and cycles permitted; semantic graph algorithms live only in offline analytics layer.
- Repeated imports, conflicting DOI, missing BibTeX title/year, BibTeX @string macros, crossref, accented TeX escapes; preserve raw.
- Main address can designate a valid prefix whose referenced intermediate card does not exist. Do not auto-create synthetic maincards.
- Multiple user-created entries for same term allowed, but index card ordering and identity explicit.
- Simultaneous writes by same process: optimistic draft revision + SQLite transaction boundary; reject stale edits rather than clobber.
- Reference to removed/discarded draft: retain literal with broken/pending status; never silently erase historical edges.
