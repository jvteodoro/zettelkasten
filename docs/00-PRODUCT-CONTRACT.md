# Product contract — digital analog Antinet

## Product hypothesis

A local terminal implementation can preserve the *intentional friction* of the analog Zettelkasten — address choosing, filing, index-based retrieval, proximity discovery, selective referencing — even though it cannot reproduce handwriting-related cognitive effects. The structured store may simultaneously preserve rich relationships for *separate, future* analytics.

## Roles and modes

One local human user; no accounts, server, sync, collaboration, cloud, AI services or administrative dashboard in v1. Two conceptual layers:

- **Analog workflow** (available to the human): Desk, Main, Index, Bib; writing, installing, reading, explicitly inserting and appending links, selective indexing, source-note creation.
- **Archive/maintenance** (separate CLI): integrity, backups, export/import, migration, repair-with-preview; analytics export, never in-core traversal/navigation.

An explicit mode switch must not smuggle automatic backlinks or semantic search into the default workflow.

## User journeys and acceptance

### UJ-01 Create on Desk
From Desk, create a blank maincard, write Portuguese/German/English Unicode, insert literal card reference by Ctrl+L, save. No address required. Card appears as unfiled; can resume and edit. Close/reopen app: draft persists.

### UJ-02 File in Main
Open Desk card; manually enter proposed numeric-alpha address; navigate existing Main/Index for context; choose installation. Reject an invalid/duplicate address without deleting draft. On successful transaction card becomes Installed, immutable identity, fixed address, canonical filing position and immutable initial body. Event logged. Reject destructive edits afterward; allow append-only annotation.

### UJ-03 Explore by adjacency
Open Main by entering an address or by browsing ordered physical neighbors. Navigate next/previous card or local stems, as if flipping cards; no click-through links and no automatic target lookup by cursor. Card links render as legible text. Ordering is deterministic and tested; no content relevance ranking.

### UJ-04 Index manually
Open letter dividers A..Z; list cards accumulate entries in insertion order. Create human-authored keyterm → one/multiple Main addresses; choose promoted dedicated keyterm card when useful. Opening a term displays its written pointers; user must manually navigate to the address in Main. No automatic indexing of mentions, autocomplete or global search.

### UJ-05 Bibliography
Import a UTF-8 BibTeX file; inspect source citations in Bib. Create independent bibcard per source with front-side reading goal/overview and back-side bibnotes keyed by page/timestamp locator. Link maincard ExRef to a bibliographic source and locator; retain literal presentation. Duplicate imports must not duplicate the same source silently nor destroy annotations.

### UJ-06 Recover
After normal exit and simulated abrupt termination, reopen consistent data. Create a consistent backup; restore and compare canonical export. Invalid operations leave no partially installed cards or disconnected mandatory event.

## Explicit prohibitions in main user UI

No full-text search, find-as-you-type for addresses, autocomplete, copy/paste command provided by application, clickable card link traversal, backlinks, tag browser, graph viewer, automated topic extraction, automatic keyterm creation, recommender, inferred semantic relation surfacing, automatic card addressing/renumbering, online telemetry or Zotero network call at startup.

A terminal emulator may supply native clipboard; application should not implement a clipboard workflow or promise to prevent OS-level clipboard operations. Respect input methods and screen readers as far as practical. Avoid global key hooks outside terminal.

## Book-derived vs engineered choices

- Three boxes, fixed numeric-alpha positions, index as deliberate entry points, stem/branch/remote/ExRef links, short cards, 825 approximate limit and post-install non-destructive behavior derive from Scheper; see docs/12-BOOK-TRACEABILITY.md.
- Digital Desk, UUID identities, SQLite, event journal, explicit state machine, install anchor metadata, richer provenance, append-only corrections, CLI diagnostics, and Zotero seams are **project decisions**, not claims about Luhmann.
- Digital interfaces do not reproduce physical handwriting. Document this limitation in user help and release notes.

## Release outcomes

R1: DB + address/domain invariants + Desk/Main usable end-to-end offline.
R2: Index + Bib + rich references usable end-to-end.
R3: hardening + disaster recovery + export + optional analysis extraction (not an analytics UI) + packaging/manuals.

## Not in scope v1

Multiuser/sync, browser/mobile interface, handwritten OCR, markdown plugins, AI, embeddings, web clipping, PDF viewer, full Zotero sync, network services, bidirectional citation updates, rich media attachments, arbitrary query console in the default app.
