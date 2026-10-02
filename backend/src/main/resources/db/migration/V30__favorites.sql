-- Favoris : V1 créait déjà `favorites` (resource_type/resource_id).
-- Renomme les colonnes pour l'API (target_type/target_id) et ajoute l'index de liste.

ALTER TABLE favorites RENAME COLUMN resource_type TO target_type;
ALTER TABLE favorites RENAME COLUMN resource_id TO target_id;

ALTER TABLE favorites DROP CONSTRAINT IF EXISTS favorites_resource_type_check;
ALTER TABLE favorites DROP CONSTRAINT IF EXISTS favorites_target_type_check;
ALTER TABLE favorites ADD CONSTRAINT favorites_target_type_check
  CHECK (target_type IN ('document', 'space', 'folder'));

CREATE INDEX IF NOT EXISTS favorites_user_created_idx ON favorites (user_id, created_at DESC);
