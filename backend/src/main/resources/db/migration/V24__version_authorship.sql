-- SPDX-License-Identifier: LicenseRef-Socle-Proprietary
-- author_id = auteur du contenu ; archived_by = qui a déclenché l'archivage.
-- documents.updated_by = auteur du contenu courant.

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES users(id);

COMMENT ON COLUMN documents.updated_by IS
    'Auteur du contenu courant (create/update/restore) — non modifié à la soumission';

ALTER TABLE document_versions
    ADD COLUMN IF NOT EXISTS archived_by UUID REFERENCES users(id);

COMMENT ON COLUMN document_versions.author_id IS
    'Auteur du contenu de cette version archivée';
COMMENT ON COLUMN document_versions.archived_by IS
    'Utilisateur qui a déclenché l''archivage (édition, restore ou soumission)';

-- Backfill léger updated_by pour docs sans historique : = created_by
UPDATE documents
   SET updated_by = created_by
 WHERE updated_by IS NULL
   AND created_by IS NOT NULL
   AND NOT EXISTS (
         SELECT 1 FROM document_versions dv WHERE dv.document_id = documents.id
       );
