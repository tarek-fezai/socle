-- Gouvernance espace : owners + sous-ensemble responsible (pas un rôle global).
CREATE TABLE space_owners (
    space_id        UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    is_responsible  BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, user_id)
);

CREATE INDEX idx_space_owners_responsible
    ON space_owners (space_id)
    WHERE is_responsible = true;

-- Créateur du groupe (gestion des membres).
ALTER TABLE groups
    ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES users(id);

COMMENT ON TABLE space_owners IS
    'Owners d''espace : mirror applicatif de la gouvernance. OpenFGA reste source pour Check. '
    'is_responsible = sous-ensemble qui gère qui entre/sort des owners.';
