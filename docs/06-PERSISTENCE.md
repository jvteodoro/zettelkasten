# Storage and SQL consistency specification (SQLite canonical)

The initial Java adapter uses checksum-verified numbered SQL resources. Draft body
runs and annotation runs share a non-null `run_containers` parent; `(container_id,
run_order)` is unique, so SQLite's nullable-`UNIQUE` behavior cannot weaken run
ordering. Installation, placement, address uniqueness and annotations are enforced
both in the repository transaction and with database constraints/triggers.
The stored placement sort key encodes arbitrary-precision numeric token lengths,
letter tokens, suffixes and path prefixes so SQL ordering agrees with the v1
semantic comparator.

Migration V002 adds append-only Index promotion provenance and guards optional stable
pointer IDs: a Main pointer must resolve to an installed Main card, and an Index
redirect must resolve to a dedicated keyterm card. Index target literals remain stored
even when those IDs are null. Promotion rows must point to a copied entry with the same
authored target and to a keyterm card named for the promoted source entry.

Migration V003 protects authored BibNotes, provider aliases, import batches, and
per-entry import diagnostics from update or deletion. BibTeX import records a
namespace, descriptive source name, whole-file SHA-256, raw file content, per-entry
outcome, and domain event in the same transaction as any accepted source records.
Rejected files keep their diagnostics but commit no new bibliographic sources.

Migration V004 projects existing typed Main card runs into normalized authored
references, preserving each run ID, literal, and reference type. It records one
backfill event per projected occurrence; normal draft, install, and annotation
operations maintain these projections in the same unit of work as their card rows.

Migration V005 adds `cards.discarded_at`. A confirmed Desk discard sets this timestamp
and appends `DraftDiscarded` in one transaction. The card and authored runs remain
available by stable UUID for audit and portable export; Desk listings omit tombstones.
Database triggers prevent a tombstoned card from receiving edits, runs, annotations,
or a placement.

Opening an existing archive validates its current migration checksums and refuses
an older schema without modifying it. `migrate --dry-run` reports the pending
versions; `migrate --apply` first writes a verified sibling SQLite backup and
then applies the numbered migrations transactionally. Initialization of a new
archive applies the complete current migration sequence.

## Persistence contract

