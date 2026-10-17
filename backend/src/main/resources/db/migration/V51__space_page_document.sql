-- SPDX-License-Identifier: LicenseRef-Socle-Proprietary
-- Page d'espace : document ordinaire rattaché, créé à la demande.
-- Le document a space_id = cet espace et folder_id NULL (garanti par l'application).

ALTER TABLE spaces
    ADD COLUMN page_document_id UUID NULL UNIQUE
        REFERENCES documents(id) ON DELETE RESTRICT;

COMMENT ON COLUMN spaces.page_document_id IS
    'Document ordinaire servant de page de l''espace. NULL tant qu''aucune écriture n''a eu lieu. UNIQUE : un document n''est la page que d''un seul espace. folder_id du document reste NULL.';
