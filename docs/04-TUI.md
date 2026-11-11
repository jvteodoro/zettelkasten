# Terminal UI and editor state machine — normative UX specification

## Platform

Java 21 + Lanterna (ncurses-like terminal implementation, not JNA curses). Support ANSI terminals on Linux/macOS and sensible Windows terminal fallback where Lanterna supports it. 80×24 as reference size, dynamic reflow on resize, explicit small-terminal message below minimum; no unsafe ANSI injection from card content.

## Screens

\`DESK\`: enumerate uninstalled maincard drafts, create/open, save, discard with confirmation, install.
\`MAIN\`: browse canonical ordered cards; show address and body; next/previous, enter address manually to position, view annotations. No "follow link" action. Contextual neighbors are allowed.
\`INDEX\`: alphabetized dividers A–Z, list indexcards in sequential fill order, manually authored index entries (in creation order within list), dedicated keyterm cards alphabetized among dedicated cards, manual read and write.
\`BIB\`: sort by author/title alphabetically, open source citation/bibcard; create goal, overview, bibnotes, ExRefs and import source file via explicit command.
\`EDITOR\`: draft content with structured blocks; Ctrl+L enters literal card link dialog; Ctrl+K enters keyterm link dialog; Ctrl+E enters bibliographic ExRef dialog; all dialogs raw input without suggestion, preview, autojump or target existence hints.
\`MAINTENANCE\`: avoid in-TUI advanced dashboard; CLI only for doctor/export/backup.

## Global navigation

At home: D Desk, M Main, I Index, B Bib, ? help, Q quit with unsaved draft prompt. Within editor typing any printable char must enter literal text without mode confusion. Ctrl+S save draft; Ctrl+L link; Esc dialog cancel / back; Ctrl+C should be handled safely, not treated as data deletion. Key mapping and help documented together in one source file; key binding assertions in tests.

When reading Main, N/P move to adjacent cards **without invoking a graph link traversal**. G prompts for an exact address and positions the Main drawer at that location (manual addressing is allowed). No typeahead or auto-complete. When user reads a link, it is ordinary printed text (or subtle pen-color equivalent), never a click target. An address in Index is a written pointer; user manually opens Main and types it.

## Editor

- Represent body as \`List<BodyRun>\` with \`TextRun\`, \`InternalCardLinkRun\`, \`IndexKeytermLinkRun\`, \`ExternalReferenceRun\`. Every run stable ID, visible authored literal, position. Avoid substring offsets as canonical persisted reference identity.
- Keep mode \`INSERT_TEXT\` default; modal \`INPUT_LINK_LITERAL\`, \`INPUT_INDEX_LITERAL\`, \`INPUT_EXREF\`, \`SAVE\`/confirmation. No conflict between 'l' and typing 'l'.
- Draft may be edited and corrected. Installed maincard opens read-only plus append-annotation editor (never direct overwrite).
- 825 Unicode code points is a documented *default experimental soft limit* based on Scheper Appendix C, not fixed byte quota. Warn at 800/825, offer new card or manual override for accessibility; no truncation or silent discard. Count visible Unicode code points accurately; width/display using grapheme segmentation if available. Configurable strict limit later via ADR.
- Structured link literal inserted at cursor and rendered as authored; hyperlink styling may differ but no clickable semantics.
- Never intercept terminal-wide clipboard access; no application Copy/Paste menu or command. A user can always type freely. No OS-level surveillance.
- Autosave draft on explicit safe checkpoints or user commands; transaction consistency before showing saved status.
- Raw terminal mode must restore on normal exit and exceptions; test fatal exception path.

## Screen example (illustrative)

~~~text
+-------------------------- ZETTELKASTEN -------------------------+
| [D] Desk   [M] Main   [I] Index   [B] Bib                      |
+------------------------------ Desk -----------------------------+
| Drafts: 3       [N] New    [Enter] Edit    [F] File            |
| 1  Perception and conflict              unshelved              |
| 2  Deutsch's cooperative competition    unshelved              |
+-----------------------------------------------------------------+
| EDITOR [no address]                                             |
| Perceptions of incompatible objectives may...                  |
| See: 4214/2e/3a/1                                               |
| Ctrl+L reference   Ctrl+S Save   Esc Back                       |
+-----------------------------------------------------------------+
~~~

## TUI acceptance tests

- 'l' typed in normal editor emits literal 'l'; Ctrl+L prompts and creates a typed link run with exact printed address.
- Input + resize preserves text, cursor, lines, and no data loss; all terminal state is restored on failure.
- No search field appears; no link cursor navigation exists; no automatic suggestions appear.
- Link can point to nonexistent card and still save. Draft stemlink remains pending until installation.
- A duplicate/invalid address does not lose draft nor change current Main listing.
- Index list ordering stays authored temporal order even when entered out-of-alphabet order.
- Screens satisfy narrow (80×24) and wide (120×35) smoke test fixtures.
