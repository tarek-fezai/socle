-- SPDX-License-Identifier: LicenseRef-Socle-Proprietary
-- Commentaires : ancres TextQuoteSelector, soft-delete, statut, politique espace.

ALTER TABLE spaces
    ADD COLUMN IF NOT EXISTS comment_policy TEXT NOT NULL DEFAULT 'members'
        CHECK (comment_policy IN ('members', 'all_readers'));

COMMENT ON COLUMN spaces.comment_policy IS
    'members = space.viewer|editor doc ; all_readers = tout viewer du document';

ALTER TABLE document_comments
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deleted_by UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS deleted_by_moderator BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS author_display_name TEXT,
    ADD COLUMN IF NOT EXISTS author_anonymized BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'ouvert',
    ADD COLUMN IF NOT EXISTS anchor_exact TEXT,
    ADD COLUMN IF NOT EXISTS anchor_prefix TEXT,
    ADD COLUMN IF NOT EXISTS anchor_suffix TEXT,
    ADD COLUMN IF NOT EXISTS anchor_version_no INTEGER;

-- Contrainte status (après backfill)
UPDATE document_comments
   SET status = CASE WHEN resolved THEN 'resolu' ELSE 'ouvert' END
 WHERE status IS NULL OR status NOT IN ('ouvert', 'resolu');

ALTER TABLE document_comments DROP CONSTRAINT IF EXISTS document_comments_status_check;
ALTER TABLE document_comments
    ADD CONSTRAINT document_comments_status_check CHECK (status IN ('ouvert', 'resolu'));

-- Préfixe / suffixe W3C : max 32 caractères (application) ; colonnes TEXT.
COMMENT ON COLUMN document_comments.anchor_exact IS 'TextQuoteSelector.exact';
COMMENT ON COLUMN document_comments.anchor_prefix IS 'TextQuoteSelector.prefix (≤32)';
COMMENT ON COLUMN document_comments.anchor_suffix IS 'TextQuoteSelector.suffix (≤32)';
COMMENT ON COLUMN document_comments.anchor_block_id IS 'Identifiant de bloc TipTap si disponible';
COMMENT ON COLUMN document_comments.anchor_version_no IS 'current_version_no à la création de l''ancre';

CREATE INDEX IF NOT EXISTS idx_document_comments_doc_active
    ON document_comments (document_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_document_comments_doc_status
    ON document_comments (document_id, status)
    WHERE deleted_at IS NULL AND parent_comment_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_document_comments_author
    ON document_comments (author_id)
    WHERE deleted_at IS NULL;
