# Implementation roadmap and agent issue map

## Milestone order / dependency graph

The tracked GitHub issues use stable codes \`ZK-01\` through \`ZK-16\` (search issue titles by code). Work in sequence where dependencies require it. One PR per issue where practical; do not merge a later issue onto an unreviewed base.

~~~text
ZK-01 scaffold + CI/hooks
  |
  +-- ZK-02 domain model + typed body
  |    +-- ZK-03 address grammar/order
  |    +-- ZK-04 reference grammar/resolution
  |    +-- ZK-07 desk/main application commands
  |           +-- ZK-08 terminal shell/editor
  |                  +-- ZK-09 Main browser/install UX
  |
  +-- ZK-05 SQLite schema/repository/UoW + events
       +-- ZK-06 event journal/export schemas
       +-- ZK-07
       +-- ZK-10 Index domain/persistence + ZK-11 Index UI
       +-- ZK-12 Bib domain + importer + ZK-13 Bib UI
       +-- ZK-14 backup/import/export/doctor
       +-- ZK-15 end-to-end/regression/packaging
       +-- ZK-16 optional graph projection/compat seams
~~~

## Issue checklist (titles to create)

| Code | Implementation outcome | Dependencies |
|------|-------------------------|--------------|
| ZK-01 | Java 25 scaffold, Maven wrapper, quality gates, CI, hooks, keymap resource | none |
| ZK-02 | Pure domain entities, state machine, structured body runs | ZK-01 |
| ZK-03 | Address parser/comparator, persisted scheme, fixture/property tests | ZK-02 |
| ZK-04 | Internal/keyterm/external reference model; pending resolution | ZK-02, ZK-03 |
| ZK-05 | SQLite canonical schema, migrations, repositories, UoW, consistency | ZK-02 |
| ZK-06 | Atomic event journal, event versioning, provenance | ZK-05 |
| ZK-07 | Desk/Main services: create/edit/install/annotate | ZK-03,04,05,06 |
| ZK-08 | Lanterna navigation/editor/input state machine | ZK-07 |
| ZK-09 | Main ordered browsing, insertion dialog, manual addressing | ZK-08 |
| ZK-10 | Index list and dedicated cards + manual entry/redirect | ZK-05,06 |
| ZK-11 | Index TUI, 26 dividers and deliberate lookup | ZK-08,10 |
| ZK-12 | Bibliography entities, BibTeX parser/importer, Zotero port | ZK-05,06 |
| ZK-13 | Bib TUI, bibnotes, ExRefs + source references | ZK-08,12 |
| ZK-14 | Integrity, backups, portable export/import, migration drill | ZK-05,06,10,12 |
| ZK-15 | E2E terminal suite, packaging, docs, release checks | ZK-09,11,13,14 |
| ZK-16 | Optional offline graph projection and contract test, no UI | ZK-14 |

## Acceptance baseline for all issues

- No forbidden UX features per docs/00-PRODUCT-CONTRACT.md.
- Apply tests from docs/08-TEST-PLAN.md with traceable assertion names.
- Link new PR to a ZK issue; pass \`mvn -B verify\` and new CI gates; make repo start and read-only workflows offline.
- No repository changes outside the codebase permitted without review; no force push or merge to main during agent work.
- Migration changes must include old fixture -> upgrade -> export/reopen test.

## Definition of full implementation

User can launch packaged app, manage Desk/Main/Index/Bib for meaningful research work, store and later retrieve every card with deliberate browsing, import BibTeX, create bibnotes and source ExRefs, backup/restore, and run integrity diagnostics. All acceptance tests pass. Optional ZK-16 enables *data extraction* but never user-facing graph exploration. Zotero actual synchronization remains a separately scoped future integration; its port and persisted external IDs are mandatory now.

## Agent handoff

Start with AGENTS.md then docs in numbered order. Implement ZK-01 first. For each subsequent task, retrieve relevant docs and fix only issue-specified behavior. Record any change to grammar, storage or cognitive constraints in an ADR. Never infer a product feature because a library makes it easy.
