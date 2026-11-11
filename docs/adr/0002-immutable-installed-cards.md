# ADR 0002: Draft edits vs installed append-only annotations

Status: Accepted (design, pending implementation) — 2026-11-11.

## Context
Physical Antinet cards constrain rewriting. Users still require a working Desk. Silent mutation would make longitudinal analytics and archival confidence unreliable.

## Decision
Draft bodies editable with revision checks. Installing freezes original body snapshot and manually assigned address. Later changes are append-only annotations/corrections with their own UUIDs and events. Explicit repair/migration is a privileged nonstandard workflow, never silent.

## Consequences
UI has dual modes; old ideas preserved; typo fixes append commentary rather than overwrite. Tests enforce invariants both at application and persistence boundary.
