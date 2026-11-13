# ADR 0008: Optional graph projection is a read-only export

Status: Accepted — 2026-11-11.

## Context

ZK-16 requires offline graph extraction while SQLite remains the sole writable authority. Canonical tables already contain intentional references, manual Index pointers, bibliographic associations, filing snapshots and the event journal.

## Decision

Provide `graph-export --db FILE --out DIRECTORY`, which reads the current SQLite archive and writes a versioned, deterministic `zettelkasten-graph-jsonl` directory. Namespaced UUIDs preserve stable reversible identities. Authored references and Index pointers preserve direction, multiplicity, target literal, resolution and source run/entry provenance. Index promotions retain both entry IDs. Bibliographic source associations and authored BibNotes are represented explicitly. Event envelopes are exported as event records. Filing predecessor/successor snapshots use the separate `FILED_ADJACENT_TO` namespace with `derived=true`; address prefixes never create semantic edges.

The export is derived, read-only and replaceable. It is not imported as another writable store and does not mutate the canonical schema. No graph UI or suggestion behavior is introduced.

## Consequences

Consumers can rebuild a graph from stable entity/edge IDs while retaining unresolved literals and event history. Export is deterministic and checksummed. We add no LadybugDB dependency: ZK-16 exports canonical rows and does not need graph traversal, so a backend benchmark would compare an unimplemented workload and add no evidence. A future measured traversal workload may justify a separate adapter experiment and ADR.
