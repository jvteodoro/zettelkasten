---
name: sqlite-persistence
description: Build durable SQLite schema, migrations, unit-of-work, backup and restore.
---
# sqlite-persistence

1. Read docs/06-PERSISTENCE.md, docs/09-DATA-SAFETY.md and ADR 0001.
2. SQLite is canonical; parameterized queries, FK ON per connection and transactionally persisted events.
3. Test rollback, concurrent installs, migration and crash/reopen with real temporary files.
4. Account for SQLite NULL-in-UNIQUE behavior, WAL sidecars, integrity/foreign_key checks.
5. Backup with proper online API/VACUUM INTO, not raw live file copy.
6. Preserve all raw references/imports and never silently repair or drop unknown fields.
