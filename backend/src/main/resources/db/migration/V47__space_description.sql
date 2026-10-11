-- SPDX-License-Identifier: LicenseRef-Socle-Proprietary
-- Description d'espace (texte brut, borné côté service).

ALTER TABLE spaces
    ADD COLUMN IF NOT EXISTS description TEXT;
