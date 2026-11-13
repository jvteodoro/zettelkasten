# ADR 0005: Slash address zero and canonical equality policy

Status: Accepted — 2026-11-11, before the first persistent schema migration.

## Context

The address contract leaves zero handling open and asks that leading-zero rules be
resolved before addresses are stored. Equality and comparator behavior must also
agree when users type ASCII uppercase letters that canonicalize to lowercase.

## Decision

- `ANTINET_SLASH_V1` permits the numeric value `0` as a component, including the
  initial segment, but rejects multi-digit numeric components beginning with `0`.
- The rule applies to both a segment's initial digits and its optional numeric
  suffix (for example, `1/02` and `1/a02` are invalid).
- ASCII uppercase letters are accepted and lowercased in the canonical value;
  the original literal remains stored separately.
- Address equality and ordering use canonical semantic tokens, so equal canonical
  addresses compare as zero and have equal hashes.
- A string prefix or address segment never creates a semantic parent relation.

## Consequences

Equivalent ASCII casing cannot produce duplicate placements. Zero remains
representable without allowing multiple padded spellings of the same numeric
token. Historic address syntaxes remain outside this scheme and require an
explicit future version.
