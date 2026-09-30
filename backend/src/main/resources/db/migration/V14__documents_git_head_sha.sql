-- Verrou optimiste mode Git : HEAD commit attendu avant écriture (≠ contenu canonique).
ALTER TABLE documents ADD COLUMN IF NOT EXISTS git_head_sha TEXT;
