-- Hibernate validate cannot map citext cleanly without extra types; text is enough for app-level case handling.
ALTER TABLE users ALTER COLUMN email TYPE text USING email::text;
ALTER TABLE team_invitations ALTER COLUMN email TYPE text USING email::text;
