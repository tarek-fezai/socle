-- Drop email uniqueness; identities move to user_identities (multi-IdP).

-- 1. Email: unique → index non unique
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;
DROP INDEX IF EXISTS users_email_key;
CREATE INDEX IF NOT EXISTS idx_users_email ON users (email);

-- 2. Table des identités externes (unicité globale issuer+subject)
CREATE TABLE IF NOT EXISTS user_identities (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    issuer      TEXT NOT NULL,
    subject     TEXT NOT NULL,
    linked_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    linked_by   UUID REFERENCES users(id),
    PRIMARY KEY (user_id, issuer, subject),
    UNIQUE (issuer, subject)
);

CREATE INDEX IF NOT EXISTS idx_user_identities_user_id ON user_identities (user_id);

-- 3. Migrer users.issuer / users.subject
INSERT INTO user_identities (user_id, issuer, subject, linked_at, linked_by)
SELECT id, issuer, subject, COALESCE(created_at, now()), NULL
  FROM users
 WHERE issuer IS NOT NULL
   AND subject IS NOT NULL
ON CONFLICT (issuer, subject) DO NOTHING;

DROP INDEX IF EXISTS uq_users_issuer_subject;

ALTER TABLE users DROP COLUMN IF EXISTS issuer;
ALTER TABLE users DROP COLUMN IF EXISTS subject;
