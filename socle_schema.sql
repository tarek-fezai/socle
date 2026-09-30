-- ============================================================================
-- REFERENCE ONLY — do not execute this file by hand.
-- Canonical versioned migration:
--   backend/src/main/resources/db/migration/V1__init.sql
-- Apply via Flyway on backend startup (spring.flyway). Schema changes = new Vn__*.sql.
-- ============================================================================
-- SOCLE — Schéma de base de données (PostgreSQL 16+)
-- Plateforme de documentation d'entreprise auto-hébergée
-- Dérivé de l'artifact "Système de documentation — direction UI" (83 écrans)
--
-- Notes de conception :
--   - Déploiement mono-tenant : une instance de cette base = un VPC = un
--     client. Pas de multi-tenant partagé, donc pas de
--     colonne org_id, pas de Row-Level Security par organisation, pas de
--     fuite possible entre clients au niveau base de données. La séparation
--     des clients est assurée par l'infrastructure (VPC dédié), pas par le
--     schéma.
--   - Corollaire : tout ce qui relève de la gestion de flotte multi-clients
--     (facturation, licences, suivi de version/santé de chaque déploiement)
--     ne vit PAS dans cette base. C'est le rôle d'un système de contrôle
--     central (control plane / fleet management), externe, qui parle à
--     chaque déploiement via une API d'health-check/reporting — non modélisé
--     ici, à concevoir séparément.
--   - UUID (gen_random_uuid(), extension pgcrypto) comme clé primaire partout.
--   - Le contenu riche des documents est stocké en JSONB (format structuré
--     type ProseMirror/Tiptap), jamais du HTML brut — cohérent avec le
--     sandboxing des blocs enrichis vu dans Integrations.dc.html.
--   - Les relations de permission fines (accès direct + groupes hérités,
--     cascade espace → dossier → document, vues sur Access.dc.html) sont
--     modélisées et appliquées dans OpenFGA, pas ici. Cette base ne garde
--     qu'un miroir léger (table `groups` / `group_members`) pour les besoins
--     d'affichage UI (Team.dc.html, GlobalRoles.dc.html) ; la décision
--     d'autorisation ne doit jamais être prise en lisant ces tables SQL.
--   - Le journal d'audit est append-only (aucun UPDATE/DELETE applicatif).
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

-- ============================================================================
-- 1. DÉPLOIEMENT, UTILISATEURS, SSO
-- ============================================================================

-- Ligne unique décrivant ce déploiement (identité affichée, palette de plan
-- informative). Volontairement une table à une seule ligne plutôt qu'une
-- fausse table multi-organisations : il n'y aura jamais de deuxième client
-- dans cette base. Le niveau de licence/plan est informatif côté produit ;
-- l'entitlement réel (facturation, expiration) est arbitré par le control
-- plane externe, pas lu ici pour prendre une décision d'accès.
CREATE TABLE platform_settings (
    id                  BOOLEAN PRIMARY KEY DEFAULT true CHECK (id),  -- verrou singleton (une seule ligne possible)
    org_name            TEXT NOT NULL,                     -- ex. "Organisation Démo"
    org_slug            TEXT NOT NULL UNIQUE,
    plan_tier           TEXT NOT NULL DEFAULT 'enterprise'  -- informatif uniquement, voir note ci-dessus
                        CHECK (plan_tier IN ('standard','business','enterprise')),
    deployment_id       UUID NOT NULL DEFAULT gen_random_uuid(),  -- identifiant stable envoyé au control plane
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               CITEXT NOT NULL,                    -- attribut login ; NON unique (multi-IdP)
    display_name        TEXT NOT NULL,
    avatar_initials     TEXT,                               -- "TF", "CD"...
    status              TEXT NOT NULL DEFAULT 'active'
                        CHECK (status IN ('invited','active','suspended','deactivated')),
    is_system_account   BOOLEAN NOT NULL DEFAULT false,      -- ex. "Système (migration)"
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email ON users (email);

-- Identités OIDC (unicité globale issuer+subject) — V21
CREATE TABLE user_identities (
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    issuer              TEXT NOT NULL,
    subject             TEXT NOT NULL,
    linked_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    linked_by           UUID REFERENCES users(id),
    PRIMARY KEY (user_id, issuer, subject),
    UNIQUE (issuer, subject)
);

-- Rôles plateforme (mode role-source INTERNAL | BOTH) — ≠ global_roles (workflows)
CREATE TABLE user_platform_roles (
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role                TEXT NOT NULL,                      -- SocleRole.name()
    granted_by          UUID REFERENCES users(id),
    granted_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role)
);

