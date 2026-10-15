-- SPDX-License-Identifier: LicenseRef-Socle-Proprietary
-- Page de dossier : document ordinaire rattaché, créé à la demande.
-- folders.body n'est ni modifié ni supprimé : il est obsolète (voir docs/decisions).

ALTER TABLE folders
    ADD COLUMN page_document_id UUID NULL UNIQUE
        REFERENCES documents(id) ON DELETE RESTRICT;

COMMENT ON COLUMN folders.page_document_id IS
    'Document ordinaire servant de page du dossier. NULL tant qu''aucune écriture n''a eu lieu. UNIQUE : un document n''est la page que d''un seul dossier.';

COMMENT ON COLUMN folders.body IS
    'Obsolète. Le contenu éditable du dossier est le document page_document_id, pas cette colonne.';
