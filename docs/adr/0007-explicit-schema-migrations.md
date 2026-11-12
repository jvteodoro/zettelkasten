# ADR 0007: Explicit schema migration before opening older archives

Status: Accepted — 2026-11-11.

## Context

Automatically applying schema changes while opening an archive makes a normal
read or TUI launch a potentially irreversible maintenance action. A migration
can also fail partway through if the file or filesystem is damaged. The data
safety contract requires preview, backup, and explicit apply.

## Decision

Opening an existing archive validates its migration history and requires its
schema to match the application. It never migrates. The CLI
`migrate --dry-run` reads the current version and lists pending migrations
without changing the archive. `migrate --apply` creates a verified sibling
SQLite backup first, then applies the numbered migrations in one transaction.
New archive initialization still applies the complete current schema.

## Consequences

Older archives remain untouched until the owner explicitly applies a migration.
Users must run `migrate --dry-run` and `migrate --apply` before opening them in
the TUI. Backups are retained beside the source and are never overwritten.