Single embedded SQLite database (e.g. \`slipbox.db\`) is the **canonical store**. WAL may create transient \`-wal\` and \`-shm\` files; explain this to users. No application service or DB server. Support one writer application per file; second writer receives a clear lock error/read-only option. Use Xerial SQLite JDBC (pin version via Maven and verify compatible Java), Flyway community or hand-rolled numbered immutable migrations, never ORM auto-DDL. Implement reusable JDBC repositories with prepared statements.

On every connection: \`PRAGMA foreign_keys=ON\`, \`busy_timeout=5000\`; choose WAL or DELETE explicitly in one tested policy. Capture SQLite version and schema version in diagnostic output; do not alter synchronous/journal settings without tests.

## Proposed normalized schema (names illustrative; final migration authoritative)

~~~sql
CREATE TABLE slipbox_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE cards (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('MAIN','INDEX_LIST','INDEX_KEYTERM','BIB')),
  state TEXT NOT NULL CHECK (state IN ('DRAFT','INSTALLED')),
  revision INTEGER NOT NULL CHECK (revision>=0),
  created_at TEXT NOT NULL,
  installed_at TEXT,
  CHECK (kind <> 'MAIN' OR state='DRAFT' OR installed_at IS NOT NULL)
);
CREATE TABLE card_runs (
  id TEXT PRIMARY KEY,
  card_id TEXT NOT NULL REFERENCES cards(id),
  is_annotation INTEGER NOT NULL CHECK(is_annotation IN (0,1)),
  annotation_id TEXT,
  run_order INTEGER NOT NULL,
  run_kind TEXT NOT NULL,
  authored_literal TEXT NOT NULL,
  UNIQUE(card_id,annotation_id,run_order)
);
CREATE TABLE placements (
  card_id TEXT PRIMARY KEY REFERENCES cards(id),
  raw_address TEXT NOT NULL,
  canonical_address TEXT NOT NULL UNIQUE,
  scheme_id TEXT NOT NULL,
  scheme_version INTEGER NOT NULL,
  sort_key TEXT NOT NULL,
  predecessor_card_id TEXT REFERENCES cards(id),
  successor_card_id TEXT REFERENCES cards(id),
  installed_at TEXT NOT NULL
);
CREATE TABLE refs (
  id TEXT PRIMARY KEY,
  source_card_id TEXT NOT NULL REFERENCES cards(id),
  source_run_id TEXT NOT NULL REFERENCES card_runs(id),
  kind TEXT NOT NULL,
  authored_literal TEXT NOT NULL,
  target_literal TEXT NOT NULL,
  target_card_id TEXT REFERENCES cards(id),
  target_source_id TEXT,
  target_index_id TEXT,
  status TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX idx_refs_source ON refs(source_card_id);
CREATE INDEX idx_refs_target_card ON refs(target_card_id);
CREATE TABLE domain_events (
  global_seq INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL UNIQUE,
  aggregate_type TEXT NOT NULL,
  aggregate_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  schema_version INTEGER NOT NULL,
  happened_at TEXT NOT NULL,
  correlation_id TEXT,
  payload_json TEXT NOT NULL
);
~~~

**Important:** the illustrative DDL above is not the complete production schema. The final numbered migrations define normalized \`annotations\`, \`index_list_cards\`, \`index_entries\`, \`keyterm_cards\`, \`bibliographic_sources\`, \`source_external_ids\`, \`bibcards\`, \`bibnotes\`, \`import_batches\` and \`import_entries\`, proper FKs, an immutable body snapshot policy and all indexes. For \`card_runs\`, nullable \`annotation_id\` in a SQLite UNIQUE does not enforce uniqueness for NULL; use the nonnullable parent/container ID in production.

## Guarantees

- No row updates to installed original \`card_runs\` through normal app services; provide app-level guard and database trigger/check for immutability after installation. Appends have separate rows/parent.
- A successful install is one transaction: address conflict checks, placement, state transition, event insert and reference re-resolution commit together.
- Typed card, keyterm and external-reference runs are projected into `refs` in the same unit of work. V004 backfills those projections in V3 archives without changing the run literal, and records `ReferenceProjectionBackfilled` events.
- A failed install or import rolls back entirely and leaves original draft and references.
- DB stores raw address and canonical address, original link literals, raw imported metadata and schema versions.
- Every schema-changing migration is append-only numbered and checksummed; backup before upgrading an existing user file, no downgrade without explicit export/restore.
- All maintenance operations are idempotent or require an explicit confirmation; no automatic destructive repair.
- Retain signed 64-bit event sequence/monotonic global sequence for ordering rather than trusting timestamps alone.

## Read models & queries

Index \`placements(canonical_address)\`, \`placements(sort_key,card_id)\`, \`refs(source_card_id)\`, \`refs(target_card_id)\`, \`index_entries(index_card_id,entry_order)\`, \`bibnotes(bibcard_id,ordinal)\`, \`source_external_ids(provider,scope,external_id)\`.
- \`Next/Previous\` read only adjacent card using rank/order and projection; don't materialize semantic NEXT edge.
- \`findByAddress\` exact match after parse; never accept substring search in user UI.
- Graph adjacency query reads \`refs\` as authored multigraph; SELECT never automatically resolves or updates.
- Offline export may derive \`source_id,target_id,kind,time\` edges from refs, distinct from filing and Index pointers.

## File operations

- CLI init creates directory with secure user permissions where possible; no accidental overwrite.
- Use temporary database file for restore/import before atomic replacement. No cross-device rename assumption; verify integrity and backup after copy.
- Backup through SQLite Online Backup API or VACUUM INTO with tested connector, not raw live DB copy when WAL active.
- SQLite \`integrity_check\` and \`foreign_key_check\` required in doctor and post-restore tests.
- Read-only export transaction ensures manifest, entities and events share a consistent snapshot.
- Never expose a user-controlled path to raw SQL or shell command execution.

## Verification

- Two different connections concurrent install on same address -> one succeeds, one safe conflict.
- Crash/reopen after transaction commit preserves card and corresponding event.
- Kill before commit -> card remains DRAFT and no install event.
- Upgrade from every supported schema fixture across consecutive versions; reproduce checksums.
- Backup of WAL-mode open DB + restore must pass equality of canonical export hashes.
