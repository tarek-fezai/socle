-- Soft-lock d'édition advisory (multi-instance) — voir docs/editing-concurrency.md
CREATE TABLE document_edit_locks (
    document_id    UUID PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
    holder_user_id UUID NOT NULL REFERENCES users(id),
    acquired_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    heartbeat_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_document_edit_locks_heartbeat ON document_edit_locks (heartbeat_at);

COMMENT ON TABLE document_edit_locks IS
    'Soft-lock advisory par document : un holder, TTL via heartbeat_at. Jamais bloquant.';
