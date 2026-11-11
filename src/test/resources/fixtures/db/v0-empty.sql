CREATE TABLE schema_migrations (
  version INTEGER PRIMARY KEY,
  description TEXT NOT NULL,
  checksum_sha256 TEXT NOT NULL,
  applied_at TEXT NOT NULL
);
INSERT INTO schema_migrations(version,description,checksum_sha256,applied_at)
VALUES(0,'pre-implementation fixture','legacy-v0','2026-11-10T12:00:00Z');
CREATE TABLE legacy_fixture (note TEXT NOT NULL);
INSERT INTO legacy_fixture(note) VALUES('preserve existing data');
