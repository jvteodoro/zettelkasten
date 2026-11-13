CREATE TRIGGER bibnotes_update_guard
BEFORE UPDATE ON bibnotes
BEGIN
  SELECT RAISE(ABORT, 'authored BibNotes are append-only');
END;

CREATE TRIGGER bibnotes_delete_guard
BEFORE DELETE ON bibnotes
BEGIN
  SELECT RAISE(ABORT, 'authored BibNotes are append-only');
END;

CREATE TRIGGER source_external_ids_update_guard
BEFORE UPDATE ON source_external_ids
BEGIN
  SELECT RAISE(ABORT, 'source external identities are append-only');
END;

CREATE TRIGGER source_external_ids_delete_guard
BEFORE DELETE ON source_external_ids
BEGIN
  SELECT RAISE(ABORT, 'source external identities are append-only');
END;

CREATE TRIGGER import_batches_update_guard
BEFORE UPDATE ON import_batches
BEGIN
  SELECT RAISE(ABORT, 'import batch history is append-only');
END;

CREATE TRIGGER import_batches_delete_guard
BEFORE DELETE ON import_batches
BEGIN
  SELECT RAISE(ABORT, 'import batch history is append-only');
END;

CREATE TRIGGER import_entries_update_guard
BEFORE UPDATE ON import_entries
BEGIN
  SELECT RAISE(ABORT, 'import entry history is append-only');
END;

CREATE TRIGGER import_entries_delete_guard
BEFORE DELETE ON import_entries
BEGIN
  SELECT RAISE(ABORT, 'import entry history is append-only');
END;
