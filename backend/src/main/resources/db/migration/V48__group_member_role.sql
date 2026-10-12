-- SPDX-License-Identifier: LicenseRef-Socle-Proprietary
-- Rôle par membre d'équipe (docs/teams-and-access.md). Défaut contributeur.

CREATE EXTENSION IF NOT EXISTS unaccent;

ALTER TABLE group_members
    ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'contributeur';

ALTER TABLE group_members
    DROP CONSTRAINT IF EXISTS group_members_role_check;

ALTER TABLE group_members
    ADD CONSTRAINT group_members_role_check
        CHECK (role IN ('proprietaire', 'approbateur', 'contributeur', 'lecteur'));

-- Créateur → propriétaire (insertion s'il n'est pas déjà membre).
INSERT INTO group_members (group_id, user_id, role)
SELECT g.id, g.created_by, 'proprietaire'
  FROM groups g
 WHERE g.created_by IS NOT NULL
   AND EXISTS (SELECT 1 FROM users u WHERE u.id = g.created_by)
    ON CONFLICT (group_id, user_id) DO UPDATE SET role = 'proprietaire';
