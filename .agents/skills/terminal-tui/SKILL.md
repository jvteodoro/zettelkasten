---
name: terminal-tui
description: Build Lanterna views, typed runs and editor keyboard state machine without modern PKM shortcuts.
---
# terminal-tui

1. Read docs/04-TUI.md and product contract.
2. Typing 'l' writes 'l', Ctrl+L opens literal reference dialog; no autocomplete or previews.
3. Main next/previous navigates physical ordering, never graph links.
4. Draft editable; installed original read-only, append notes separately.
5. Test resize, keyboard, 80x24, Unicode/grapheme width, terminal recovery on crash.
6. Escape untrusted ANSI and avoid clipboard interception.
