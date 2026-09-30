-- Visibilité des pages (organisation | space | restricted) + défaut d'espace.
-- Documents existants → 'space' (aucun élargissement silencieux vers organisation).

ALTER TABLE spaces
    ADD COLUMN IF NOT EXISTS default_visibility TEXT NOT NULL DEFAULT 'organisation';

ALTER TABLE spaces
    DROP CONSTRAINT IF EXISTS spaces_default_visibility_check;

ALTER TABLE spaces
    ADD CONSTRAINT spaces_default_visibility_check
        CHECK (default_visibility IN ('organisation', 'space', 'restricted'));

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS visibility TEXT NOT NULL DEFAULT 'space';

ALTER TABLE documents
    DROP CONSTRAINT IF EXISTS documents_visibility_check;

ALTER TABLE documents
    ADD CONSTRAINT documents_visibility_check
        CHECK (visibility IN ('organisation', 'space', 'restricted'));

-- Sécurité : forcer les rows existantes à 'space' (DEFAULT ne s'applique qu'aux INSERT).
UPDATE documents SET visibility = 'space' WHERE visibility IS DISTINCT FROM 'space';

COMMENT ON COLUMN documents.visibility IS
    'organisation = user:* viewer ; space = héritage espace/dossier ; restricted = accès explicites + owners';
COMMENT ON COLUMN spaces.default_visibility IS
    'Visibilité appliquée aux nouveaux documents (surchargeable par owner à la création)';
