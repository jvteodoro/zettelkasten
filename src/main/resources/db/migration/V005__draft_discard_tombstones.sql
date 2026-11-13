ALTER TABLE cards ADD COLUMN discarded_at TEXT;

CREATE TRIGGER discarded_main_card_immutable
BEFORE UPDATE ON cards
WHEN OLD.kind='MAIN' AND OLD.discarded_at IS NOT NULL
BEGIN
  SELECT RAISE(ABORT, 'discarded Main card is immutable');
END;

CREATE TRIGGER discarded_main_card_no_runs
BEFORE INSERT ON card_runs
WHEN EXISTS (
  SELECT 1 FROM run_containers rc JOIN cards c ON c.id=rc.card_id
  WHERE rc.id=NEW.container_id AND c.discarded_at IS NOT NULL
)
BEGIN
  SELECT RAISE(ABORT, 'discarded Main card cannot receive runs');
END;

CREATE TRIGGER discarded_main_card_no_annotations
BEFORE INSERT ON run_containers
WHEN NEW.container_kind='ANNOTATION' AND EXISTS (
  SELECT 1 FROM cards c WHERE c.id=NEW.card_id AND c.discarded_at IS NOT NULL
)
BEGIN
  SELECT RAISE(ABORT, 'discarded Main card cannot receive annotations');
END;

CREATE TRIGGER discarded_main_card_no_placement
BEFORE INSERT ON placements
WHEN EXISTS (SELECT 1 FROM cards c WHERE c.id=NEW.card_id AND c.discarded_at IS NOT NULL)
BEGIN
  SELECT RAISE(ABORT, 'discarded Main card cannot be filed');
END;

CREATE TRIGGER discarded_main_card_no_delete
BEFORE DELETE ON cards
WHEN OLD.kind='MAIN' AND OLD.discarded_at IS NOT NULL
BEGIN
  SELECT RAISE(ABORT, 'discarded Main card tombstone cannot be deleted');
END;

CREATE TRIGGER discarded_main_card_runs_no_delete
BEFORE DELETE ON card_runs
WHEN EXISTS (
  SELECT 1 FROM run_containers rc JOIN cards c ON c.id=rc.card_id
  WHERE rc.id=OLD.container_id AND c.discarded_at IS NOT NULL
)
BEGIN
  SELECT RAISE(ABORT, 'discarded Main card runs cannot be deleted');
END;

CREATE TRIGGER discarded_main_card_containers_no_delete
BEFORE DELETE ON run_containers
WHEN EXISTS (SELECT 1 FROM cards c WHERE c.id=OLD.card_id AND c.discarded_at IS NOT NULL)
BEGIN
  SELECT RAISE(ABORT, 'discarded Main card containers cannot be deleted');
END;
