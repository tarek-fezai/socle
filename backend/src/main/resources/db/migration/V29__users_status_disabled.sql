-- SPDX-License-Identifier: LicenseRef-Socle-Proprietary
-- Politique d'accès : un compte peut être désactivé par un administrateur (status = 'disabled').
-- Les statuts historiques (invited, suspended, deactivated) restent autorisés pour les lignes existantes.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_status_check;
ALTER TABLE users
    ADD CONSTRAINT users_status_check
    CHECK (status IN ('invited', 'active', 'disabled', 'suspended', 'deactivated'));
