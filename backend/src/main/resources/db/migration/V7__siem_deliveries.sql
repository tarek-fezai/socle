-- Livraisons SIEM (outbox) — pendant de webhook_deliveries, consommée par webhook-worker Go.
CREATE TABLE siem_deliveries (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connector_id        UUID NOT NULL REFERENCES siem_connectors(id) ON DELETE CASCADE,
    audit_event_id      BIGINT NOT NULL REFERENCES audit_log_events(id),
    event_type          TEXT NOT NULL,
    payload             JSONB NOT NULL,
    status              TEXT NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending','delivered','failed','retrying')),
    attempt_count       INTEGER NOT NULL DEFAULT 0,
    last_response_code  INTEGER,
    delivered_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_siem_deliveries_status
    ON siem_deliveries (status)
    WHERE status IN ('pending', 'retrying');

CREATE INDEX idx_siem_deliveries_connector
    ON siem_deliveries (connector_id, created_at DESC);
