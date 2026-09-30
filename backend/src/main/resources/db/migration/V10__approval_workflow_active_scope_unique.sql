-- Un seul workflow active par couple (espace, type) — NULL = « tous ».
-- Les brouillons (draft) peuvent partager un scope ; l'activation déclenche le conflit.
CREATE UNIQUE INDEX IF NOT EXISTS uq_approval_workflows_active_scope
    ON approval_workflows (
        (COALESCE(scope_space_id::text, '')),
        (COALESCE(lower(trim(scope_doc_type)), ''))
    )
    WHERE status = 'active';
