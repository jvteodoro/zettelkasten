CREATE TABLE IF NOT EXISTS schema_migrations (
  version INTEGER PRIMARY KEY,
  description TEXT NOT NULL,
  checksum_sha256 TEXT NOT NULL,
  applied_at TEXT NOT NULL
);

CREATE TABLE cards (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('MAIN','INDEX_LIST','INDEX_KEYTERM','BIB')),
  state TEXT NOT NULL CHECK (state IN ('DRAFT','INSTALLED')),
  revision INTEGER NOT NULL CHECK (revision >= 0),
  created_at TEXT NOT NULL,
  installed_at TEXT,
  installed_body_revision INTEGER,
  CHECK (kind <> 'MAIN' OR (state = 'DRAFT' AND installed_at IS NULL AND installed_body_revision IS NULL)
      OR (state = 'INSTALLED' AND installed_at IS NOT NULL AND installed_body_revision IS NOT NULL))
);

CREATE TABLE run_containers (
  id TEXT PRIMARY KEY,
  card_id TEXT NOT NULL REFERENCES cards(id) ON DELETE CASCADE,
  container_kind TEXT NOT NULL CHECK (container_kind IN ('ORIGINAL','ANNOTATION')),
  ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
  created_at TEXT NOT NULL,
  UNIQUE (card_id, container_kind, ordinal)
);
CREATE UNIQUE INDEX one_original_body_per_card
  ON run_containers(card_id) WHERE container_kind = 'ORIGINAL';

CREATE TABLE card_runs (
  id TEXT PRIMARY KEY,
  container_id TEXT NOT NULL REFERENCES run_containers(id) ON DELETE RESTRICT,
  run_order INTEGER NOT NULL CHECK (run_order >= 0),
  run_kind TEXT NOT NULL CHECK (run_kind IN ('TEXT','CARD_LINK','INDEX_KEYTERM_LINK','EXTERNAL_REFERENCE')),
  authored_literal TEXT NOT NULL,
  UNIQUE (container_id, run_order)
);

CREATE TABLE placements (
  card_id TEXT PRIMARY KEY REFERENCES cards(id) ON DELETE RESTRICT,
  raw_address TEXT NOT NULL,
  canonical_address TEXT NOT NULL UNIQUE,
  scheme_id TEXT NOT NULL,
  scheme_version INTEGER NOT NULL CHECK (scheme_version > 0),
  sort_key TEXT NOT NULL,
  predecessor_card_id TEXT REFERENCES cards(id) ON DELETE RESTRICT,
  successor_card_id TEXT REFERENCES cards(id) ON DELETE RESTRICT,
  installed_at TEXT NOT NULL,
  CHECK (predecessor_card_id IS NULL OR predecessor_card_id <> card_id),
  CHECK (successor_card_id IS NULL OR successor_card_id <> card_id),
  CHECK (predecessor_card_id IS NULL OR successor_card_id IS NULL OR predecessor_card_id <> successor_card_id)
);
CREATE INDEX placements_sort_order ON placements(sort_key, card_id);

