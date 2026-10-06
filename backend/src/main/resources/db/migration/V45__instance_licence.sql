-- Licence d'instance (fichier JSON signé Ed25519).

CREATE TABLE IF NOT EXISTS instance_licence (
    id              BOOLEAN PRIMARY KEY DEFAULT true CHECK (id),
    license_id      TEXT NOT NULL,
    licensee        TEXT NOT NULL,
    edition         TEXT NOT NULL,
    issued_at       TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    max_users       INT NOT NULL CHECK (max_users > 0),
    payload_json    JSONB NOT NULL,
    imported_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    imported_by     UUID REFERENCES users(id)
);

COMMENT ON TABLE instance_licence IS
    'Licence d''instance courante (remplacement à l''import) ; signature Ed25519 vérifiée hors ligne.';
