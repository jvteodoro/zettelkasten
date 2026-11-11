# Agent implementation contract (GPT-6 Luna / Codex / other coding agents)

Read this file before writing code. Read the task's linked documentation and its dependencies before implementation.

## Mission

Implement an **analog-first digital Antinet**, not a generic PKM app. The user deliberately writes, files, and searches by manual exploration. Internal graph-capable metadata must remain invisible to normal use. All user data persists locally and is transportable without proprietary tooling.

## Mandatory source precedence

1. This file and the explicit linked GitHub issue acceptance criteria.
2. The design specifications in docs/00 through docs/12 and accepted ADRs.
3. Tests, fixtures, and existing code.
4. The book-derived notes in docs/12-BOOK-TRACEABILITY.md.
5. Reasonable implementation assumptions; record material assumptions in an ADR/issue instead of silently creating product behavior.

If specifications conflict, stop at the **minimal reversible behavior**, document the contradiction in the PR, and do not silently add a convenience feature that violates analog constraints.

## Non-negotiable product invariants

1. Main cards have immutable UUID identity; addresses are separately assigned exactly once on installation; there is no silent address reassignment.
2. A draft on the desk may have no address and is editable. An installed maincard has a unique address and is not subject to silent destructive editing. Explicit append-only addenda are allowed.
3. Treat filing order, address syntax, intentional references, and graph **inference** as distinct concepts. A textual address prefix alone must never imply a semantic PARENT_OF edge.
4. Preserve the literal printed reference, source span, type, original target literal, resolution status, and resolved stable target where applicable. Allow unresolved references.
5. Index references are deliberately authored and selective, not automatically generated from content.
6. BibTeX import does not automatically create handwritten-style bibnotes. External metadata never overwrites user-authored observations.
7. No user-facing full-text search, tag UI, backlink UI, click-to-follow links, graph explorer, semantic suggestions, address completion, or auto-filing.
8. Store meaningful domain events transactionally with entity changes; avoid recording keystrokes or unnecessary personal telemetry.
9. All business rules, schema changes, and exports need deterministic tests; no irreversible database change without migration and backup/restore coverage.
10. No network access or Zotero dependency during offline core startup.

## Required implementation architecture

- Java 25 LTS (JDK 25 required for build and runtime); Maven 3.9+; single-process modular monolith under org.zettelkasten. Set `maven.compiler.release=25`; do not silently downgrade to Java 21 or enable preview APIs without an ADR.
- Modules/packages: domain (card/address/placement/reference/index/bibliography/event), application (commands/queries/ports), infrastructure (sqlite/bibtex/zotero/export/clock/id), tui (Lanterna screen/editor/input/render), cli.
- Domain pure Java, no dependency on UI, JDBC, JSON parser, or Zotero DTOs.
- Application services own transactions through a UnitOfWork port; repositories are ports. SQLite adapter is the canonical storage engine. Any later Ladybug adapter/exporter must satisfy the same canonical contracts.
- Use prepared statements, JDBC transactions, SQLite FK enforcement, migration versioning, and indexes; no direct SQL inside screens.
- Use small classes with descriptive names; deterministic time and IDs injected for tests.

## Work protocol for every issue

1. Inspect baseline and relevant docs. State files to change and the invariant tested.
2. Write failing domain/integration tests or acceptance fixtures first where practical.
3. Implement only the issue's scope; avoid speculative features.
4. Execute mvn -B verify; for storage issues also run migration, reopen, integrity and backup smoke tests.
5. Update documentation/tests and record important design deviations in docs/adr/.
6. Provide PR summary with: linked issue, acceptance checklist, manual TUI steps, test evidence, migrations, compatibility risks and screenshots/terminal transcript where useful.
7. Keep issues/PRs small and ordered by docs/10-IMPLEMENTATION-ROADMAP.md; do not mark an issue complete without tests and evidence.
8. Never change source data or force-push/merge main as part of a routine implementation unless separately authorized.

## Definition of done

- Acceptance tests pass; CI green; tests for Unicode, empty input, duplicate addresses, transaction rollback and crash/reopen where applicable.
- No hidden navigation/auto-completion shortcuts in the TUI.
- Persistent data round-trips including literal references, source locators and imported raw metadata.
- No orphan or silently missing references after migration; data integrity constraints remain enabled.
- Document changes are synchronized with the code and the issue.

## Agent skills

Use the targeted skills under .agents/skills/ (each has SKILL.md):
- antinet-method: source-derived UX boundaries and traceability
- numeric-alpha-addresses: grammar, order and fixtures
- sqlite-persistence: DDL, migration, consistency, export
- terminal-tui: state machines, editor spans, focus and keyboard tests
- bibliography-integration: BibTeX, source identity and Zotero seam
- release-quality: CI, hooks, fuzz/property tests and recovery drills

Do not add dependencies without stating purpose, license, version and fallback.
