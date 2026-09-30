-- Métadonnée Git (provider git) : commit SHA ↔ version_no API.
ALTER TABLE document_versions
    ADD COLUMN IF NOT EXISTS git_commit_sha TEXT;
