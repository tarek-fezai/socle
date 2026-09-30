-- Soft-delete réel : tombstones sur les ressources ; trash_items = index Corbeille (affichage).
-- La restauration lit/écrit documents|folders|spaces, pas le snapshot JSONB.

ALTER TABLE spaces
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ADD COLUMN deleted_by UUID NULL REFERENCES users(id);

ALTER TABLE folders
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ADD COLUMN deleted_by UUID NULL REFERENCES users(id);

ALTER TABLE documents
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ADD COLUMN deleted_by UUID NULL REFERENCES users(id);

CREATE INDEX idx_documents_not_deleted ON documents (space_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_folders_not_deleted ON folders (space_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_spaces_not_deleted ON spaces (id) WHERE deleted_at IS NULL;

CREATE INDEX idx_trash_items_purge ON trash_items (purge_at);
CREATE UNIQUE INDEX uq_trash_items_resource ON trash_items (resource_type, resource_id);

COMMENT ON TABLE trash_items IS
    'Index Corbeille (affichage). Source de vérité = deleted_at sur documents/folders/spaces.';
COMMENT ON COLUMN trash_items.resource_snapshot IS
    'Snapshot d''affichage (title, …) — pas utilisé pour restaurer le contenu.';
