# Antinet book traceability: source vs implementation

Source: Scott P. Scheper, *Antinet Zettelkasten* supplied project PDF. Page labels refer to printed pages in that copy, not generic web summaries. Requirements below paraphrase rather than reproduce substantive copyrighted material.

| Book section / pages | Supported insight | Product response | Status |
|---|---|---|---|
| Ch. 7, pp. 226–228 | Numeric-alpha addresses permit insertion between previously filed cards; 3118/2a between /2 and /3. | Manual fixed addressing; precise order tests. | Book-derived |
| Ch. 7, pp. 235–242 | Stemlinks, branchlinks, remotelinks, index keyterm links, external refs (b./r.). | Typed runs retaining literal and optional resolved target. | Book-derived categories; engineered storage |
| Ch. 8, pp. 260–265 | Branch/stem/leaf/vine analogy; notes have locations without theoretical rank. | Distinguish filing position from semantic graph; do not infer conceptual parenthood from prefix. | Book-derived distinction |
| Ch. 9, pp. 295–299 | Index is selective entry point, not exhaustive list of mentions. | Manual-only index entries and intentional retrieval. | Book-derived |
| Ch. 9, pp. 304–309 | Index list cards per letter, chronological entries, dedicated keyterm cards. | Two index card subtypes and manual promotion. | Book-derived |
| Ch. 11, pp. 341–347 | Main and index boxes, card creation, physical drawer metaphor. | Three-box TUI + manual filing. | Book-derived metaphor; engineered UI |
| Ch. 14, pp. 422–433 | Bibcard has citation/reading goal/overview and brief bibnotes with page/time references. | BibliographicSource separate from BibCard and BibNote. | Book-derived distinctions; engineered importer |
| Ch. 16, pp. 487–489 | Ideally identify installation location before composing; filing under/behind similar neighbor; manual Index lookup. | Desk allowed but optional browse-first workflow; no auto-file. | Book recommendation + user-specific Desk |
| Appendix C, pp. 583–587 | Digital approximation: short ~825 char notes; no destructive edit/delete; three top-level boxes; no app clipboard, tags, backlinks, content search. | Experimental card-size advisory; append-only installed notes; prohibitions. | Book-derived guidance, with explicit accessibility exceptions |

## Divergences and deliberate additions

- **Desk:** user specifically requires cards without addresses pending installation. The book warns that accumulating unplaced cards can be a burden, but this is an intentional user workflow.
- **Typing vs handwriting:** cannot recreate physical neuroimprinting with Java TUI; never make equivalence claim.
- **Typed link metadata:** user explicitly requires recording typed links for future graph analytics, without exposing auto-follow behavior.
- **SQLite/Java/UUID/event log:** engineering choices, not book prescriptions. Their job is to preserve data and provenance without simplifying cognition.
- **Soft 825-character warning:** Scheper estimates physical card capacity; strict universal cap could damage long Unicode or accessibility. Configurable cap chosen for v1.
- **Zotero/BibTeX:** digital bibliography adapters are user requirements, complementing bibcard methodology. Book itself also describes external reference manager ExRefs.
- **No OS clipboard interception:** application does not provide copy/paste, but cannot and should not secretly monitor terminal/OS clipboard.
- **Terminology:** Slash scheme v1 is a restricted subset for deterministic software; historic comma/period schemes require explicitly versioned adapters.
