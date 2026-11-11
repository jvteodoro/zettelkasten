# Temporal provenance, event journal, graph export

## Goal

After years of writing, retain the historically irrecoverable facts (when cards were created/installed, which neighbors existed, when an explicit link/index entry was authored, which source was consulted). Do not expose analytics, backlinks or graph shortcuts in the normal user interface.

## Transactional journal

Every meaningful successful mutation writes exactly one or more typed events in the SAME DB transaction as aggregate state; no asynchronous queues. Envelope:

~~~json
{
  "schemaVersion": 1,
  "eventId": "uuid",
  "sequence": 42,
  "eventType": "CardInstalled",
  "aggregateType": "MAIN_CARD",
  "aggregateId": "uuid",
  "occurredAt": "2026-11-11T20:00:00Z",
  "correlationId": "uuid",
  "payload": {
    "addressLiteral": "3118/2a",
    "addressScheme": "ANTINET_SLASH_V1",
    "predecessorId": "uuid-or-null",
    "successorId": "uuid-or-null",
    "bodyRevision": 4
  }
}
~~~

Store namespaced UTC ISO timestamps (Clock injected), global DB sequence monotonic, type and version. PII/content minimized in event payload; avoid full text duplication when ID/digest suffices. Events are append-only. Unknown future event versions remain exportable. No event for cursor movement, hover, typing or time-on-card.

Recommended event taxonomy:
\`CardCreated\`, \`DraftEdited\`, \`DraftDiscarded\`, \`CardInstalled\`, \`CardAnnotationAppended\`, \`ReferenceAuthored\`, \`ReferenceResolutionChanged\`, \`IndexCardCreated\`, \`IndexEntryAdded\`, \`IndexEntryRedirected\`, \`BibliographicSourceImported\`, \`BibliographicSourceReconciled\`, \`BibCardCreated\`, \`BibNoteAdded\`, \`BackupCreated\` (maintenance log only if valuable). Avoid logging every transient UI action.

## Source of truth and projections

State tables + append-only events share canonical SQLite file. Events represent audit history; do not force complex replay to bootstrap the interactive UI in v1. Export/analytics graph projections can be regenerated from canonical tables + journal. No second writable state store in v1.

The SQLite adapter writes one minimal schema-version-1 event in the same transaction
as each implemented draft create/edit/install/annotation, authored reference, Index
card, imported bibliographic source, or BibCard mutation. Event IDs and correlation
IDs are UUIDs; the database assigns the global sequence; timestamps are UTC ISO-8601.
Payloads avoid card text and retain only identifiers and small provenance fields.
The journal reader returns unknown positive schema versions and payload JSON without
interpreting or rewriting them, so a later exporter can preserve them. Mutations not
yet implemented as application operations (including BibNote and import batches)
will add their corresponding events with those operations.

`src/test/resources/fixtures/events/card-installed-v1.json` is the representative
portable envelope fixture. Event rows are append-only and are not a replay source
for starting the interactive UI.

Graph edge namespaces (do not confuse):
- \`AUTHORED_CARD_REFERENCE\` directed, multiedge, preserves literal and span/run.
- \`AUTHORED_INDEX_POINTER\` selective user-authored index -> Main.
- \`AUTHORED_SOURCE_REFERENCE\` Main/BibNote -> BibSource, with locator.
- \`FILED_ADJACENT_TO\` **derived** snapshot of physical order; not semantic or user-authored.
- \`ADDRESS_STEM_CONTEXT\` **derived** syntactic relation, not PARENT_OF importance/concept relation.

Analytics export in versioned JSONL plus manifest. Entity records and edge records carry \`fromId,toId,edgeId,type,authoredAt,derived:boolean,sourceRunId,literal,targetResolutionStatus\`. Do not silently discard unresolved references: export unresolved record with null toId and literal. Historical graph snapshots can be rebuilt from events where sufficient.

## Export manifest & compatibility

Define \`manifest.json\`: format name, formatVersion, appVersion, schemaVersion, exportUtc, item/event counts, per-file SHA-256, address scheme registry, source database identifier, optional privacy notice. Files: \`cards.jsonl\`, \`runs.jsonl\`, \`placements.jsonl\`, \`references.jsonl\`, \`index.jsonl\`, \`bibliography.jsonl\`, \`events.jsonl\`. UTF-8 LF, deterministic row ordering, stable canonical JSON key order, explicit nulls, strict escape rules. No invented or synthesized links on import. Keep all unknown-version fields in a round-trip preservation bucket or reject with explicit error before mutation.

## Analytics use cases (future, NOT in TUI)

- Directed in-degree/out-degree by time, isolated cards, connected components, path lengths.
- Historical branching depth and number of cards installed by neighborhood/date.
- Manual index coverage and cross-branch authored remote links.
- Bibliographic source influence, note conversion from Bib to Main, temporal change of graph connectivity.
- Compare authored edges vs derived proximity without conflation; handle unresolved edges and event-timestamp gaps.

## Anti-goals

Do NOT add real-time graph explorer, auto-suggested links, backlinks or importance ranking in main UI. Never use graph centrality to auto-index or auto-file. Do NOT infer links from keyword similarity. No telemetry collection without later explicit opt-in and privacy design.
