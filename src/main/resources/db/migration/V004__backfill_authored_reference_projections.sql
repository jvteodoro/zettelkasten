-- Preserve typed references authored before the application wrote normalized refs rows.
INSERT INTO refs(
  id,source_card_id,source_run_id,kind,authored_literal,target_literal,status,created_at
)
SELECT
  cr.id,
  rc.card_id,
  cr.id,
  CASE
    WHEN cr.run_kind = 'CARD_LINK' AND substr(cr.authored_literal,1,2) = '~/' THEN 'BRANCH'
    WHEN cr.run_kind = 'CARD_LINK' AND substr(cr.authored_literal,1,1) = '/' THEN 'STEM'
    WHEN cr.run_kind = 'CARD_LINK' THEN 'FULL'
    WHEN cr.run_kind = 'INDEX_KEYTERM_LINK' THEN 'INDEX_KEYTERM'
    WHEN cr.run_kind = 'EXTERNAL_REFERENCE' THEN 'BIBLIOGRAPHIC_SOURCE'
  END,
  cr.authored_literal,
  cr.authored_literal,
  'PENDING',
  rc.created_at
FROM card_runs cr
JOIN run_containers rc ON rc.id = cr.container_id
JOIN cards source_card ON source_card.id = rc.card_id AND source_card.kind = 'MAIN'
WHERE cr.run_kind <> 'TEXT'
  AND NOT EXISTS (
    SELECT 1 FROM refs existing WHERE existing.source_run_id = cr.id
  );
