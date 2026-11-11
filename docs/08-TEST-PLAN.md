# Test strategy, fixtures and complete release gate

## Quality model

Domain deterministic tests -> service tests -> SQLite integration (real temporary files) -> synthetic Lanterna key/UI tests -> process crash/reopen/backup end-to-end. All test fixtures contain fabricated content; do not commit real personal notes.

## Required suites

### Address/domain
- Parse every supported/unsupported numeric-alpha address, mixed-case normalizing rule, overflows, 9/10 ordering, deep stems, 3118/2a insertion, /1 and ~/ relative resolution, prefix missing, non-ASCII invalid address.
- Comparator properties: antisymmetry, transitivity, consistency and address uniqueness (property-based seeded tests).
- Draft created with no address, saved/reopened/editable; duplicate installation rolls back with draft unchanged.
- Installed card cannot be overwritten/deleted through normal API; append annotation succeeds and is immutable; identical multiple references are distinct.
- Reference to missing card remains PENDING, later installation resolves projection but original literal/span remains unchanged.

### Index
- A–Z dividers; out-of-alphabet entry insertion order remains unchanged; overflow; dedicated keyterm promotion retains provenance; manually typed target absent allowed.
- No implicit automatic tag/index entry from body words.
- Index view has no forward-click or search.

### Bib
- Import valid multi-entry BibTeX, Unicode, brace nesting, \`@string\`, \`crossref\`, unknown fields and unknown media; preserve raw.
- Repeat import idempotent; same keys across separate namespaces and duplicate DOI create reportable conflicts; user BibNotes unchanged after metadata reconciliation.
- External references with roman page numbers/timestamps; reference to pending source preserved.
- Fake Zotero provider contract test; normal app offline without Zotero.

### Persistence/recovery
- Fresh init/migrate; historical schema fixture upgrade; referential constraints on every connection; transaction rollback on injected failure.
- Crash with open WAL; backup online + restore + integrity/foreign_key checks + matching exported digest.
- Export/import canonical round trip including all Unicode literals, failed targets, annotation ordering, event schema revisions.
- Import invalid manifest/checksum refuses before mutating destination.
- Concurrent duplicate address install across processes/threads; graceful busy handling.

### TUI
- 'l' typed inserts literal 'l'; Ctrl+L inserts typed link with no suggestions; Esc cancels without mutation.
- Deliberate Main navigation by next/prev/exact address only; no follow-link keystroke; no full-text-search field/backlink view.
- Input resize, 80×24 and 120×35, focus, enter/escape, unknown shortcut, terminal mode restored on exception.
- Unicode combining sequences and two-column glyph widths avoid cursor/content corruption.
- Dialog contents not injected into terminal control codes.

## Example acceptance transcript

~~~text
open clean.db
D -> N -> write "Horses" -> Ctrl+S -> Q
reopen clean.db -> D -> draft exists without address
D -> selected -> F -> 3118/1 -> Enter -> installed
D -> N -> type "Mules l" -> Ctrl+L -> "3118/2" -> save
F -> 3118/2a -> installed
M -> G -> 3118/1 -> N -> 3118/2a (or next stored address)
M -> read Mules -> link is printed only
M -> click or Enter on "3118/2" -> no automatic jump
I -> create manual entry "Mules: 3118/2a"
B -> import fixtures/reading.bib -> source visible
B -> create bibnote "(p. 27) observation"
doctor -> integrity OK, no orphan reference/event
backup+restore -> same canonical state
~~~

Adapt next-neighbor expectation to whichever addresses exist in fixture; do not fake a 3118/2 card.

## CI gates

GitHub Actions PR + push: build/test on Java 21 Linux with Maven in batch mode; no network-dependent tests; unit+integration; Spotless/Checkstyle (after setup), OWASP dependency checks on scheduled release path, static docs/hook validation, packaged runnable artifact and smoke test. Full CI is source of truth. Local hooks are a faster subset.

## Definition of done

For each issue: explicit tests above mapped to a short acceptance checklist; green CI, documented command examples and negative test. For release: full workflows above on supported terminals, backups restored twice (same app version and upgrade), documentation matches CLI and keymap, no uncaught data loss or accidental UI affordances, reproducible tagged build with checksums.
