# Terminal UI and editor state machine — normative UX specification

## Platform

Java 25 + Lanterna (ncurses-like terminal implementation, not JNA curses). Support ANSI terminals on Linux/macOS and sensible Windows terminal fallback where Lanterna supports it. 80×24 as reference size, dynamic reflow on resize, explicit small-terminal message below minimum; no unsafe ANSI injection from card content.

## Screens

\`DESK\`: enumerate uninstalled maincard drafts, create/open, save, discard with confirmation, install.
\`MAIN\`: browse canonical ordered cards; show address and body; next/previous, enter address manually to position, view annotations. No "follow link" action. Contextual neighbors are allowed.
\`INDEX\`: alphabetized dividers A–Z, list indexcards in sequential fill order, manually authored index entries (in creation order within list), dedicated keyterm cards alphabetized among dedicated cards, manual read and write.
\`BIB\`: sort by author/title alphabetically, open source citation/bibcard; create goal, overview, bibnotes, ExRefs and import source file via explicit command.
\`EDITOR\`: Vim-style INSERT/NORMAL modes; R/T/E in NORMAL mode enter literal card, keyterm, and ExRef prompts; :w saves, :q exits, and :wq saves and exits. Prompts accept raw input without suggestion, preview, autojump or target existence hints.
\`MAINTENANCE\`: avoid in-TUI advanced dashboard; CLI only for doctor/export/backup.

## Global navigation

At home, use J/K to select Desk, Main, Index, or Bib; L or Enter opens the selected box. H returns to a parent screen where applicable; ? opens help and Q quits with the unsaved draft prompt. The editor starts in INSERT mode. Esc switches to NORMAL mode, where H/J/K/L move the cursor and Vim-style commands work; I returns to INSERT mode. In NORMAL mode use R/T/E for literal reference prompts, :w to save, :q to exit, and :wq to save and exit. Unsaved changes require an explicit choice. Printable characters, including h/j/k/l, remain authored input in INSERT mode and literal prompts. The editor uses no Ctrl-key commands. Ctrl+C is handled safely and is not treated as data deletion. The context-specific bindings and help are documented together and covered by key binding tests.

When reading Main, J/K move to adjacent cards **without invoking a graph link traversal**. G prompts for an exact address and positions the Main drawer at that location (manual addressing is allowed). No typeahead or auto-complete. When user reads a link, it is ordinary printed text (or subtle pen-color equivalent), never a click target. An address in Index is a written pointer; user manually opens Main and types it.

## Editor

- Represent body as \`List<BodyRun>\` with \`TextRun\`, \`InternalCardLinkRun\`, \`IndexKeytermLinkRun\`, \`ExternalReferenceRun\`. Every run stable ID, visible authored literal, position. Avoid substring offsets as canonical persisted reference identity.
- Start in INSERT mode; Esc switches to NORMAL mode, I resumes INSERT, and a second Esc returns from the editor. In NORMAL mode H/J/K/L move the cursor; R/T/E enter literal prompts; :w saves, :q exits, and :wq saves and exits. Literal prompts return to the mode from which they were opened. No conflict between 'l' and typing 'l' in INSERT mode.
- Draft may be edited and corrected. Installed maincard opens read-only plus append-annotation editor (never direct overwrite).
- 825 Unicode code points is a documented *default experimental soft limit* based on Scheper Appendix C, not fixed byte quota. Warn at 800/825, offer new card or manual override for accessibility; no truncation or silent discard. Count visible Unicode code points accurately; width/display using grapheme segmentation if available. Configurable strict limit later via ADR.
- Structured link literal inserted at cursor and rendered as authored; hyperlink styling may differ but no clickable semantics.
- Never intercept terminal-wide clipboard access; no application Copy/Paste menu or command. A user can always type freely. No OS-level surveillance.
- Autosave draft on explicit safe checkpoints or user commands; transaction consistency before showing saved status.
- Raw terminal mode must restore on normal exit and exceptions; test fatal exception path.

## Screen example (illustrative)

~~~text
+-------------------------- ZETTELKASTEN -------------------------+
| J/K Select   H/L Move or back   L/Enter Open   ? Help   Q Quit  |
+------------------------------ Desk -----------------------------+
| Drafts: 3       [N] New    [Enter] Edit    [F] File [X] Discard |
| 1  Perception and conflict              unshelved              |
| 2  Deutsch's cooperative competition    unshelved              |
+-----------------------------------------------------------------+
| EDITOR [no address]                                             |
| Perceptions of incompatible objectives may...                  |
| See: 4214/2e/3a/1                                               |
| INSERT: type freely   Esc NORMAL                              |
+-----------------------------------------------------------------+
~~~

## TUI acceptance tests

- 'l' typed in INSERT mode emits literal 'l'; Esc enters NORMAL, where H/J/K/L move the cursor, I resumes typing, and another Esc exits the editor.
- R in NORMAL mode prompts for a literal and creates a typed link run without suggestions.
- Input + resize preserves text, cursor, lines, and no data loss; all terminal state is restored on failure.
- No search field appears; no link cursor navigation exists; no automatic suggestions appear.
- Link can point to nonexistent card and still save. Draft stemlink remains pending until installation.
- A duplicate/invalid address does not lose draft nor change current Main listing.
- X on the selected saved Desk draft opens a separate confirmation; Y tombstones it, while N/Esc keeps it.
- Index list ordering stays authored temporal order even when entered out-of-alphabet order.
- Screens satisfy narrow (80×24) and wide (120×35) smoke test fixtures.

## Current implementation slice

ZK-08/09 supply the responsive Lanterna shell, Desk draft editing and manual Main
filing. The install prompt shows the immediate physical neighbor addresses around
a fully parsed proposed address, without selecting or filling the address. Exact
Main lookups report a missing address without offering alternatives.
`open --db FILE` opens an existing SQLite archive; it does not initialize
one. Bib sources are browsed separately from their research BibCards; local
BibTeX files require a typed namespace, a reconciliation preview, and an
explicit apply action. The exact bindings are maintained in
`src/main/resources/keymap.txt` and shown by the `?` help screen. The editor uses
an 825-codepoint configurable soft limit, warns beginning 25 codepoints early,
and never truncates. R/T/E in NORMAL mode open literal prompts; lower-case `l` remains text in INSERT mode.

The Index screen moves across A–Z dividers with H/L, selects overflow list cards
and authored entries with J/K, opens explicitly numbered list cards in order,
and browses dedicated keyterm cards alphabetically.
On a card, `A` starts a form for authored entry text, target kind (`M`, `K`, or
`S`), and literal target. `P` promotes the selected list entry while retaining
the original. Entries and targets are printed text; Enter reports the literal
pointer and never opens Main. To follow an address manually, go to Main and use
`G` to type the exact address. No inverted index, suggestions, or clickable
references are available.

The Bib screen orders sources by creator and title. Opening a source shows
imported citation metadata and its scoped citation keys. A separate research
BibCard holds the user's reading goal and overview on its front and ordered
BibNotes on its back. Notes require an explicit locator kind, exact locator
text, and authored observation. Import preview classifies every record before
apply; rejected files commit no new sources. E in the Main editor's NORMAL mode
accepts a literal such as `b.paperkey, p. 27` and renders that same text. It
does not suggest source keys or navigate to a source.
