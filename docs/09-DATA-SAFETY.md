# Long-term data safety, portability and security

## Storage policy

Default a local data directory selected by the user, with a single canonical SQLite file. No automatic network access, cloud, user account or telemetry. SQLite in WAL mode may temporarily create sidecars; do NOT copy a live db with ordinary cp as if it were a complete backup.

On first init, refuse overwriting any existing file. Prefer POSIX 0700 for data directory and 0600 for DB file when supported; never silently broaden permissions.

## Maintenance commands (target contracts)

~~~sh
zettelkasten doctor --db FILE
zettelkasten backup --db FILE --out BACKUP.db
zettelkasten restore --from BACKUP.db --db NEWFILE
zettelkasten export --db FILE --out DIRECTORY/
zettelkasten import-archive --from DIRECTORY/ --db NEWFILE
zettelkasten migrate --db FILE --dry-run
zettelkasten migrate --db FILE --apply
~~~

These operations are implemented by the CLI. `backup` uses SQLite `VACUUM INTO`
and verifies integrity and foreign keys before moving a new output into place;
`restore` creates a consistent SQLite snapshot at a new destination. Both refuse
existing outputs. `export` writes the versioned JSONL archive; `import-archive`
validates the full manifest, checksums, UTF-8, row counts, schema, and columns
before creating a destination, and imports only to a new database. The imported
store is checked for integrity and foreign keys before success. `doctor` reports
integrity, FK, schema, and event-order checks. `migrate --dry-run` reads the
schema version without migrating; `migrate --apply` writes a sibling
`.pre-migration-vN.db` safety backup before applying pending migrations.

## Canonical portable archive

Export is readable without Java or SQLite: versioned JSONL text plus manifest containing byte checksums, schema versions and counts. Preserve raw BibTeX, human notes, Unicode literal links, IDs, indexes, relative link syntax, historical events and unresolved references. No hidden binary-only fields. Export deterministically for easy diff (except snapshot time when requested).

A forward-compat reader either preserves unknown future fields in a structured extension or rejects without mutation; never silently drops unknown fields.

## Security boundaries

- Terminal content may include untrusted ANSI escape sequences or control chars from imported BibTeX; escape for display, prevent terminal injection.
- SQL always parameterized; file paths normalized but do not follow untrusted symlinks blindly when writing backup/restore.
- BibTeX inputs are untrusted and parsed as data, never executed as TeX. The current parser adapter limits files to 16 MiB and brace nesting to 256, preserves the original UTF-8 file, and rejects malformed files atomically. Zotero local API opt-in, localhost only, no auth token in logs, disable by default.
- Journals and logs avoid raw card text/PII; analytics extracts are user-controlled local outputs.
- Unlimited or deeply nested input must be bounded to protect terminal and importer memory use, without truncating canonical user data.
- Normal UI cannot perform destructive maintenance; repair is explicit, validated, previewed, backed up and logged.
- Confirmed Desk draft discard retains card content and identity in a tombstone; it never deletes authored rows.

## Operational drill

1. Generate 1000 fixture cards, 500 references (including unresolved), Index and Bib entries, and 100 events.
2. Create WAL writes and backup while app is open via supported SQLite method.
3. Restore to a NEW location, run doctor, compare canonical exports.
4. Repeat restore against a newer schema fixture.
5. Simulate interrupted update, rejected checksum, invalid BibTeX and disk-full transaction; original database must remain consistent.

No claim of "durable for decades" is accepted without repeated tested export/restore and a documented format.