CREATE TABLE sso_connections (                               -- Admin.dc.html "Identité & SSO"
    -- Métadonnée instance ; le runtime Socle ne parle qu'OIDC (SAML via broker IdP).
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    protocol            TEXT NOT NULL CHECK (protocol IN ('saml','oidc')),
    idp_metadata_url    TEXT,
    scim_enabled        BOOLEAN NOT NULL DEFAULT false,
    scim_endpoint_secret TEXT,
    status              TEXT NOT NULL DEFAULT 'draft'
                        CHECK (status IN ('draft','active','disabled')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE team_invitations (                               -- TeamInvitations.dc.html, InviteMember/InviteOrgMember
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               CITEXT NOT NULL,
    invited_role        TEXT NOT NULL,
    invited_by          UUID NOT NULL REFERENCES users(id),
    status              TEXT NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending','accepted','expired','revoked')),
    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE groups (                                         -- Team.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT NOT NULL,                        -- "Tous les employés", "Équipe conformité"
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE group_members (
    group_id            UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, user_id)
);

CREATE TABLE global_roles (                                    -- GlobalRoles.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT NOT NULL,                         -- "Administrateur système", "Analyste conformité"
    description         TEXT
);

CREATE TABLE user_global_roles (
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id             UUID NOT NULL REFERENCES global_roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- ============================================================================
-- 2. ESPACES, DOSSIERS
-- ============================================================================

CREATE TABLE spaces (                                          -- Spaces.dc.html, SpaceSettings.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT NOT NULL,                          -- "Identité & accès", "Infrastructure"
    color               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,                             -- soft-delete (corbeille)
    deleted_by          UUID REFERENCES users(id)
);

CREATE TABLE folders (                                         -- FolderProcedures.dc.html, FolderReference.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id            UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    parent_folder_id    UUID REFERENCES folders(id) ON DELETE CASCADE,
    title               TEXT NOT NULL,
    body                JSONB,                                  -- corps riche éditable (ajout récent : dossier = page normale)
    owner_group_id      UUID REFERENCES groups(id),
    status              TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','archived')),
    reliability_score   NUMERIC(5,2),                           -- moyenne agrégée affichée dans le rail
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID REFERENCES users(id),
    deleted_at          TIMESTAMPTZ,                             -- soft-delete (corbeille)
    deleted_by          UUID REFERENCES users(id)
);

-- ============================================================================
-- 3. DOCUMENTS, VERSIONS, COMMENTAIRES
-- ============================================================================

CREATE TABLE documents (                                       -- Main.dc.html, Edit.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id            UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    folder_id           UUID REFERENCES folders(id) ON DELETE SET NULL,
    title               TEXT NOT NULL,
    body                JSONB NOT NULL,                         -- blocs structurés (h1/h2/paragraphe/liste/code/embed/callout...)
    doc_type            TEXT,                                    -- type libre — match approval_workflows.scope_doc_type
    status              TEXT NOT NULL DEFAULT 'brouillon'
                        CHECK (status IN ('brouillon','en_revue','valide','archive')),
    reliability_score   NUMERIC(5,2),                           -- ContentHealth.dc.html
    reliability_computed_at TIMESTAMPTZ,
    language            TEXT NOT NULL DEFAULT 'fr',
    owner_id            UUID REFERENCES users(id),
    owner_group_id      UUID REFERENCES groups(id),
    current_version_no  INTEGER NOT NULL DEFAULT 1,
    created_by          UUID REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_mandatory_ack    BOOLEAN NOT NULL DEFAULT false,          -- bannière "accusé de lecture requis"
    ack_due_date        DATE,
    deleted_at          TIMESTAMPTZ,                             -- soft-delete (corbeille)
    deleted_by          UUID REFERENCES users(id)
);

CREATE TABLE document_versions (                                -- History.dc.html, Diff.dc.html, RestoreVersion.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id         UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    version_no          INTEGER NOT NULL,
    body_snapshot       JSONB NOT NULL,
    author_id           UUID REFERENCES users(id),
    change_summary      TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, version_no)
);

-- Index persistant des transclusions (V19) — pas de FK sur target_id (cible soft-deleted / absente OK)
CREATE TABLE document_links (
    source_id           UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    target_id           UUID NOT NULL,
    source_space_id     UUID NOT NULL,
    link_type           TEXT NOT NULL DEFAULT 'transclusion',
    PRIMARY KEY (source_id, target_id, link_type)
);

CREATE TABLE document_comments (                                -- Review.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id         UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    parent_comment_id   UUID REFERENCES document_comments(id) ON DELETE CASCADE,
    author_id           UUID NOT NULL REFERENCES users(id),
    body                TEXT NOT NULL,
    anchor_block_id     TEXT,                                   -- ancrage sur un bloc précis du document
    resolved            BOOLEAN NOT NULL DEFAULT false,
    resolved_by         UUID REFERENCES users(id),
    resolved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE attestation_acknowledgments (                      -- bannière "J'ai lu et compris" sur Main.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id         UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    acknowledged_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, user_id)
);

CREATE TABLE attestation_campaigns (                            -- Attestations.dc.html (vue admin d'ensemble)
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id         UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    target_group_id     UUID REFERENCES groups(id),
    due_date            DATE NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- 4. MÉTADONNÉES : TAGS, CHAMPS PERSONNALISÉS, MODÈLES
-- ============================================================================

CREATE TABLE tags (                                             -- TagsAdmin.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT NOT NULL UNIQUE,                   -- "IAM", "RGPD", "Critique"
    color               TEXT
);

CREATE TABLE document_tags (
    document_id         UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    tag_id              UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, tag_id)
);

CREATE TABLE custom_field_definitions (                         -- CustomFields.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT NOT NULL,                          -- "Référence réglementaire", "Système concerné"
    slug                TEXT NOT NULL UNIQUE,
    field_type          TEXT NOT NULL CHECK (field_type IN
                        ('texte','liste','multi_selection','date','nombre','lien','personne','case_a_cocher')),
    scope               TEXT NOT NULL DEFAULT 'all_spaces',      -- ou un space_id ciblé
    is_required         BOOLEAN NOT NULL DEFAULT false,
    options             JSONB,                                  -- valeurs possibles pour liste/multi_selection
    status              TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','draft','archived')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document_custom_field_values (
    document_id         UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    field_id            UUID NOT NULL REFERENCES custom_field_definitions(id) ON DELETE CASCADE,
    value               JSONB NOT NULL,                         -- typé côté application selon field_type
    PRIMARY KEY (document_id, field_id)
);

CREATE TABLE templates (                                        -- TemplatesAdmin.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT NOT NULL,
    body_template       JSONB NOT NULL,
    created_by          UUID REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE glossary_terms (                                   -- Glossary.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    term                TEXT NOT NULL,
    definition          TEXT NOT NULL
);

-- ============================================================================
-- 5. WORKFLOWS D'APPROBATION
-- ============================================================================

CREATE TABLE approval_workflows (                               -- Workflows.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT NOT NULL,
    scope_space_id      UUID REFERENCES spaces(id),              -- portée : espace ciblé (ou NULL = global)
    scope_doc_type      TEXT,                                    -- portée : type de document ciblé
    status              TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('active','draft')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE approval_workflow_steps (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id         UUID NOT NULL REFERENCES approval_workflows(id) ON DELETE CASCADE,
    step_order          INTEGER NOT NULL,
    approver_role_id    UUID REFERENCES global_roles(id),
    sla_hours           INTEGER,                                 -- délai avant escalade automatique
    escalates_to_step_order INTEGER,
    UNIQUE (workflow_id, step_order)
);

-- Instance : le processus Temporal.io fait autorité sur l'état d'exécution ;
-- cette table est la projection lisible en base pour l'UI (Approval.dc.html).
CREATE TABLE approval_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id         UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    workflow_id         UUID NOT NULL REFERENCES approval_workflows(id),
    temporal_workflow_id TEXT NOT NULL UNIQUE,                   -- pointeur vers l'exécution Temporal
    requested_by        UUID NOT NULL REFERENCES users(id),
    current_step_order  INTEGER NOT NULL DEFAULT 1,
    status              TEXT NOT NULL DEFAULT 'en_cours'
                        CHECK (status IN ('en_cours','approuve','rejete','annule')),
    sla_deadline_at      TIMESTAMPTZ,
    submitted_version_no INTEGER,                               -- version figée à la soumission (DiffApproval)
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ
);

CREATE TABLE approval_actions (                                 -- DiffApproval.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_request_id UUID NOT NULL REFERENCES approval_requests(id) ON DELETE CASCADE,
    step_order          INTEGER NOT NULL,
    actor_id            UUID NOT NULL REFERENCES users(id),
    decision            TEXT NOT NULL CHECK (decision IN ('approuve','rejete','reassigne')),
    comment             TEXT,
    acted_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- 6. AUDIT, INTÉGRATIONS, API
-- ============================================================================

CREATE TABLE audit_log_events (                                 -- AuditLog.dc.html — append-only
    id                  BIGSERIAL PRIMARY KEY,
    actor_id            UUID REFERENCES users(id),
    actor_is_system     BOOLEAN NOT NULL DEFAULT false,
    action              TEXT NOT NULL,                           -- "document.published", "access.granted"...
    resource_type       TEXT NOT NULL,
    resource_id         UUID,
    metadata            JSONB,
    ip_address          INET,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Aucune contrainte de mise à jour : appliquer en base un trigger REVOKE UPDATE, DELETE
-- au rôle applicatif pour garantir l'append-only.

CREATE TABLE siem_connectors (                                  -- Integrations.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider            TEXT NOT NULL CHECK (provider IN ('splunk','datadog','sentinel')),
    config              JSONB NOT NULL,                          -- endpoint, format (HEC/CEF/JSON), clé chiffrée côté secret manager
    status              TEXT NOT NULL DEFAULT 'disconnected' CHECK (status IN ('connected','disconnected','error')),
    connected_at        TIMESTAMPTZ
);

CREATE TABLE siem_deliveries (                                  -- outbox SIEM — consommée par le worker Go (même pattern que webhook_deliveries)
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connector_id        UUID NOT NULL REFERENCES siem_connectors(id) ON DELETE CASCADE,
    audit_event_id      BIGINT NOT NULL REFERENCES audit_log_events(id),
    event_type          TEXT NOT NULL,
    payload             JSONB NOT NULL,
    status              TEXT NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending','delivered','failed','retrying')),
    attempt_count       INTEGER NOT NULL DEFAULT 0,
    last_response_code  INTEGER,
    delivered_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE webhook_endpoints (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    url                 TEXT NOT NULL,
    secret_hash         TEXT NOT NULL,
    subscribed_events   TEXT[] NOT NULL,
    status              TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE webhook_deliveries (                               -- WebhookDeliveries.dc.html — consommée par le worker Go
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id         UUID NOT NULL REFERENCES webhook_endpoints(id) ON DELETE CASCADE,
    event_type          TEXT NOT NULL,
    payload             JSONB NOT NULL,
    status              TEXT NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending','delivered','failed','retrying')),
    attempt_count       INTEGER NOT NULL DEFAULT 0,
    last_response_code  INTEGER,
    delivered_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (                                         -- GenerateApiKey.dc.html (niveau déploiement)
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT NOT NULL,
    key_hash            TEXT NOT NULL,
    key_prefix          TEXT NOT NULL,                           -- affiché en clair dans l'UI, ex. "sk_live_a1b2..."
    created_by          UUID REFERENCES users(id),
    last_used_at        TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE personal_access_tokens (                           -- GeneratePersonalToken.dc.html (niveau utilisateur)
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name                TEXT NOT NULL,
    token_hash          TEXT NOT NULL,
    scopes              TEXT[] NOT NULL DEFAULT '{}',
    last_used_at        TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- 7. CONFORMITÉ, RÉTENTION, RGPD
-- ============================================================================

CREATE TABLE retention_policies (                               -- Retention.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    applies_to          TEXT NOT NULL,                           -- "space", "tag", "document_type"
    scope_ref_id        UUID,
    retention_days      INTEGER NOT NULL,
    action_on_expiry    TEXT NOT NULL CHECK (action_on_expiry IN ('archive','delete','review_flag')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE trash_items (                                      -- Trash.dc.html — index Corbeille (affichage)
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_type       TEXT NOT NULL CHECK (resource_type IN ('document','folder','space')),
    resource_id         UUID NOT NULL,
    resource_snapshot   JSONB NOT NULL,                          -- affichage rapide (title…) — pas source de restauration
    deleted_by          UUID REFERENCES users(id),
    deleted_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    purge_at            TIMESTAMPTZ NOT NULL,                     -- deleted_at + 30 jours
    UNIQUE (resource_type, resource_id)
);

CREATE TABLE personal_data_export_requests (                    -- ExportPersonalData.dc.html — RGPD
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    categories          TEXT[] NOT NULL,                         -- profil, documents, commentaires, activité, attestations
    format              TEXT NOT NULL CHECK (format IN ('zip_json','csv')),
    status              TEXT NOT NULL DEFAULT 'en_preparation'
                        CHECK (status IN ('en_preparation','pret','expire')),
    file_url            TEXT,
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    ready_at            TIMESTAMPTZ,
    link_expires_at     TIMESTAMPTZ                              -- lien valable 30 jours
);

CREATE TABLE folder_exports (                                   -- ExportFolder.dc.html, ExportTag.dc.html, Export.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope_type          TEXT NOT NULL CHECK (scope_type IN ('folder','tag','space','document')),
    scope_ref_id        UUID NOT NULL,
    requested_by        UUID REFERENCES users(id),
    status              TEXT NOT NULL DEFAULT 'en_cours' CHECK (status IN ('en_cours','pret','echec')),
    file_url            TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- 8. MARQUE, STATUT PUBLIC
-- ============================================================================
--
-- La facturation/l'abonnement n'a pas sa place ici : dans un déploiement
-- VPC-per-client, ce client EST le contrat. Il n'y a pas de "compte" à
-- débiter depuis l'intérieur de sa propre base — ce serait au client de
-- lire et modifier ses propres données de facturation, ce qui n'a aucun
-- sens opérationnel. La facturation, les licences et le suivi de plan
-- vivent dans le control plane externe (voir note d'en-tête), qui
-- interroge chaque déploiement via une API d'health-check plutôt que
-- l'inverse. Aucune table `billing_subscriptions` ici.

CREATE TABLE branding_settings (                                -- Branding.dc.html — singleton (un seul déploiement)
    id                  BOOLEAN PRIMARY KEY DEFAULT true CHECK (id),
    logo_url            TEXT,
    favicon_url         TEXT,
    accent_color        TEXT,
    hide_powered_by     BOOLEAN NOT NULL DEFAULT false,
    custom_domain       TEXT UNIQUE,
    custom_domain_verified BOOLEAN NOT NULL DEFAULT false,
    sender_name         TEXT,
    sender_email        TEXT,
    spf_dkim_verified   BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE status_page_components (                           -- StatusPage.dc.html (page publique)
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT NOT NULL,                           -- "API", "Webhooks", "SSO"...
    current_status      TEXT NOT NULL DEFAULT 'operationnel'
                        CHECK (current_status IN ('operationnel','degrade','panne'))
);

CREATE TABLE status_page_incidents (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id        UUID NOT NULL REFERENCES status_page_components(id) ON DELETE CASCADE,
    title               TEXT NOT NULL,
    status              TEXT NOT NULL CHECK (status IN ('surveillance','identifie','resolu')),
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ
);

CREATE TABLE status_page_incident_updates (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id         UUID NOT NULL REFERENCES status_page_incidents(id) ON DELETE CASCADE,
    message             TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- 9. DIVERS UX : FAVORIS, NOTIFICATIONS, CENTRE D'AIDE
-- ============================================================================

CREATE TABLE favorites (                                        -- Favorites.dc.html
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resource_type       TEXT NOT NULL CHECK (resource_type IN ('document','folder','space')),
    resource_id         UUID NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, resource_type, resource_id)
);

CREATE TABLE notifications (                                    -- Notifications.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type                TEXT NOT NULL,                           -- "approval_request","comment","publish","attestation_due"
    payload             JSONB NOT NULL,
    read_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE help_categories (                                  -- HelpCenter.dc.html — catalogue fourni par défaut
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT NOT NULL
);

CREATE TABLE help_articles (                                    -- HelpArticle.dc.html
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id         UUID NOT NULL REFERENCES help_categories(id) ON DELETE CASCADE,
    title               TEXT NOT NULL,
    body                JSONB NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- 10. INDEX RECOMMANDÉS
-- ============================================================================

CREATE INDEX idx_documents_space          ON documents (space_id);
CREATE INDEX idx_documents_folder         ON documents (folder_id);
CREATE INDEX idx_documents_status         ON documents (status);
CREATE INDEX idx_document_versions_doc    ON document_versions (document_id, version_no DESC);
CREATE INDEX idx_document_links_target    ON document_links (target_id);
CREATE INDEX idx_document_links_source_space ON document_links (source_space_id);
CREATE INDEX idx_document_comments_doc    ON document_comments (document_id);
CREATE INDEX idx_audit_log_created        ON audit_log_events (created_at DESC);
CREATE INDEX idx_audit_log_resource       ON audit_log_events (resource_type, resource_id);
CREATE INDEX idx_webhook_deliveries_status ON webhook_deliveries (status) WHERE status IN ('pending','retrying');
CREATE INDEX idx_siem_deliveries_status ON siem_deliveries (status) WHERE status IN ('pending','retrying');
CREATE INDEX idx_approval_requests_document ON approval_requests (document_id);
CREATE INDEX idx_approval_requests_status  ON approval_requests (status) WHERE status = 'en_cours';
CREATE INDEX idx_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;
CREATE INDEX idx_documents_not_deleted ON documents (space_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_folders_not_deleted ON folders (space_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_spaces_not_deleted ON spaces (id) WHERE deleted_at IS NULL;
CREATE INDEX idx_trash_items_purge ON trash_items (purge_at);
