CREATE TABLE index_promotions (
  source_entry_id TEXT PRIMARY KEY REFERENCES index_entries(id) ON DELETE RESTRICT,
  keyterm_card_id TEXT NOT NULL REFERENCES index_keyterm_cards(card_id) ON DELETE RESTRICT,
  promoted_entry_id TEXT NOT NULL UNIQUE REFERENCES keyterm_entries(id) ON DELETE RESTRICT,
  promoted_at TEXT NOT NULL
);

CREATE TRIGGER index_entries_main_target_guard
BEFORE INSERT ON index_entries
WHEN NEW.target_main_card_id IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM cards WHERE id=NEW.target_main_card_id AND kind='MAIN' AND state='INSTALLED'
)
BEGIN
  SELECT RAISE(ABORT, 'Index Main pointer target must be an installed Main card');
END;

CREATE TRIGGER index_entries_keyterm_target_guard
BEFORE INSERT ON index_entries
WHEN NEW.target_index_card_id IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM index_keyterm_cards WHERE card_id=NEW.target_index_card_id
)
BEGIN
  SELECT RAISE(ABORT, 'Index redirect target must be a dedicated keyterm card');
END;

CREATE TRIGGER keyterm_entries_main_target_guard
BEFORE INSERT ON keyterm_entries
WHEN NEW.target_main_card_id IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM cards WHERE id=NEW.target_main_card_id AND kind='MAIN' AND state='INSTALLED'
)
BEGIN
  SELECT RAISE(ABORT, 'Index Main pointer target must be an installed Main card');
END;

CREATE TRIGGER keyterm_entries_keyterm_target_guard
BEFORE INSERT ON keyterm_entries
WHEN NEW.target_index_card_id IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM index_keyterm_cards WHERE card_id=NEW.target_index_card_id
)
BEGIN
  SELECT RAISE(ABORT, 'Index redirect target must be a dedicated keyterm card');
END;

CREATE TRIGGER index_entries_update_guard
BEFORE UPDATE ON index_entries
BEGIN
  SELECT RAISE(ABORT, 'authored Index entries are append-only');
END;

CREATE TRIGGER index_entries_delete_guard
BEFORE DELETE ON index_entries
BEGIN
  SELECT RAISE(ABORT, 'authored Index entries are append-only');
END;

CREATE TRIGGER keyterm_entries_update_guard
BEFORE UPDATE ON keyterm_entries
BEGIN
  SELECT RAISE(ABORT, 'authored keyterm entries are append-only');
END;

CREATE TRIGGER keyterm_entries_delete_guard
BEFORE DELETE ON keyterm_entries
BEGIN
  SELECT RAISE(ABORT, 'authored keyterm entries are append-only');
END;

CREATE TRIGGER index_promotions_insert_guard
BEFORE INSERT ON index_promotions
WHEN NOT EXISTS (
  SELECT 1 FROM index_entries source
  JOIN index_keyterm_cards card ON card.card_id=NEW.keyterm_card_id
  JOIN keyterm_entries target ON target.id=NEW.promoted_entry_id
  WHERE source.id=NEW.source_entry_id
    AND source.authored_text=card.keyterm
    AND target.keyterm_card_id=NEW.keyterm_card_id
    AND target.authored_text=source.authored_text
    AND target.target_kind=source.target_kind
    AND target.target_literal=source.target_literal
    AND target.target_main_card_id IS source.target_main_card_id
    AND target.target_index_card_id IS source.target_index_card_id
)
BEGIN
  SELECT RAISE(ABORT, 'promotion must preserve the authored Index entry and target');
END;

CREATE TRIGGER index_promotions_update_guard
BEFORE UPDATE ON index_promotions
BEGIN
  SELECT RAISE(ABORT, 'Index promotion history is append-only');
END;

CREATE TRIGGER index_promotions_delete_guard
BEFORE DELETE ON index_promotions
BEGIN
  SELECT RAISE(ABORT, 'Index promotion history is append-only');
END;
