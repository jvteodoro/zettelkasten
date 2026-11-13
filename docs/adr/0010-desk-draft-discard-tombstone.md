# ADR 0010: Keep discarded Desk drafts as tombstones

## Status

Accepted

## Context

The domain and TUI contracts require a user-confirmed way to discard a saved,
unfiled Desk draft, with an auditable tombstone. Physical deletion would lose
authored text and could orphan literal reference history.

## Decision

V005 adds nullable `cards.discarded_at`. A single transaction stamps the draft
and appends `DraftDiscarded`; the stable UUID, body runs, and reference projection
remain available by explicit archive access and export. The Desk query omits
tombstones. A separate confirmation screen requires Y and supports N/Esc to keep
the draft. SQLite triggers prevent subsequently adding runs, annotations, or a
placement. Domain rehydration reports `DISCARDED`, and normal edit/install methods
reject that state.

## Consequences

Discarded content remains in local backups and exports. Recovery requires explicit
archive maintenance and is not part of normal Desk use. No automatic purging is
provided.
