-- Admin Rétention & conformité + Branding (instance unique, jamais « SaaS »).
--   * instance_settings : politiques de rétention (audit, versions, archives) + registre de traitement
--   * legal_holds       : gel légal (document | espace) bloquant toute suppression / purge / effacement
--   * branding_settings : ligne singleton garantie, logo/favicon en BlobStore
--   * audit_log_events  : DELETE réservé à la purge de rétention (garde-fou trigger)

-- ============================================================================
-- 1. Rétention
-- ============================================================================

ALTER TABLE instance_settings
    ADD COLUMN IF NOT EXISTS audit_retention_months INT NOT NULL DEFAULT 24
        CHECK (audit_retention_months BETWEEN 1 AND 1200),
    ADD COLUMN IF NOT EXISTS version_retention_mode TEXT NOT NULL DEFAULT 'unlimited'
        CHECK (version_retention_mode IN ('unlimited', 'months', 'count')),
    ADD COLUMN IF NOT EXISTS version_retention_value INT
        CHECK (version_retention_value IS NULL OR version_retention_value > 0),
    ADD COLUMN IF NOT EXISTS archived_docs_retention_years INT NOT NULL DEFAULT 7
        CHECK (archived_docs_retention_years BETWEEN 1 AND 100),
    ADD COLUMN IF NOT EXISTS processing_register_reviewed_at DATE;

ALTER TABLE instance_settings
    ADD CONSTRAINT instance_settings_version_retention_value_chk CHECK (
        (version_retention_mode = 'unlimited' AND version_retention_value IS NULL)
        OR (version_retention_mode IN ('months', 'count') AND version_retention_value IS NOT NULL));

INSERT INTO instance_settings (id) VALUES (true) ON CONFLICT (id) DO NOTHING;

COMMENT ON COLUMN instance_settings.audit_retention_months IS
    'Durée de conservation du journal d''audit (mois) ; purgée chaque jour par RetentionPurgeScheduler.';
COMMENT ON COLUMN instance_settings.version_retention_mode IS
    'unlimited (défaut) | months (versions plus anciennes que N mois) | count (N dernières versions par document).';
COMMENT ON COLUMN instance_settings.version_retention_value IS
    'Valeur associée à version_retention_mode (NULL si unlimited).';
COMMENT ON COLUMN instance_settings.archived_docs_retention_years IS
    'Documents archivés (status = archive) et espaces entièrement archivés purgés après N années.';
COMMENT ON COLUMN instance_settings.processing_register_reviewed_at IS
    'Date de dernière revue du registre de traitement (RGPD art. 30), saisie par l''administrateur.';

-- ============================================================================
-- 2. Legal hold
-- ============================================================================

CREATE TABLE legal_holds (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope_type      TEXT NOT NULL CHECK (scope_type IN ('document', 'space')),
    scope_id        UUID NOT NULL,
    reason          TEXT NOT NULL CHECK (length(btrim(reason)) > 0),
    created_by      UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_by     UUID REFERENCES users(id) ON DELETE SET NULL,
    released_at     TIMESTAMPTZ,
    release_reason  TEXT
);

-- Un seul gel actif par périmètre ; l'historique (gels levés) est conservé.
CREATE UNIQUE INDEX uq_legal_holds_active
    ON legal_holds (scope_type, scope_id) WHERE released_at IS NULL;
CREATE INDEX idx_legal_holds_scope ON legal_holds (scope_type, scope_id);

COMMENT ON TABLE legal_holds IS
    'Gel légal : tant que released_at IS NULL, suppression / corbeille / purge / effacement RGPD '
    'du document (ou de tous les documents de l''espace) renvoient 409 legal_hold_active.';

-- ============================================================================
-- 3. Branding (singleton)
-- ============================================================================

INSERT INTO branding_settings (id) VALUES (true) ON CONFLICT (id) DO NOTHING;

ALTER TABLE branding_settings
    ADD COLUMN IF NOT EXISTS logo_blob_key      TEXT,
    ADD COLUMN IF NOT EXISTS favicon_blob_key   TEXT,
    ADD COLUMN IF NOT EXISTS logo_media_type    TEXT,
    ADD COLUMN IF NOT EXISTS favicon_media_type TEXT,
    ADD COLUMN IF NOT EXISTS updated_at         TIMESTAMPTZ NOT NULL DEFAULT now();

COMMENT ON COLUMN branding_settings.custom_domain IS
    'Obsolète : domaine personnalisé SaaS non utilisé (instance auto-hébergée). '
    'L''URL publique vient de SOCLE_PUBLIC_BASE_URL. Colonne conservée, plus lue ni écrite.';
COMMENT ON COLUMN branding_settings.custom_domain_verified IS 'Obsolète (voir custom_domain).';
COMMENT ON COLUMN branding_settings.spf_dkim_verified IS 'Obsolète (voir custom_domain).';
COMMENT ON COLUMN branding_settings.logo_blob_key IS
    'Clé BlobStore du logo (branding/logo) ; NULL = pas de logo.';
COMMENT ON COLUMN branding_settings.favicon_blob_key IS
    'Clé BlobStore du favicon (branding/favicon) ; NULL = pas de favicon.';

-- ============================================================================
-- 4. audit_log_events : DELETE uniquement via la purge de rétention
-- ============================================================================
-- V5/V28 révoquent UPDATE/DELETE au rôle applicatif. La rétention doit pouvoir supprimer les
-- événements plus anciens que audit_retention_months : DELETE est de nouveau accordé, mais un
-- trigger refuse toute suppression hors transaction de purge (set_config local) et toute ligne
-- encore dans la fenêtre de rétention. UPDATE reste révoqué.

CREATE OR REPLACE FUNCTION audit_log_events_retention_guard() RETURNS trigger AS $$
DECLARE
    months INT;
BEGIN
    IF current_setting('socle.audit_retention_purge', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'audit_log_events est append-only : DELETE réservé à la purge de rétention'
            USING ERRCODE = '42501';
    END IF;
    SELECT audit_retention_months INTO months FROM instance_settings WHERE id = true;
    IF months IS NULL THEN
        months := 24;
    END IF;
    IF OLD.created_at >= now() - make_interval(months => months) THEN
        RAISE EXCEPTION 'audit_log_events : événement encore dans la fenêtre de rétention (% mois)', months
            USING ERRCODE = '42501';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_events_retention_guard
    BEFORE DELETE ON audit_log_events
    FOR EACH ROW EXECUTE FUNCTION audit_log_events_retention_guard();

DO $$
BEGIN
    EXECUTE format('GRANT DELETE ON TABLE audit_log_events TO %I', current_user);
EXCEPTION
    WHEN undefined_object THEN
        NULL;
    WHEN insufficient_privilege THEN
        NULL;
END $$;
