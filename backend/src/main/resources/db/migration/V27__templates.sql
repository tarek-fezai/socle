-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Modèles de pages : portée global|espace, métadonnées, provenance documents, seed.

-- ---------------------------------------------------------------------------
-- Évolution table templates (V1 : id, name, body_template, created_by, created_at)
-- ---------------------------------------------------------------------------
ALTER TABLE templates
    ADD COLUMN IF NOT EXISTS space_id UUID REFERENCES spaces(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS doc_type TEXT,
    ADD COLUMN IF NOT EXISTS default_tag_ids UUID[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS seed_key TEXT;

-- Renommer body_template → body (configuration TipTap, toujours en Postgres)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'templates' AND column_name = 'body_template'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'templates' AND column_name = 'body'
    ) THEN
        ALTER TABLE templates RENAME COLUMN body_template TO body;
    END IF;
END $$;

ALTER TABLE templates
    ALTER COLUMN body SET DEFAULT '{"type":"doc","content":[]}'::jsonb;

COMMENT ON COLUMN templates.space_id IS
    'NULL = modèle global (ADMINISTRATEUR_SYSTEME) ; sinon modèle d''espace (owners)';
COMMENT ON COLUMN templates.body IS
    'Corps TipTap JSON — toujours en PostgreSQL, quel que soit socle.storage.provider';
COMMENT ON COLUMN templates.seed_key IS
    'Clé stable des modèles de démarrage ; soft-delete conserve la clé (pas de recreação)';

CREATE UNIQUE INDEX IF NOT EXISTS templates_seed_key_uidx
    ON templates (seed_key)
    WHERE seed_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS templates_space_active_idx
    ON templates (space_id)
    WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- Provenance document ← modèle (pas de mise à jour cascade)
-- ---------------------------------------------------------------------------
ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS template_id UUID REFERENCES templates(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS template_version INTEGER;

COMMENT ON COLUMN documents.template_id IS
    'Modèle d''origine (null si page vierge ou modèle depuis supprimé) — snapshot, pas de sync';
COMMENT ON COLUMN documents.template_version IS
    'Version du modèle au moment de la création';

-- ---------------------------------------------------------------------------
-- Seed modèles globaux (structure seule) — idempotent via seed_key
-- Ne recrée jamais un modèle soft-supprimé (seed_key toujours présent).
-- ---------------------------------------------------------------------------
INSERT INTO templates (id, name, description, doc_type, body, space_id, version, seed_key, created_at, updated_at)
SELECT gen_random_uuid(), v.name, v.description, v.doc_type, v.body::jsonb, NULL, 1, v.seed_key, now(), now()
FROM (VALUES
    (
        'seed.procedure',
        'Procédure',
        'Étapes opérationnelles séquencées, avec rôles et points de contrôle',
        'procedure',
        $json${"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Procédure"}]},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Objectif"}]},{"type":"placeholder","attrs":{"hint":"Décrire l'objectif"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Périmètre"}]},{"type":"placeholder","attrs":{"hint":"Périmètre d'application"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Étapes"}]},{"type":"placeholder","attrs":{"hint":"Lister les étapes"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Rôles"}]},{"type":"placeholder","attrs":{"hint":"Rôles et responsabilités"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Points de contrôle"}]},{"type":"placeholder","attrs":{"hint":"Contrôles et critères"}}]}$json$
    ),
    (
        'seed.adr',
        'Décision d''architecture (ADR)',
        'Contexte, décision et conséquences d''un choix d''architecture',
        'adr',
        $json${"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Décision d'architecture"}]},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Contexte"}]},{"type":"placeholder","attrs":{"hint":"Contexte et contraintes"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Décision"}]},{"type":"placeholder","attrs":{"hint":"Décision retenue"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Conséquences"}]},{"type":"placeholder","attrs":{"hint":"Conséquences positives et négatives"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Statut"}]},{"type":"placeholder","attrs":{"hint":"Proposé / Accepté / Déprécié"}}]}$json$
    ),
    (
        'seed.meeting_minutes',
        'Compte rendu de réunion',
        'Ordre du jour, participants, décisions et actions',
        'compte_rendu',
        $json${"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Compte rendu de réunion"}]},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Participants"}]},{"type":"placeholder","attrs":{"hint":"Liste des participants"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Ordre du jour"}]},{"type":"placeholder","attrs":{"hint":"Points abordés"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Décisions"}]},{"type":"placeholder","attrs":{"hint":"Décisions prises"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Actions"}]},{"type":"placeholder","attrs":{"hint":"Actions, responsables, échéances"}}]}$json$
    ),
    (
        'seed.policy',
        'Politique',
        'Règles et principes normatifs, avec cycle d''approbation',
        'politique',
        $json${"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Politique"}]},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Objet"}]},{"type":"placeholder","attrs":{"hint":"Objet de la politique"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Champ d'application"}]},{"type":"placeholder","attrs":{"hint":"Personnes et systèmes concernés"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Principes"}]},{"type":"placeholder","attrs":{"hint":"Principes et règles"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Responsabilités"}]},{"type":"placeholder","attrs":{"hint":"Responsabilités"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Références"}]},{"type":"placeholder","attrs":{"hint":"Références associées"}}]}$json$
    ),
    (
        'seed.runbook',
        'Runbook d''exploitation',
        'Procédure opérationnelle de run / incident',
        'runbook',
        $json${"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Runbook d'exploitation"}]},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Symptômes"}]},{"type":"placeholder","attrs":{"hint":"Signes observables"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Impact"}]},{"type":"placeholder","attrs":{"hint":"Impact service / utilisateurs"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Diagnostic"}]},{"type":"placeholder","attrs":{"hint":"Étapes de diagnostic"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Remédiation"}]},{"type":"placeholder","attrs":{"hint":"Actions de remédiation"}},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Escalade"}]},{"type":"placeholder","attrs":{"hint":"Contacts et seuils d'escalade"}}]}$json$
    )
) AS v(seed_key, name, description, doc_type, body)
WHERE NOT EXISTS (
    SELECT 1 FROM templates t WHERE t.seed_key = v.seed_key
);
