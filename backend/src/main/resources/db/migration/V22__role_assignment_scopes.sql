-- Attributions de rôles d'approbation scopées (espace / tag / type de document).
-- Remplace la lecture applicative de user_global_roles (conservée, dépréciée).

CREATE TABLE approval_role_assignments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_id         UUID NOT NULL REFERENCES global_roles(id) ON DELETE CASCADE,
    subject_type    TEXT NOT NULL CHECK (subject_type IN ('user', 'group')),
    subject_id      UUID NOT NULL,
    scope_type      TEXT NOT NULL CHECK (scope_type IN ('all', 'space', 'tag', 'doc_type')),
    scope_ref       TEXT,
    granted_by      UUID REFERENCES users(id) ON DELETE SET NULL,
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT approval_role_assignments_scope_ck CHECK (
        (scope_type = 'all' AND scope_ref IS NULL)
        OR (scope_type <> 'all' AND scope_ref IS NOT NULL AND btrim(scope_ref) <> '')
    )
);

CREATE UNIQUE INDEX uq_approval_role_assignments
    ON approval_role_assignments (
        role_id,
        subject_type,
        subject_id,
        scope_type,
        COALESCE(scope_ref, '')
    );

CREATE INDEX idx_approval_role_assignments_subject
    ON approval_role_assignments (subject_type, subject_id);

CREATE INDEX idx_approval_role_assignments_role_scope
    ON approval_role_assignments (role_id, scope_type);

COMMENT ON TABLE approval_role_assignments IS
    'Attribution d''un rôle d''approbation (global_roles) à un user ou group, '
    'avec portée all|space|tag|doc_type. scope_ref = UUID espace/tag ou doc_type.';

COMMENT ON COLUMN approval_role_assignments.scope_ref IS
    'NULL si all ; UUID texte (space/tag) ou libellé doc_type sinon.';

-- Migration comportement préservé : anciennes attributions globales → scope all.
INSERT INTO approval_role_assignments (role_id, subject_type, subject_id, scope_type, scope_ref, granted_at)
SELECT ugr.role_id, 'user', ugr.user_id, 'all', NULL, now()
  FROM user_global_roles ugr
 WHERE NOT EXISTS (
     SELECT 1 FROM approval_role_assignments ara
      WHERE ara.role_id = ugr.role_id
        AND ara.subject_type = 'user'
        AND ara.subject_id = ugr.user_id
        AND ara.scope_type = 'all'
        AND ara.scope_ref IS NULL
 );

COMMENT ON TABLE user_global_roles IS
    'DEPRECATED depuis V22 — remplacé par approval_role_assignments (scope_type=all). '
    'Ne plus lire depuis le code applicatif. Suppression prévue dans une migration ultérieure '
    '(après période de double-écriture éventuelle / validation en prod).';
