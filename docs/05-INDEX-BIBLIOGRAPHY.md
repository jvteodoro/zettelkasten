# Index and Bibliography: analog semantics, digital storage

## Index

Source: Scheper chapter 9, pp. 295–309. Index links are selective **points of entry**, not exhaustive search results. In list indexcard A, *Argument* can appear before *Aggressivität*, because entries accumulate chronologically (p. 306). Support many list cards per letter \`A (1)\`, \`A (2)\`, etc. Create 26 alphabetic dividers (not necessarily 26 substantive entries). Reordering Index entries automatically is forbidden.

Two subtypes:

1. **ListIndexCard**: fixed letter, overflow sequence and user-authored entries in temporal insertion order. Each entry can be KEYTERM→MAIN_ADDRESS, KEYTERM→INDEX_KEYTERM (redirect), or authored See Also. Preserve literal entries.
2. **KeytermIndexCard**: manually promoted/dedicated keyed card, contains multiple user-curated address references, see-also and contexts; organized alphabetically among dedicated cards. Promotion must not silently erase old entries; retain strike-through/redirect history.

The current Index domain and SQLite adapter implement these authored records. List cards are explicitly numbered per divider, and their entries receive monotonically increasing per-card order when appended. Each entry retains its authored text, target kind and literal; Main-address and dedicated-keyterm pointers resolve to stable IDs only when an exact installed target exists. Missing targets remain as written. See Also remains authored text and is not resolved through search. Promoting an entry creates a dedicated keyterm card and a copy of that entry while recording an append-only source-to-promotion mapping; the list entry and its original order remain intact. Card creation, entry addition and promotion provenance each produce domain events in the same unit of work as their rows.

Index does not auto-link every occurrence, tag cards, suggest synonym, or offer search. Term->address relation is an authored edge even if the target address currently does not exist.

## Bibliographic entities

**BibliographicSource** describes an external work; **BibCard** records the research interaction; **BibNote** is a short observation tied to a human locator. This distinction is required by Scheper chapter 14 pp. 422–433 (front: bibliographic details, reading aim, source overview; back: brief page/timecoded notes). Source may be book, article, video, lecture, podcast or website.

- Source fields: UUID, sourceType, title, creator(s), date/year, container, publisher, edition, volume/issue/pages, DOI, ISBN, URL, cite-key aliases, original TeX/BibTeX attributes and raw record.
- Provenance: \`provider\` (\`BIBTEX_FILE\`, future \`ZOTERO_LOCAL\`), \`importBatchId\`, original path only as non-sensitive descriptive name, import time, raw content digest, provider ID, source record key, merge decision.
- Source external identities are *scoped*: BibTeX key scoped by import namespace/file; Zotero item key scoped by library; DOI is not a unique guarantee when conflicting records exist.
- Research fields are user-owned and never overwritten by source sync: readingGoal, sourceOverview, BibNotes, local annotations, manually authored ExRefs.
- BibNote: own UUID, source/BibCard ID, locator type (page/time/section/other), literal locator (e.g. \`xiv\`, \`12-15\`, \`00:32:18\`), short author text, createdAt, source ordering, optional derived maincard ID.
- Internal ExRefs must be able to refer to sources with raw literal like \`b.NakladovaJohann, 199\` or \`r.Zetteldan.1\` plus a normalized source ID and locator. See book pp. 239–242.

## BibTeX adapter

- Use a real Java BibTeX parser (proposed JBibTeX; verify actively maintained version/license at implementation time); do not parse entries with a regex.
- Accept UTF-8 and recognize TeX escapes, nested braces, comments, \`@string\`, \`@preamble\`, \`crossref\` if library supports; preserve original raw file and source record payload when unsure.
- Preserve previously unknown fields; do not drop unrecognized source types. Store import errors per entry and report summarized actionable diagnostics.
- Import is explicitly initiated via Bib screen or CLI; no background file scanning.
- Idempotency: reimport of unchanged file/entry does not duplicate source or bibnotes; changed same-key record creates explicit conflict when identity ambiguous. Duplicate DOI across different entries must not silently merge.
- Atomicity: either per-file transaction with well-defined rejection semantics, or per-entry transactions with a persisted import manifest and resumable progress. Choose and test before implementation; recommended v1 per-file atomic to prevent partial surprises.
- Expose a dry-run report of new/changed/conflict/unchanged before applying.
- Support preserving BibTeX citation aliases after key rename.

The offline importer stores source UUIDs independently from provider aliases, scoped by
namespace and citation key. Dry-run parses and classifies entries without writing.
Apply is per-file atomic: any parse error, changed citation key, or DOI collision
records a rejected batch and commits none of that file's new sources. Unchanged aliases
reimport as unchanged; two namespaces may use the same citation key without merging.
Imported fields and the complete original UTF-8 file are retained. Import never creates
a BibCard or BibNote. Users create those separately, and notes keep their exact locator
text and append order.
When a citation key is intentionally renamed, users can append the new scoped alias to
the existing source identity; aliases are never rewritten or inferred automatically.

The parser dependency is isolated behind an infrastructure adapter. ADR
0006-bibtex-parser records the selected version, license, maintenance caveat, and
replacement path.

## Zotero seam

Port \`BibliographyProvider\`: \`listSources\`, \`fetchSource(externalKey)\`, \`importBatch\`; import ID always scoped. A \`ZoteroLocalProvider\` can later use the *official local API*, explicitly opted-in and only when Zotero is available; no direct access to Zotero's SQLite database, no automatic cloud calls, no requirement for Zotero to use the Antinet.
- Mapping schema anticipates \`libraryId\`, \`itemKey\`, \`itemVersion\`, raw CSL-JSON/item JSON, attachments as external references (no importing binary in v1), sync conflicts.
- Never overwrite local reading annotations due to a changed Zotero title/author. Imported metadata updates on explicit user confirmation when changed.
- Zotero adapter is **future integration**, not part of v1 release gate; add tests with a fixture provider.

## Acceptance

- Two entries with identical cite key in separate files are not silently conflated.
- Repeated import round-trip preserves raw BibTeX fields, Unicode names, and user BibNotes.
- A Main ExRef to an unimported or unavailable source retains its literal.
- User can create a source manually when BibTeX has no reference.
- All workflows offline, no auto index entries from imported keywords.
