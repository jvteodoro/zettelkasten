# Zettelkasten — an analog-first, local-first terminal slip-box

A Java terminal application that preserves the working constraints of a physical Antinet Zettelkasten while keeping machine-readable structure available for archival and optional future analysis.

The core workflows are implemented: Desk drafts, deliberate Maincard installation, manually authored Index cards, local BibTeX import and authored BibCards/notes, and offline archive maintenance. See the [implementation roadmap](docs/10-IMPLEMENTATION-ROADMAP.md) for remaining work.

## Product boundaries

- Three physically inspired boxes: **Main**, **Index**, and **Bibliography (Bib)**, with a Desk for drafts.
- Drafts can be edited without an address. Installing a maincard assigns a unique address; installed content is not silently rewritten.
- The user deliberately chooses addresses, writes references, files index entries, and discovers adjacent cards.
- No full-text search, tags, backlinks, semantic recommendations, click-to-follow links, automatic address assignment, or link-target autocomplete in normal use.
- Reference spans and provenance are stored for archival fidelity. Graph analysis is not exposed in ordinary use.
- Data stays in a local SQLite archive. Core startup and CLI maintenance do not use the network.

## Requirements

JDK 25 is required for build and runtime. Maven 3.9+ is available through the pinned Maven wrapper (`./mvnw`); its first use may download the Maven distribution. The application itself runs offline.

## Run

~~~sh
./mvnw -B verify
java -jar target/zettelkasten-0.1.0-SNAPSHOT-all.jar --help
java -jar target/zettelkasten-0.1.0-SNAPSHOT-all.jar init --db ~/zettelkasten/slipbox.db
java -jar target/zettelkasten-0.1.0-SNAPSHOT-all.jar open --db ~/zettelkasten/slipbox.db
~~~

The jar name includes the Maven project version. Use `--version` to inspect the application version. `open` requires an existing archive and does not create one. In the terminal, use `D` for Desk, `M` for Main, `I` for Index, `B` for Bib, `?` for help, and `Q` to quit. Draft editor shortcuts are listed in [`keymap.txt`](src/main/resources/keymap.txt).

CLI maintenance commands are `doctor`, `backup`, `restore`, `export`, `import-archive`, and `migrate`; run `--help` for syntax. Restore and archive import write to a new destination. Migrations are explicit and create a safety backup before applying changes. SQLite can create temporary WAL/SHM sidecars while the archive is open.

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

Primary methodological reference: Scott P. Scheper, *Antinet Zettelkasten*, supplied as the project research source. We distinguish book-derived behavior from engineering decisions in the traceability document. This is an intentional digital experiment; it does not claim keyboard typing is cognitively equivalent to handwriting.

## Development

~~~sh
./mvnw -B verify
bash scripts/install-git-hooks.sh
java -jar target/zettelkasten-0.1.0-SNAPSHOT-all.jar --help
~~~

See [Developer operations](docs/11-DEVELOPER-OPERATIONS.md). Hooks are local convenience checks; GitHub Actions is the authoritative CI gate.

License: MIT.
