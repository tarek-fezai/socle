-- File durable de purge Git (après suppression SQL définitive).

CREATE TABLE IF NOT EXISTS git_purge_queue (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_ids        UUID[] NOT NULL,
    requested_by        UUID,
    motif               TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'pending',
    attempts            INT NOT NULL DEFAULT 0,
    last_error          TEXT,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    CONSTRAINT git_purge_queue_motif_check
        CHECK (motif IN ('retention', 'rgpd', 'corbeille')),
    CONSTRAINT git_purge_queue_status_check
        CHECK (status IN ('pending', 'running', 'done', 'failed'))
);

CREATE INDEX IF NOT EXISTS git_purge_queue_status_next_attempt_idx
    ON git_purge_queue (status, next_attempt_at)
    WHERE status IN ('pending', 'failed');

COMMENT ON TABLE git_purge_queue IS
    'Purge réelle de l''historique Git après suppression SQL ; reprise automatique avec backoff.';
