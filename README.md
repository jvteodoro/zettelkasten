# Zettelkasten — an analog-first, local-first terminal slip-box

A Java terminal application that reproduces the *working constraints* of a physical Antinet Zettelkasten while preserving machine-readable structure for long-term archival and optional future analytics.

**Status:** implementation in progress. The Java 25 Maven build, SQLite persistence layer, Desk/Main application services, and initial Lanterna shell/editor are in place; Index and Bibliography workflows and maintenance commands continue issue by issue. See [Implementation roadmap](docs/10-IMPLEMENTATION-ROADMAP.md).

## Product boundaries

- Three physically inspired boxes: **Main**, **Index**, and **Bibliography (Bib)**.
- A **Desk** for unwritten/unfinished or unfiled maincards. A card may be composed without an address; an installed maincard must have an address.
- User deliberately selects numeric-alpha addresses, writes internal references, files index entries, and discovers adjacent cards.
- No full-text search, tags, backlinks, semantic recommendations, click-to-follow links, automatic address assignment, or link target autocomplete in the normal user interface.
- Structured link spans and provenance are recorded internally for archival fidelity; no graph-analysis screen is exposed in the core product.
- Java 25 LTS, Lanterna terminal UI, embedded SQLite file, imported BibTeX, future opt-in Zotero integration.

## Current command-line scaffold

The runnable jar supports `--help`, `--version`, and `open --db FILE`. `open` requires an existing archive and never creates a database. Use `D` for Desk, `M` for Main, `I` for Index, `B` for Bib, `?` for help, and `Q` to quit. Draft editor shortcuts are listed in [`keymap.txt`](src/main/resources/keymap.txt). `init` and maintenance commands are still planned.

## Planned first use (after implementation)

The planned CLI is:

~~~sh
zettelkasten init --db ~/zettelkasten/slipbox.db
zettelkasten open --db ~/zettelkasten/slipbox.db
zettelkasten doctor --db ~/zettelkasten/slipbox.db
zettelkasten backup --db ~/zettelkasten/slipbox.db --out ~/backups/slipbox-2026-11-11.db
zettelkasten export --db ~/zettelkasten/slipbox.db --out ~/backups/slipbox-export/
~~~

The eventual application accepts a separate storage path; the only required persistent application database is an embedded local SQLite file (SQLite may create transient WAL/SHM sidecars while open).

## Documentation map

- [Product contract](docs/00-PRODUCT-CONTRACT.md)
- [Architecture and package boundaries](docs/01-ARCHITECTURE.md)
- [Domain model and workflows](docs/02-DOMAIN.md)
- [Numeric-alpha addresses and filing](docs/03-ADDRESSING.md)
- [Terminal UI and editor](docs/04-TUI.md)
- [Index, BibTeX and Zotero](docs/05-INDEX-BIBLIOGRAPHY.md)
- [Schema, migration, transaction and storage](docs/06-PERSISTENCE.md)
- [Event journal and analytics extraction](docs/07-EVENTS-ANALYTICS.md)
- [Acceptance and test plan](docs/08-TEST-PLAN.md)
- [Security, portability and backup](docs/09-DATA-SAFETY.md)
- [Implementation order and issue map](docs/10-IMPLEMENTATION-ROADMAP.md)
- [Developer operations and Git hooks](docs/11-DEVELOPER-OPERATIONS.md)
- [Book-to-requirement traceability](docs/12-BOOK-TRACEABILITY.md)
- [Architectural decisions](docs/adr/)
- [Instructions for coding agents](AGENTS.md)

## Source and interpretation

Primary methodological reference: Scott P. Scheper, *Antinet Zettelkasten*, supplied as the project research source. Its method, not its copyrighted text/images, informs these requirements. Appendix C, pp. 583–587, describes constraints for a hypothetical digital Antinet. We separate **book-derived behavior** from **our engineering decisions** explicitly in the traceability document.

This is an **intentional digital experiment**. We do **not** claim that keyboard typing is cognitively equivalent to handwriting.

## Development

Install **JDK 25** (Java 21 is intentionally rejected) and Maven 3.9+. Then run:

~~~sh
mvn -B verify
bash scripts/install-git-hooks.sh
java -jar target/zettelkasten-0.1.0-SNAPSHOT-all.jar --help
~~~

See [Developer operations](docs/11-DEVELOPER-OPERATIONS.md). Hooks are local convenience checks; GitHub Actions is the authoritative CI gate.

License: MIT.
