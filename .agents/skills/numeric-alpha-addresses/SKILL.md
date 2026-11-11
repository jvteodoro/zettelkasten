---
name: numeric-alpha-addresses
description: Implement the versioned numeric-alpha parser, order, placement and relative link fixtures.
---
# numeric-alpha-addresses

1. Read docs/03-ADDRESSING.md and ADR 0003.
2. Separate raw literal, canonical address, scheme/version, filing order and insertion-neighbor snapshot.
3. Property-test comparator laws, 9/10, 3118/2a and nested cards.
4. Support unresolved relative links before filing; preserve written literal.
5. Do not infer semantic PARENT_OF, create synthetic parent cards or auto-assign addresses.
6. Historic dot/comma syntaxes require explicit separate scheme.
