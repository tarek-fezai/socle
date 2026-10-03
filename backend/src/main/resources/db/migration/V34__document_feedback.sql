-- Retour "cette page vous a-t-elle ete utile ?" : un vote par utilisateur et par document.
-- Les totaux oui/non ne sont exposes qu'aux editeurs du document (voir docs/privacy.md).

CREATE TABLE document_feedback (
    document_id UUID        NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    helpful     BOOLEAN     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, user_id)
);

CREATE INDEX idx_document_feedback_document_helpful
    ON document_feedback (document_id, helpful);
