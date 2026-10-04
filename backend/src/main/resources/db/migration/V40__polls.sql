-- Sondages embarqués dans le corps TipTap (id stable dans le nœud).
-- Votes individuels jamais exposés ; totaux agrégés pour les lecteurs.
-- ON DELETE CASCADE avec le document ; archivage si le nœud disparaît du corps.

CREATE TABLE polls (
    id           UUID PRIMARY KEY,
    document_id  UUID        NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    question     TEXT        NOT NULL,
    options      JSONB       NOT NULL,
    closed_at    TIMESTAMPTZ,
    archived_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT polls_options_is_array CHECK (jsonb_typeof(options) = 'array')
);

CREATE INDEX idx_polls_document ON polls (document_id);
CREATE INDEX idx_polls_document_active ON polls (document_id) WHERE archived_at IS NULL;

CREATE TABLE poll_votes (
    poll_id    UUID        NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    option     TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (poll_id, user_id)
);

CREATE INDEX idx_poll_votes_poll ON poll_votes (poll_id);
