-- Index persistant des transclusions (arêtes sortantes par document).
-- target_id sans FK : la cible peut être absente / soft-deleted.

CREATE TABLE document_links (
    source_id       UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    target_id       UUID NOT NULL,
    source_space_id UUID NOT NULL,
    link_type       TEXT NOT NULL DEFAULT 'transclusion',
    PRIMARY KEY (source_id, target_id, link_type)
);

CREATE INDEX idx_document_links_target_id ON document_links (target_id);
CREATE INDEX idx_document_links_source_space_id ON document_links (source_space_id);

COMMENT ON TABLE document_links IS
    'Arêtes de transclusion indexées à l''écriture — soft-delete filtré via documents.deleted_at à la lecture';
