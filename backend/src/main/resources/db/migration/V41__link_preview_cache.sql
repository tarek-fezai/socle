-- Cache d'aperçus de lien (activé seulement si SOCLE_LINK_PREVIEW_ENABLED=true).
-- Vignettes stockées comme pièces jointes (pas de hotlink vers le tiers).

CREATE TABLE link_preview_cache (
    id                      UUID PRIMARY KEY,
    url_norm                TEXT        NOT NULL,
    url_hash                TEXT        NOT NULL UNIQUE,
    title                   TEXT,
    domain                  TEXT        NOT NULL,
    thumbnail_attachment_id UUID        REFERENCES attachments(id) ON DELETE SET NULL,
    fetched_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at              TIMESTAMPTZ NOT NULL,
    created_by              UUID        REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_link_preview_cache_expires ON link_preview_cache (expires_at);
