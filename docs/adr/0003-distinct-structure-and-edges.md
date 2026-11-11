# ADR 0003: Filing positions are not conceptual hierarchy

Status: Accepted (design, pending implementation) — 2026-11-11.

## Context
Antinet numeric-alpha addresses specify a location, not a conceptual ranking. User-authored references and physical proximity are different evidence.

## Decision
Store addresses, filing order and insertion neighbor snapshots separately from typed authored directed references. Never infer semantic PARENT_OF or reciprocal backlinks from string prefixes. Derived graph edges carry \`derived=true\` and a distinct namespace.

## Consequences
Analytics can distinguish intentions from derived geometry; data survives future graph engines without corrupt interpretation. More schema fields but better integrity.
