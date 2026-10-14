-- SPDX-License-Identifier: LicenseRef-Socle-Proprietary
-- Invitations e-mail (docs/invitations.md). Table distincte de team_invitations (V1, inutilisée).

CREATE TABLE invitations (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email              TEXT NOT NULL,
    team_id            UUID REFERENCES groups (id) ON DELETE SET NULL,
    team_role          TEXT,
    global_role        TEXT NOT NULL,
    token_hash         BYTEA NOT NULL,
    status             TEXT NOT NULL DEFAULT 'pending'
                       CHECK (status IN ('pending', 'accepted', 'revoked', 'expired')),
    expires_at         TIMESTAMPTZ NOT NULL,
    invited_by         UUID NOT NULL REFERENCES users (id),
    accepted_user_id   UUID REFERENCES users (id),
    accepted_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_sent_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    send_count         INTEGER NOT NULL DEFAULT 1,
    CONSTRAINT invitations_team_role_check
        CHECK (team_role IS NULL OR team_role IN (
            'proprietaire', 'approbateur', 'contributeur', 'lecteur')),
    CONSTRAINT invitations_team_pair_check
        CHECK ((team_id IS NULL AND team_role IS NULL)
            OR (team_id IS NOT NULL AND team_role IS NOT NULL)),
    CONSTRAINT invitations_ttl_check
        CHECK (expires_at <= created_at + INTERVAL '30 days'),
    CONSTRAINT invitations_send_count_check
        CHECK (send_count >= 1)
);

CREATE UNIQUE INDEX invitations_pending_email_uidx
    ON invitations (email)
    WHERE status = 'pending';

CREATE INDEX invitations_status_expires_idx
    ON invitations (status, expires_at);
