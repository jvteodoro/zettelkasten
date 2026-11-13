# ADR 0009: Resolve authored Index and bibliographic references conservatively

Status: Accepted — 2026-11-12.

## Context

Typed Maincard runs are persisted as authored references. Address references
already have a defined grammar and resolve against Main placements. Keyterm and
bibliographic references also carry stable target slots, but their typed runs
preserve user-entered literals and do not choose a target UUID in the editor.
BibTeX citation keys are scoped to import namespaces, so the same printed key
can identify different source records.

## Decision

- A keyterm reference resolves only when its literal exactly matches one
  dedicated Index keyterm. More than one exact match produces AMBIGUOUS.
- A bibliographic reference resolves only for the documented
  `b.<citation-key>[, locator]` form, using an exact citation-key match across
  scoped BibTeX aliases. One distinct source UUID resolves; multiple source UUIDs
  produce AMBIGUOUS. Literals in other formats, including `r.` references, remain
  PENDING because there is no explicit identity mapping.
- The original authored literal and source run remain unchanged. Resolution is
  storage-only, runs in the same unit of work as the card, Index or bibliography
  operation that makes a target available, and records a domain event. It adds
  no target preview, suggestions or navigation to the TUI.

## Consequences

Unscoped references can remain ambiguous even when each underlying BibTeX alias
is individually scoped. The user can still preserve and read the printed literal;
automatic source matching never merges bibliographic records or rewrites the
reference text.