CREATE TABLE bibliographic_sources (
  id TEXT PRIMARY KEY,
  source_type TEXT NOT NULL,
  title TEXT NOT NULL,
  creators_text TEXT NOT NULL,
  publication_year TEXT,
  container_title TEXT,
  publisher TEXT,
  edition TEXT,
  volume TEXT,
  issue TEXT,
  pages TEXT,
  doi TEXT,
  isbn TEXT,
  url TEXT,
  raw_record TEXT NOT NULL,
  metadata_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE source_external_ids (
  id TEXT PRIMARY KEY,
  source_id TEXT NOT NULL REFERENCES bibliographic_sources(id) ON DELETE RESTRICT,
  provider TEXT NOT NULL,
  scope TEXT NOT NULL,
  external_id TEXT NOT NULL,
  UNIQUE (provider, scope, external_id)
);
CREATE INDEX source_external_ids_source ON source_external_ids(source_id);

CREATE TABLE bibliographic_source_fields (
  source_id TEXT NOT NULL REFERENCES bibliographic_sources(id) ON DELETE RESTRICT,
  field_order INTEGER NOT NULL CHECK (field_order >= 0),
  field_name TEXT NOT NULL,
  field_value TEXT NOT NULL,
  PRIMARY KEY (source_id, field_order),
  UNIQUE (source_id, field_name)
);

CREATE TABLE index_list_cards (
  card_id TEXT PRIMARY KEY REFERENCES cards(id) ON DELETE RESTRICT,
  divider TEXT NOT NULL CHECK (length(divider) = 1 AND divider GLOB '[A-Z]'),
  overflow_number INTEGER NOT NULL CHECK (overflow_number > 0),
  UNIQUE (divider, overflow_number)
);

CREATE TABLE index_keyterm_cards (
  card_id TEXT PRIMARY KEY REFERENCES cards(id) ON DELETE RESTRICT,
  keyterm TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX index_keyterm_cards_alphabetical ON index_keyterm_cards(keyterm COLLATE NOCASE, card_id);

CREATE TABLE index_entries (
  id TEXT PRIMARY KEY,
  index_card_id TEXT NOT NULL REFERENCES index_list_cards(card_id) ON DELETE RESTRICT,
  entry_order INTEGER NOT NULL CHECK (entry_order >= 0),
  authored_text TEXT NOT NULL,
  target_kind TEXT NOT NULL CHECK (target_kind IN ('MAIN_ADDRESS','INDEX_KEYTERM','SEE_ALSO')),
  target_literal TEXT NOT NULL,
  target_main_card_id TEXT REFERENCES cards(id) ON DELETE RESTRICT,
  target_index_card_id TEXT REFERENCES cards(id) ON DELETE RESTRICT,
  created_at TEXT NOT NULL,
  UNIQUE (index_card_id, entry_order),
  CHECK ((target_kind = 'MAIN_ADDRESS' AND target_index_card_id IS NULL)
      OR (target_kind = 'INDEX_KEYTERM' AND target_main_card_id IS NULL)
      OR target_kind = 'SEE_ALSO')
);

CREATE TABLE keyterm_entries (
  id TEXT PRIMARY KEY,
  keyterm_card_id TEXT NOT NULL REFERENCES index_keyterm_cards(card_id) ON DELETE RESTRICT,
  entry_order INTEGER NOT NULL CHECK (entry_order >= 0),
  authored_text TEXT NOT NULL,
  target_kind TEXT NOT NULL CHECK (target_kind IN ('MAIN_ADDRESS','INDEX_KEYTERM','SEE_ALSO')),
  target_literal TEXT NOT NULL,
  target_main_card_id TEXT REFERENCES cards(id) ON DELETE RESTRICT,
  target_index_card_id TEXT REFERENCES cards(id) ON DELETE RESTRICT,
  created_at TEXT NOT NULL,
  UNIQUE (keyterm_card_id, entry_order),
  CHECK ((target_kind = 'MAIN_ADDRESS' AND target_index_card_id IS NULL)
      OR (target_kind = 'INDEX_KEYTERM' AND target_main_card_id IS NULL)
      OR target_kind = 'SEE_ALSO')
);

CREATE TABLE bibcards (
  card_id TEXT PRIMARY KEY REFERENCES cards(id) ON DELETE RESTRICT,
  source_id TEXT NOT NULL REFERENCES bibliographic_sources(id) ON DELETE RESTRICT,
  reading_goal TEXT NOT NULL,
  source_overview TEXT NOT NULL
);

CREATE TABLE bibnotes (
  id TEXT PRIMARY KEY,
  bibcard_id TEXT NOT NULL REFERENCES bibcards(card_id) ON DELETE RESTRICT,
  ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
  locator_type TEXT NOT NULL,
  locator_literal TEXT NOT NULL,
  authored_text TEXT NOT NULL,
  created_at TEXT NOT NULL,
  derived_main_card_id TEXT REFERENCES cards(id) ON DELETE SET NULL,
  UNIQUE (bibcard_id, ordinal)
);

CREATE TABLE import_batches (
  id TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  namespace TEXT NOT NULL,
  source_name TEXT NOT NULL,
  content_sha256 TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('DRY_RUN','APPLIED','REJECTED')),
  imported_at TEXT NOT NULL
);

CREATE TABLE import_entries (
  id TEXT PRIMARY KEY,
  batch_id TEXT NOT NULL REFERENCES import_batches(id) ON DELETE RESTRICT,
  source_id TEXT REFERENCES bibliographic_sources(id) ON DELETE RESTRICT,
  external_key TEXT NOT NULL,
  raw_record TEXT NOT NULL,
  outcome TEXT NOT NULL CHECK (outcome IN ('NEW','CHANGED','CONFLICT','UNCHANGED','ERROR')),
  diagnostic TEXT,
  UNIQUE (batch_id, external_key)
);

CREATE TABLE refs (
  sequence INTEGER PRIMARY KEY AUTOINCREMENT,
  id TEXT NOT NULL UNIQUE,
  source_card_id TEXT NOT NULL REFERENCES cards(id) ON DELETE RESTRICT,
  source_run_id TEXT NOT NULL REFERENCES card_runs(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('STEM','BRANCH','FULL','INDEX_KEYTERM','BIBLIOGRAPHIC_SOURCE')),
  authored_literal TEXT NOT NULL,
  target_literal TEXT NOT NULL,
  target_card_id TEXT REFERENCES cards(id) ON DELETE RESTRICT,
  target_source_id TEXT REFERENCES bibliographic_sources(id) ON DELETE RESTRICT,
  target_index_id TEXT REFERENCES cards(id) ON DELETE RESTRICT,
  status TEXT NOT NULL CHECK (status IN ('PENDING','RESOLVED','AMBIGUOUS','BROKEN')),
  created_at TEXT NOT NULL,
  CHECK ((kind IN ('STEM','BRANCH','FULL') AND target_source_id IS NULL AND target_index_id IS NULL)
      OR (kind = 'INDEX_KEYTERM' AND target_card_id IS NULL AND target_source_id IS NULL)
      OR (kind = 'BIBLIOGRAPHIC_SOURCE' AND target_card_id IS NULL AND target_index_id IS NULL)),
  CHECK ((status = 'RESOLVED' AND
          ((kind IN ('STEM','BRANCH','FULL') AND target_card_id IS NOT NULL)
          OR (kind = 'INDEX_KEYTERM' AND target_index_id IS NOT NULL)
          OR (kind = 'BIBLIOGRAPHIC_SOURCE' AND target_source_id IS NOT NULL)))
      OR (status IN ('PENDING','AMBIGUOUS') AND target_card_id IS NULL AND target_source_id IS NULL AND target_index_id IS NULL)
      OR status = 'BROKEN')
);
CREATE INDEX refs_source ON refs(source_card_id, sequence);
CREATE INDEX refs_target_card ON refs(target_card_id);
CREATE INDEX refs_target_source ON refs(target_source_id);
CREATE INDEX refs_target_index ON refs(target_index_id);

CREATE TABLE domain_events (
  global_seq INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL UNIQUE,
  aggregate_type TEXT NOT NULL,
  aggregate_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  schema_version INTEGER NOT NULL CHECK (schema_version > 0),
  happened_at TEXT NOT NULL,
  correlation_id TEXT,
  payload_json TEXT NOT NULL
);

CREATE TRIGGER card_runs_insert_guard
BEFORE INSERT ON card_runs
WHEN EXISTS (
  SELECT 1 FROM run_containers rc JOIN cards c ON c.id = rc.card_id
  WHERE rc.id = NEW.container_id AND
    ((rc.container_kind = 'ORIGINAL' AND c.state = 'INSTALLED')
      OR (rc.container_kind = 'ANNOTATION' AND c.state <> 'INSTALLED'))
)
BEGIN
  SELECT RAISE(ABORT, 'card run container is immutable or in the wrong lifecycle state');
END;

CREATE TRIGGER original_container_update_guard
BEFORE UPDATE ON run_containers
WHEN OLD.container_kind = 'ORIGINAL'
  AND EXISTS (SELECT 1 FROM cards WHERE id = OLD.card_id AND state = 'INSTALLED')
BEGIN
  SELECT RAISE(ABORT, 'installed original body container is immutable');
END;

CREATE TRIGGER original_container_delete_guard
BEFORE DELETE ON run_containers
WHEN OLD.container_kind = 'ORIGINAL'
  AND EXISTS (SELECT 1 FROM cards WHERE id = OLD.card_id AND state = 'INSTALLED')
BEGIN
  SELECT RAISE(ABORT, 'installed original body container is immutable');
END;

CREATE TRIGGER annotation_container_insert_guard
BEFORE INSERT ON run_containers
WHEN NEW.container_kind = 'ANNOTATION'
  AND NOT EXISTS (SELECT 1 FROM cards WHERE id = NEW.card_id AND state = 'INSTALLED')
BEGIN
  SELECT RAISE(ABORT, 'annotation requires an installed Main card');
END;

CREATE TRIGGER card_runs_original_update_guard
BEFORE UPDATE ON card_runs
WHEN EXISTS (
  SELECT 1 FROM run_containers rc JOIN cards c ON c.id = rc.card_id
  WHERE rc.id = OLD.container_id AND rc.container_kind = 'ORIGINAL' AND c.state = 'INSTALLED'
)
BEGIN
  SELECT RAISE(ABORT, 'installed original body is immutable');
END;

CREATE TRIGGER card_runs_original_delete_guard
BEFORE DELETE ON card_runs
WHEN EXISTS (
  SELECT 1 FROM run_containers rc JOIN cards c ON c.id = rc.card_id
  WHERE rc.id = OLD.container_id AND rc.container_kind = 'ORIGINAL' AND c.state = 'INSTALLED'
)
BEGIN
  SELECT RAISE(ABORT, 'installed original body is immutable');
END;

CREATE TRIGGER card_runs_annotation_update_guard
BEFORE UPDATE ON card_runs
WHEN EXISTS (
  SELECT 1 FROM run_containers rc WHERE rc.id = OLD.container_id AND rc.container_kind = 'ANNOTATION'
)
BEGIN
  SELECT RAISE(ABORT, 'annotation runs are append-only');
END;

CREATE TRIGGER card_runs_annotation_delete_guard
BEFORE DELETE ON card_runs
WHEN EXISTS (
  SELECT 1 FROM run_containers rc WHERE rc.id = OLD.container_id AND rc.container_kind = 'ANNOTATION'
)
BEGIN
  SELECT RAISE(ABORT, 'annotation runs are append-only');
END;

CREATE TRIGGER annotation_container_update_guard
BEFORE UPDATE ON run_containers
WHEN OLD.container_kind = 'ANNOTATION'
BEGIN
  SELECT RAISE(ABORT, 'annotations are append-only');
END;

CREATE TRIGGER annotation_container_delete_guard
BEFORE DELETE ON run_containers
WHEN OLD.container_kind = 'ANNOTATION'
BEGIN
  SELECT RAISE(ABORT, 'annotations are append-only');
END;

CREATE TRIGGER placement_card_state_guard
BEFORE INSERT ON placements
WHEN NOT EXISTS (SELECT 1 FROM cards WHERE id = NEW.card_id AND kind = 'MAIN' AND state = 'DRAFT')
BEGIN
  SELECT RAISE(ABORT, 'placement requires a Main draft');
END;

CREATE TRIGGER placement_installs_card
AFTER INSERT ON placements
BEGIN
  UPDATE cards SET state='INSTALLED', installed_at=NEW.installed_at, installed_body_revision=revision
  WHERE id=NEW.card_id AND kind='MAIN' AND state='DRAFT';
END;

CREATE TRIGGER cards_install_requires_placement
BEFORE UPDATE OF state ON cards
WHEN NEW.state='INSTALLED' AND OLD.state='DRAFT'
  AND NOT EXISTS (SELECT 1 FROM placements WHERE card_id=NEW.id)
BEGIN
  SELECT RAISE(ABORT, 'Main installation must create a placement');
END;

CREATE TRIGGER installed_cards_update_guard
BEFORE UPDATE ON cards
WHEN OLD.kind='MAIN' AND OLD.state='INSTALLED'
BEGIN
  SELECT RAISE(ABORT, 'installed Main card identity and state are immutable');
END;

CREATE TRIGGER installed_cards_delete_guard
BEFORE DELETE ON cards
WHEN OLD.kind='MAIN' AND OLD.state='INSTALLED'
BEGIN
  SELECT RAISE(ABORT, 'installed Main cards cannot be deleted');
END;

CREATE TRIGGER placements_update_guard
BEFORE UPDATE ON placements
BEGIN
  SELECT RAISE(ABORT, 'installed placements are immutable');
END;

CREATE TRIGGER placements_delete_guard
BEFORE DELETE ON placements
BEGIN
  SELECT RAISE(ABORT, 'installed placements are immutable');
END;

CREATE TRIGGER index_list_card_kind_guard
BEFORE INSERT ON index_list_cards
WHEN NOT EXISTS (SELECT 1 FROM cards WHERE id=NEW.card_id AND kind='INDEX_LIST')
BEGIN
  SELECT RAISE(ABORT, 'list Index entry requires an INDEX_LIST card');
END;

CREATE TRIGGER index_keyterm_card_kind_guard
BEFORE INSERT ON index_keyterm_cards
WHEN NOT EXISTS (SELECT 1 FROM cards WHERE id=NEW.card_id AND kind='INDEX_KEYTERM')
BEGIN
  SELECT RAISE(ABORT, 'keyterm entry requires an INDEX_KEYTERM card');
END;

CREATE TRIGGER bibcard_kind_guard
BEFORE INSERT ON bibcards
WHEN NOT EXISTS (SELECT 1 FROM cards WHERE id=NEW.card_id AND kind='BIB')
BEGIN
  SELECT RAISE(ABORT, 'BibCard requires a BIB card');
END;

CREATE TRIGGER placement_neighbor_state_guard
BEFORE INSERT ON placements
WHEN (NEW.predecessor_card_id IS NOT NULL AND NOT EXISTS
      (SELECT 1 FROM placements WHERE card_id = NEW.predecessor_card_id))
  OR (NEW.successor_card_id IS NOT NULL AND NOT EXISTS
      (SELECT 1 FROM placements WHERE card_id = NEW.successor_card_id))
BEGIN
  SELECT RAISE(ABORT, 'placement neighbor must already be installed');
END;

CREATE TRIGGER reference_source_run_kind_guard
BEFORE INSERT ON refs
WHEN NOT EXISTS (
  SELECT 1 FROM card_runs r JOIN run_containers c ON c.id = r.container_id
  WHERE r.id = NEW.source_run_id AND c.card_id = NEW.source_card_id AND
    ((NEW.kind IN ('STEM','BRANCH','FULL') AND r.run_kind = 'CARD_LINK')
      OR (NEW.kind = 'INDEX_KEYTERM' AND r.run_kind = 'INDEX_KEYTERM_LINK')
      OR (NEW.kind = 'BIBLIOGRAPHIC_SOURCE' AND r.run_kind = 'EXTERNAL_REFERENCE'))
)
BEGIN
  SELECT RAISE(ABORT, 'reference type does not match its authored source run');
END;

CREATE TRIGGER reference_target_kind_guard
BEFORE INSERT ON refs
WHEN (NEW.kind IN ('STEM','BRANCH','FULL') AND NEW.target_card_id IS NOT NULL AND
      NOT EXISTS (SELECT 1 FROM cards WHERE id = NEW.target_card_id AND kind = 'MAIN' AND state = 'INSTALLED'))
  OR (NEW.kind = 'INDEX_KEYTERM' AND NEW.target_index_id IS NOT NULL AND
      NOT EXISTS (SELECT 1 FROM index_keyterm_cards WHERE card_id = NEW.target_index_id))
BEGIN
  SELECT RAISE(ABORT, 'reference target has the wrong card kind');
END;

CREATE TRIGGER domain_events_update_guard
BEFORE UPDATE ON domain_events
BEGIN
  SELECT RAISE(ABORT, 'domain events are append-only');
END;

CREATE TRIGGER domain_events_delete_guard
BEFORE DELETE ON domain_events
BEGIN
  SELECT RAISE(ABORT, 'domain events are append-only');
END;

CREATE TRIGGER schema_migrations_update_guard
BEFORE UPDATE ON schema_migrations
BEGIN
  SELECT RAISE(ABORT, 'schema migration history is immutable');
END;

CREATE TRIGGER schema_migrations_delete_guard
BEFORE DELETE ON schema_migrations
BEGIN
  SELECT RAISE(ABORT, 'schema migration history is immutable');
END;
