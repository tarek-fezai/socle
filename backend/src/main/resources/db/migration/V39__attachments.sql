-- Pièces jointes (BlobStore) — métadonnées ; octets hors Postgres.
CREATE TABLE attachments (
    id                UUID PRIMARY KEY,
    document_id       UUID NOT NULL REFERENCES documents (id),
    uploaded_by       UUID NOT NULL REFERENCES users (id),
    original_filename TEXT NOT NULL,
    media_type        TEXT NOT NULL,
    size_bytes        BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256            CHAR(64) NOT NULL,
    storage_key       UUID NOT NULL UNIQUE,
    width             INT NULL,
    height            INT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ NULL,
    -- NULL tant que référencée par une version (courante ou archivée) ou en attente de purge orpheline.
    referenced_at     TIMESTAMPTZ NULL
);

CREATE INDEX idx_attachments_document ON attachments (document_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_attachments_orphan ON attachments (created_at)
    WHERE deleted_at IS NULL AND referenced_at IS NULL;

COMMENT ON TABLE attachments IS 'Métadonnées des fichiers ; octets dans BlobStore (local ou S3-compatible).';
COMMENT ON COLUMN attachments.storage_key IS 'Clé BlobStore = UUID (jamais le nom utilisateur).';
COMMENT ON COLUMN attachments.referenced_at IS 'Dernière fois où une version TipTap a référencé cette pièce ; NULL = orpheline candidate.';
