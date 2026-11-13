# ADR 0001: SQLite is the only canonical store

Status: Accepted; implementation in progress — 2026-11-11.

## Context
The product is a durable, local, no-server archive. Although authored references form a multigraph, the interactive app primarily performs exact-address lookup, ordered drawer browsing, append-only notes, import, transactional filing and index inspection.

## Decision
Use one SQLite database as canonical storage through JDBC. Expose graph projections via versioned export for possible LadybugDB optimization later. Repository ports isolate physical storage.

## Consequences
Simple backup/migration, mature Java ecosystem, cross-platform single-file storage; recursive graph analytics may be slower than a specialized engine but are outside default UI. SQLite WAL sidecars can exist during execution. Graph projector/benchmark may be investigated only after core correctness and explicit comparison.
