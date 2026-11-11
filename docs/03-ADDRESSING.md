# Numeric-alpha address grammar, placement, ordering — normative v1

The v1 ambiguity around zero and canonical equality is resolved by
[ADR 0005](adr/0005-address-zero-prefix-policy.md): a single `0` is valid, while
multi-digit numeric components with leading zeroes are rejected.

## Why

Addresses are fixed *locations*, not semantic rankings or hierarchical concept IDs. The analog author can file a new card between adjacent cards without changing existing addresses (Scheper, chapter 7, pp. 226–228, 263–265). Scheper's illustrative 3118/2a goes between 3118/2 and 3118/3. Prefix/suffix alone MUST NOT become inferred semantic PARENT_OF edges.

## Scheme selection

Use address scheme id \`ANTINET_SLASH_V1\`. This is an explicitly **project-defined restricted dialect**, not a universal parser for Luhmann's diverse historical commas/dots. Preserve raw form and scheme with every placement; future parsers are additive. In scheme v1:
- ASCII digits and lowercase ASCII letters; accept uppercase on input and canonicalize ASCII letters to lowercase while retaining original literal.
- Slash '/' joins non-empty segments. Each segment matches \`[0-9]+([a-z]+[0-9]*)?\` for typical 1, 1a, 1a2, 11, 27b. Initial segment must consist solely of decimal digits: 3118, 4214/1a/2, 3118/2a.
- Leading zero segments and zero-valued initial segments are allowed only if tests explicitly document the chosen canonical policy; **initial proposal**: reject leading zero multi-digit numeric components (except "0") to avoid duplicate canonical addresses. Do not silently reinterpret historical addresses.
- Whitespace at ends is rejected with an actionable message; spaces inside address rejected. Empty segments, double slash, trailing slash, punctuation, arbitrary Unicode in v1 rejected. Never silently normalize user-visible input other than ASCII case.
- Max length and depth validated with named limits (suggest <= 255 ASCII bytes and <= 32 segments), documented/configured in application, storage not truncated.

## Comparator: semantic tokens, then prefix

Raw lexicographic string sorting is wrong ("/10" vs "/9"). Compare segments as token sequences: numeric atom as arbitrary precision nonnegative integer, then alphabetic atom lexical by ASCII with ordering empty alpha before nonempty alpha; then numeric suffix if present (empty suffix before present suffix); compare segment-by-segment, shorter path before longer path. **But** that token comparator alone is not enough to infer physical order around branch points. The physical filing order is an independent explicit sort key.

Examples of expected *default suggested order*, where not overridden by explicit insertion anchors:

~~~text
3118/1 < 3118/2 < 3118/2a < 3118/3 < 3118/9 < 3118/10
4214/1 < 4214/1a < 4214/1a/1 < 4214/1b < 4214/2
~~~

The implementation must validate nested/number-alpha alternation cases in fixtures; if the proposed grammar/comparator fails on historical examples, open an ADR revision before storing production data. Do not infer from string prefix that 3118/2a is a child of 3118/2.

## Explicit placement and stable browsing

A Main card has immutable chosen canonical address and captured insertion context. For v1 the globally displayed ordering is the **address scheme comparator**; if the UI allows an explicit physical insertion overriding that order, persist a separate \`filing_position\` sortable key (e.g. fractional rank or sequence labels) and ensure no collateral address changes. Do not conflate an address comparator with a global NEXT graph edge: next/previous are derived from ordered storage or an explicit nonsemantic adjacency index, not user-authored semantic links.

Place a card in a requested address without requiring all its address prefixes to exist. Installation UI may show immediate predecessor/successor **because these are analog physical neighbors**; never recommend a new address automatically. The prompt may preview the cards immediately before and after a fully typed, valid, unoccupied proposed address. This is read-only context: the user's literal is never filled or changed, and a missing exact lookup offers no alternative address.

Persist insertion snapshot \`predecessor_card_id\`, \`successor_card_id\`, \`scheme_version\`, \`raw_address\`, \`canonical_address\`, \`installed_at\`. A historic snapshot must not be recomputed after subsequent insertions.

## Resolution of relative authored links

`AddressReference` retains the exact literal and classifies `/...` as STEM,
`~/...` as BRANCH, and a full address as FULL. It computes a relative target only
when a source placement is supplied; target existence and stable-ID resolution
remain a separate application operation.

For a card at \`4214/2e/3a\`, a typed \`/1\` (STEM) denotes \`4214/2e/3a/1\`; a \`~/1/1a\` (BRANCH) denotes \`4214/1/1a\` using configured top-level branch root \`4214\`; a full address \`1337/1b\` is REMOTE/FULL. These reflect the book's conventions but exact parser precedence is **our design**. Before installing a draft, keep relative links unresolved and retain literal.

Target may be absent; keep PENDING until explicit or background *storage-only* resolution; do not surface existence checks or clickable navigation to the user. Never auto-create a card because it was linked.

## Required fixtures

Put \`src/test/resources/fixtures/addresses/v1-valid.tsv\`, \`v1-invalid.tsv\`, \`v1-order.tsv\`, \`relative-links.tsv\`. Each row contains raw address, expected canonical or error, and source contextual address for relative resolution. Add property-based tests (deterministic seeded generation): antisymmetry, transitivity, uniqueness, comparator consistency with equality, insertion-between compatibility and parsing idempotence. Include 3118/2a, 4214/1a/1, 4214/1a/2, 4214/1b, /1, ~/1/1a, 4214/9 vs /10 and invalid surrogate edge cases.
