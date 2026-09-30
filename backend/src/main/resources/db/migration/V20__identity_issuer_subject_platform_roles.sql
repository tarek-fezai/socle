-- Identity decoupling: issuer+subject as stable user key; internal platform roles.

ALTER TABLE users ADD COLUMN IF NOT EXISTS issuer TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS subject TEXT;

-- Backfill existing rows (Keycloak-compat: subject was historically users.id).
UPDATE users
   SET subject = id::text
 WHERE subject IS NULL;

UPDATE users
   SET issuer = 'http://localhost:8081/realms/socle'
 WHERE issuer IS NULL;

ALTER TABLE users ALTER COLUMN issuer SET NOT NULL;
ALTER TABLE users ALTER COLUMN subject SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_issuer_subject ON users (issuer, subject);

CREATE TABLE IF NOT EXISTS user_platform_roles (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        TEXT NOT NULL,
    granted_by  UUID REFERENCES users(id),
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role)
);

CREATE INDEX IF NOT EXISTS idx_user_platform_roles_role ON user_platform_roles (role);
