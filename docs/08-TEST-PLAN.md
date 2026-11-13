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
- 'l' typed in INSERT mode inserts literal 'l'; R in NORMAL mode inserts a typed link with no suggestions; Esc changes modes and cancels a literal prompt without mutation.
- Deliberate Main navigation by next/prev/exact address only; no follow-link keystroke; no full-text-search field/backlink view.
- Input resize, 80×24 and 120×35, focus, enter/escape, unknown shortcut, terminal mode restored on exception.
- Unicode combining sequences and two-column glyph widths avoid cursor/content corruption.
- Dialog contents not injected into terminal control codes.

## Example acceptance transcript

~~~text
open clean.db
D -> N -> write "Horses" -> Esc -> :w -> Enter -> Esc -> Q
reopen clean.db -> D -> draft exists without address
D -> selected -> F -> 3118/1 -> Enter -> installed
D -> N -> type "Mules l" -> Esc -> R -> "3118/2" -> Enter -> :w -> Enter
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

## Runtime compatibility gate

Assert `java -version` is 25 and the generated application runs on JDK 25. Use `maven.compiler.release=25` and fail builds on an older JDK; do not silently compile for Java 21. Keep Surefire/Failsafe/JaCoCo and any bytecode scanners compatible with Java 25.

The process-driven acceptance suite launches separate JVMs against a temporary SQLite archive. It queues Lanterna keystrokes through the real terminal event loop at 80×24 to create a Unicode Maincard, retain a literal reference, reject a duplicate address, author an Index entry, import local BibTeX, and add a BibNote. A separate process halts after a committed write while a WAL is present; CLI backup/export/import then run before a reopened 120×35 session checks the recovered state. All data is fabricated and the test requires no network.

The graph projection contract test checks deterministic repeat export, stable namespaced IDs, duplicate directed reference occurrences, unresolved literals, run provenance, authored Index/Bib relationships, full event records, distinct `derived=true` filing adjacency and absence of inferred `PARENT_OF` edges. It also confirms the canonical archive remains healthy and refuses export overwrite. No graph view or traversal shortcut is added to the TUI.

## CI gates

GitHub Actions PR + push: build/test on Temurin JDK 25 Linux with Maven in batch mode; no network-dependent tests; unit+integration; Spotless/Checkstyle (after setup), OWASP dependency checks on scheduled release path, static docs/hook validation, packaged runnable artifact and smoke test. Full CI is source of truth. Local hooks are a faster subset.

## Definition of done

For each issue: explicit tests above mapped to a short acceptance checklist; green CI, documented command examples and negative test. For release: full workflows above on supported terminals, backups restored twice (same app version and upgrade), documentation matches CLI and keymap, no uncaught data loss or accidental UI affordances, reproducible tagged build with checksums.
