-- Brouillons d'édition (autosave) : un brouillon par (document, utilisateur).
-- Toujours en PostgreSQL — jamais envoyés vers le stockage Git. Pas de version, pas d'audit,
-- pas d'événement outbox/webhook : une version n'existe que par sauvegarde explicite
-- (PUT /documents/{id}).

CREATE TABLE document_drafts (
    document_id     UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body            JSONB NOT NULL,
    title           TEXT,
    base_version_no INT NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, user_id)
);

-- Export / effacement des données personnelles (RGPD) : accès par utilisateur.
CREATE INDEX idx_document_drafts_user ON document_drafts (user_id);

COMMENT ON TABLE document_drafts IS
    'Brouillon autosave par auteur (visible uniquement par son auteur). Supprimé après '
    'sauvegarde explicite de version. Jamais écrit dans Git.';
