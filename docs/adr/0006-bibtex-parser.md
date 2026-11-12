# ADR 0006: Isolate the BibTeX parser

## Status

Accepted for the first offline BibTeX importer.

## Context

The importer must parse real BibTeX syntax, including nested braced values, string
macros, cross-references, Unicode, and unknown fields. A regular-expression parser
would not preserve these semantics. The proposed JBibTeX release was verified during
implementation: version 1.0.20 is published under the New BSD License and was released
in 2022. That release is not actively maintained, which is a material dependency risk.

## Decision

Use `org.jbibtex:jbibtex:1.0.20` only inside the infrastructure BibTeX adapter. Keep
the application importer dependent on parsed record values rather than JBibTeX types.
Preserve the original UTF-8 file alongside raw field values so parser formatting or
unknown syntax never becomes the only surviving copy. Keep conformance fixtures for
macros, cross-references, braces, Unicode, unknown fields, and malformed input.

## Consequences

- Purpose: full BibTeX parsing without network or runtime services.
- License: New BSD License.
- Version: pinned 1.0.20.
- Fallback: replace the adapter with a currently maintained Java parser that passes
  the same fixtures; until then, preserve raw input and reject unparseable files
  atomically. Do not silently fall back to regex extraction.
- The parser remains an implementation detail and can be replaced without changing
  domain records, storage, or import commands.
