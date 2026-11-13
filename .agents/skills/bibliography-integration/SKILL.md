---
name: bibliography-integration
description: Implement faithful BibTeX, bibcards/bibnotes, ExRefs and future Zotero port.
---
# bibliography-integration

1. Read docs/05-INDEX-BIBLIOGRAPHY.md and book traceability chapter 14.
2. Source metadata is distinct from user-authored bibcard and notes.
3. Use a BibTeX parser, preserve raw unknown attributes, maintain citation aliases per namespace.
4. Idempotent dry-run imports; conflicts must not silently merge; authored notes never overwritten.
5. Preserve literal page/time/section locators. Zotero remains optional and offline by default.
