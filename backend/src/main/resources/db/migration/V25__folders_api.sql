-- SPDX-License-Identifier: LicenseRef-Socle-Proprietary
-- Folders API : name/position/created_by, unicité frères, position documents.

-- folders.title → name (API / produit)
ALTER TABLE folders RENAME COLUMN title TO name;

ALTER TABLE folders
    ADD COLUMN IF NOT EXISTS position INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS created_by UUID NULL REFERENCES users(id);

-- documents.position : ordre manuel dans le dossier (NULL = racine espace)
ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS position INTEGER NOT NULL DEFAULT 0;

-- Unicité (espace, parent, nom) hors soft-delete.
-- COALESCE pour traiter parent_folder_id NULL (racine) comme une clé stable.
CREATE UNIQUE INDEX IF NOT EXISTS uq_folders_sibling_name
    ON folders (
        space_id,
        COALESCE(parent_folder_id, '00000000-0000-0000-0000-000000000000'::uuid),
        lower(name)
    )
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_folders_parent_position
    ON folders (space_id, parent_folder_id, position)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_documents_folder_position
    ON documents (folder_id, position)
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN folders.name IS 'Nom du dossier (unique parmi frères actifs du même parent).';
COMMENT ON COLUMN folders.position IS 'Ordre manuel parmi les frères (même parent_folder_id).';
COMMENT ON COLUMN folders.created_by IS 'Créateur du dossier.';
COMMENT ON COLUMN documents.position IS 'Ordre manuel dans le dossier (ou racine espace si folder_id NULL).';
COMMENT ON COLUMN documents.folder_id IS 'NULL = racine de l''espace.';
