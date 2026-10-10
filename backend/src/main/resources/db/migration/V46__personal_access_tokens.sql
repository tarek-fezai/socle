-- Jetons d'accès personnels (PAT) : agissent au nom d'un utilisateur, expiration obligatoire ≤ 90 jours.
-- Le secret n'est jamais stocké : token_hash = HMAC-SHA256(secret, SOCLE_PAT_PEPPER).
--
-- V1 avait posé une table réservée du même nom (token_hash TEXT, scopes TEXT[], sans expiration),
-- jamais alimentée par l'application : elle est remplacée. Garde : refus si elle contient des lignes.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM personal_access_tokens) THEN
        RAISE EXCEPTION 'V46 : personal_access_tokens (schéma V1) contient des lignes — migration manuelle requise';
    END IF;
END
$$;

DROP TABLE personal_access_tokens;

CREATE TABLE personal_access_tokens (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL CHECK (length(btrim(name)) > 0),
    token_lookup    CHAR(12) NOT NULL UNIQUE,
    token_hash      BYTEA NOT NULL,
    last4           CHAR(4) NOT NULL,
    scope           VARCHAR(16) NOT NULL CHECK (scope IN ('read', 'read_write')),
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    last_used_at    TIMESTAMPTZ NULL,
    revoked_at      TIMESTAMPTZ NULL,
    revoked_reason  VARCHAR(32) NULL CHECK (revoked_reason IN ('manual', 'user_disabled')),
    CONSTRAINT personal_access_tokens_expiry_window
        CHECK (expires_at > created_at AND expires_at <= created_at + interval '90 days'),
    CONSTRAINT personal_access_tokens_revocation_consistent
        CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);

CREATE INDEX personal_access_tokens_active_user_idx
    ON personal_access_tokens (user_id)
    WHERE revoked_at IS NULL;

-- Une seule notification « expire dans 7 jours » par jeton, même avec plusieurs réplicas.
CREATE UNIQUE INDEX notifications_pat_expiring_once_idx
    ON notifications ((payload ->> 'pat_id'))
    WHERE type = 'pat_expiring';

COMMENT ON TABLE personal_access_tokens IS
    'PAT utilisateur : secret jamais stocké (HMAC + pepper), expiration ≤ 90 jours, révocation horodatée.';
