-- Admin Tags / Custom Fields : provenance des tags, politique de création,
-- texte d'aide des définitions de champs.

ALTER TABLE tags
    ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;

-- Existant : created_by reste NULL (« — ») ; created_at renseigné sans inventer d'auteur.
UPDATE tags SET created_at = COALESCE(created_at, now()) WHERE created_at IS NULL;
ALTER TABLE tags
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN created_at SET NOT NULL;

-- Unicité insensible à la casse (complète UNIQUE name).
CREATE UNIQUE INDEX IF NOT EXISTS uq_tags_name_ci ON tags (lower(name));

CREATE TABLE IF NOT EXISTS instance_settings (
    id                      BOOLEAN PRIMARY KEY DEFAULT true CHECK (id),
    tag_creation_policy     TEXT NOT NULL DEFAULT 'any_editor'
        CHECK (tag_creation_policy IN ('any_editor', 'admins_only')),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO instance_settings (id, tag_creation_policy)
VALUES (true, 'any_editor')
ON CONFLICT (id) DO NOTHING;

ALTER TABLE custom_field_definitions
    ADD COLUMN IF NOT EXISTS help_text TEXT;

COMMENT ON COLUMN tags.created_by IS
    'Auteur de création ; NULL pour les tags antérieurs à V42 (affichage « — »).';
COMMENT ON COLUMN instance_settings.tag_creation_policy IS
    'any_editor (défaut) : tout éditeur peut créer via attache par nom ; '
    'admins_only : seuls ADMINISTRATEUR_SYSTEME créent un nouveau tag.';
COMMENT ON COLUMN custom_field_definitions.help_text IS
    'Description d''aide affichée dans le constructeur / panneau Métadonnées.';
