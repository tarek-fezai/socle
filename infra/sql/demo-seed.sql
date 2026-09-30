-- =============================================================================
-- Jeu de données de démo / test — Socle (dev local)
-- Idempotent : ON CONFLICT / WHERE NOT EXISTS.
-- Aligné sur les comptes Keycloak realm `socle` (infra/keycloak/realm-socle.json).
--
-- Usage :
--   Get-Content infra/sql/demo-seed.sql -Raw |
--     docker exec -i socle-postgres psql -U socle -d socle_core -v ON_ERROR_STOP=1
--
-- Note : OpenFGA n'est PAS peuplé ici — sans tuples FGA, l'UI peut masquer
-- documents/espaces même présents en SQL. Voir infra/scripts/bootstrap-openfga.ps1
-- puis grants via API / AccessController.
-- =============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- Libère les emails Keycloak / démo tenus par d'anciens UUID (FK préservées)
-- ---------------------------------------------------------------------------
UPDATE users
   SET email = lower(email) || '.legacy.' || substr(replace(id::text, '-', ''), 1, 8)
 WHERE lower(email) IN (
   'contributeur@example.com',
   'auditeur@example.com',
   'integrateur@example.com',
   'analyste@example.com',
   'admin@example.com',
   'analyste@socle.local',
   'admin@socle.local'
 )
 AND id NOT IN (
   '11111111-1111-1111-1111-111111111111',
   '44444444-4444-4444-4444-444444444444',
   '55555555-5555-5555-5555-555555555555',
   '22222222-2222-2222-2222-222222222222',
   '33333333-3333-3333-3333-333333333333'
);

-- ---------------------------------------------------------------------------
-- Identité affichée du déploiement (singleton) — org fictive
-- ---------------------------------------------------------------------------
INSERT INTO platform_settings (id, org_name, org_slug, plan_tier)
VALUES (true, 'Organisation Démo', 'organisation-demo', 'enterprise')
ON CONFLICT (id) DO UPDATE SET
  org_name = EXCLUDED.org_name,
  org_slug = EXCLUDED.org_slug;

-- ---------------------------------------------------------------------------
-- Utilisateurs Keycloak (id = sub JWT)
-- ---------------------------------------------------------------------------
INSERT INTO users (id, email, display_name, avatar_initials, status, created_at)
VALUES
  ('11111111-1111-1111-1111-111111111111', 'contributeur@example.com', 'Camille Contributeur', 'CC', 'active', now()),
  ('44444444-4444-4444-4444-444444444444', 'auditeur@example.com',     'Awa Auditeur',         'AA', 'active', now()),
  ('55555555-5555-5555-5555-555555555555', 'integrateur@example.com',  'Inès Intégrateur',     'II', 'active', now()),
  ('22222222-2222-2222-2222-222222222222', 'analyste@example.com',     'Camille Dupont',       'CD', 'active', now()),
  ('33333333-3333-3333-3333-333333333333', 'admin@example.com',        'Alex Martin',          'AM', 'active', now())
ON CONFLICT (id) DO UPDATE SET
  email = EXCLUDED.email,
  display_name = EXCLUDED.display_name,
  avatar_initials = EXCLUDED.avatar_initials,
  status = 'active';

-- ---------------------------------------------------------------------------
-- Espaces
-- ---------------------------------------------------------------------------
INSERT INTO spaces (id, name, color, external_reference, created_at)
VALUES
  ('00000000-0000-0000-0000-000000000001', 'Espace par défaut',  '#2F6FED', 'open',       now()),
  ('00000000-0000-0000-0000-000000000002', 'Identité & accès',   '#0F766E', 'open',       now()),
  ('00000000-0000-0000-0000-000000000003', 'Infrastructure',     '#B45309', 'open',       now()),
  ('00000000-0000-0000-0000-000000000004', 'Conformité (restreint)', '#7C3AED', 'restricted', now())
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  color = EXCLUDED.color,
  external_reference = EXCLUDED.external_reference,
  deleted_at = NULL;

-- Gouvernance : contributeur = responsible sur défaut + IAM ; admin sur infra + conformité
INSERT INTO space_owners (space_id, user_id, is_responsible, created_at)
VALUES
  ('00000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', true,  now()),
  ('00000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', true,  now()),
  ('00000000-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', false, now()),
  ('00000000-0000-0000-0000-000000000003', '33333333-3333-3333-3333-333333333333', true,  now()),
  ('00000000-0000-0000-0000-000000000004', '33333333-3333-3333-3333-333333333333', true,  now()),
  ('00000000-0000-0000-0000-000000000004', '22222222-2222-2222-2222-222222222222', false, now())
ON CONFLICT (space_id, user_id) DO UPDATE SET is_responsible = EXCLUDED.is_responsible;

-- ---------------------------------------------------------------------------
-- Groupes
-- ---------------------------------------------------------------------------
INSERT INTO groups (id, name, created_by, created_at)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 'Équipe conformité', '22222222-2222-2222-2222-222222222222', now()),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', 'Équipe plateforme', '33333333-3333-3333-3333-333333333333', now())
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  created_by = COALESCE(groups.created_by, EXCLUDED.created_by);

INSERT INTO group_members (group_id, user_id)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', '22222222-2222-2222-2222-222222222222'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', '11111111-1111-1111-1111-111111111111'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', '33333333-3333-3333-3333-333333333333'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', '11111111-1111-1111-1111-111111111111')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- Tags
-- ---------------------------------------------------------------------------
INSERT INTO tags (id, name, color)
VALUES
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1', 'IAM',      '#2F6FED'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2', 'RGPD',     '#0F766E'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb3', 'Critique', '#B91C1C'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb4', 'Runbook',  '#B45309')
ON CONFLICT (name) DO UPDATE SET color = EXCLUDED.color;

-- ---------------------------------------------------------------------------
-- Dossiers
-- ---------------------------------------------------------------------------
INSERT INTO folders (id, space_id, parent_folder_id, title, status, created_at, updated_at, updated_by)
VALUES
  ('cccccccc-cccc-cccc-cccc-ccccccccccc1',
   '00000000-0000-0000-0000-000000000002', NULL,
   'Procédures IAM', 'active', now(), now(), '11111111-1111-1111-1111-111111111111'),
  ('cccccccc-cccc-cccc-cccc-ccccccccccc2',
   '00000000-0000-0000-0000-000000000003', NULL,
   'Runbooks prod', 'active', now(), now(), '33333333-3333-3333-3333-333333333333'),
  ('cccccccc-cccc-cccc-cccc-ccccccccccc3',
   '00000000-0000-0000-0000-000000000004', NULL,
   'Politiques', 'active', now(), now(), '22222222-2222-2222-2222-222222222222')
ON CONFLICT (id) DO UPDATE SET
  title = EXCLUDED.title,
  deleted_at = NULL;

-- Corps TipTap minimal réutilisable
-- (fonction inline via literal JSON)

-- ---------------------------------------------------------------------------
-- Documents (stables pour démo UI / recherche / staleness / audit)
-- ---------------------------------------------------------------------------
INSERT INTO documents (
  id, space_id, folder_id, title, body, doc_type, status, language,
  owner_id, current_version_no, created_by, created_at, updated_at,
  reliability_score, is_mandatory_ack
)
VALUES
  (
    'dddddddd-dddd-dddd-dddd-ddddddddddd1',
    '00000000-0000-0000-0000-000000000002',
    'cccccccc-cccc-cccc-cccc-ccccccccccc1',
    'Procédure de provisionnement compte',
    '{"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Provisionnement compte"}]},{"type":"paragraph","content":[{"type":"text","text":"Étapes pour créer un compte Keycloak et synchroniser OpenFGA."}]},{"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Créer l''utilisateur dans le realm socle"}]}]},{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Accorder les relations space/document via Access"}]}]}]}]}'::jsonb,
    'procedure', 'valide', 'fr',
    '11111111-1111-1111-1111-111111111111', 1,
    '11111111-1111-1111-1111-111111111111',
    now() - interval '10 days', now() - interval '2 days',
    82.50, false
  ),
  (
    'dddddddd-dddd-dddd-dddd-ddddddddddd2',
    '00000000-0000-0000-0000-000000000002',
    'cccccccc-cccc-cccc-cccc-ccccccccccc1',
    'Politique de révision des accès',
    '{"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Revue trimestrielle des accès"}]},{"type":"paragraph","content":[{"type":"text","text":"Les owners d''espace vérifient les grants OpenFGA chaque trimestre."}]}]}'::jsonb,
    'politique', 'en_revue', 'fr',
    '22222222-2222-2222-2222-222222222222', 1,
    '22222222-2222-2222-2222-222222222222',
    now() - interval '5 days', now() - interval '1 day',
    61.00, true
  ),
  (
    'dddddddd-dddd-dddd-dddd-ddddddddddd3',
    '00000000-0000-0000-0000-000000000003',
    'cccccccc-cccc-cccc-cccc-ccccccccccc2',
    'Runbook incident Postgres',
    '{"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Incident Postgres"}]},{"type":"paragraph","content":[{"type":"text","text":"Checklist : health container, connexions, WAL, bascule."}]}]}'::jsonb,
    'runbook', 'brouillon', 'fr',
    '33333333-3333-3333-3333-333333333333', 1,
    '33333333-3333-3333-3333-333333333333',
    now() - interval '120 days', now() - interval '100 days',
    40.00, false
  ),
  (
    'dddddddd-dddd-dddd-dddd-ddddddddddd4',
    '00000000-0000-0000-0000-000000000004',
    'cccccccc-cccc-cccc-cccc-ccccccccccc3',
    'Charte de confidentialité interne',
    '{"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Confidentialité"}]},{"type":"paragraph","content":[{"type":"text","text":"Document sensible — espace en external_reference restricted."}]}]}'::jsonb,
    'politique', 'valide', 'fr',
    '22222222-2222-2222-2222-222222222222', 1,
    '33333333-3333-3333-3333-333333333333',
    now() - interval '30 days', now() - interval '3 days',
    90.00, true
  ),
  (
    'dddddddd-dddd-dddd-dddd-ddddddddddd5',
    '00000000-0000-0000-0000-000000000002',
    'cccccccc-cccc-cccc-cccc-ccccccccccc1',
    'Composite — référence procédure IAM',
    '{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Ce document inclut une transclusion :"}]},{"type":"transclusion","attrs":{"documentId":"dddddddd-dddd-dddd-dddd-ddddddddddd1"}}]}'::jsonb,
    'procedure', 'brouillon', 'fr',
    '11111111-1111-1111-1111-111111111111', 1,
    '11111111-1111-1111-1111-111111111111',
    now() - interval '1 day', now() - interval '1 day',
    NULL, false
  )
ON CONFLICT (id) DO UPDATE SET
  title = EXCLUDED.title,
  body = EXCLUDED.body,
  doc_type = EXCLUDED.doc_type,
  status = EXCLUDED.status,
  folder_id = EXCLUDED.folder_id,
  space_id = EXCLUDED.space_id,
  owner_id = EXCLUDED.owner_id,
  updated_at = EXCLUDED.updated_at,
  deleted_at = NULL;

-- Versions courantes (miroir body)
INSERT INTO document_versions (id, document_id, version_no, body_snapshot, author_id, change_summary, created_at)
VALUES
  ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1', 'dddddddd-dddd-dddd-dddd-ddddddddddd1', 1,
   (SELECT body FROM documents WHERE id = 'dddddddd-dddd-dddd-dddd-ddddddddddd1'),
   '11111111-1111-1111-1111-111111111111', 'Version initiale', now() - interval '10 days'),
  ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2', 'dddddddd-dddd-dddd-dddd-ddddddddddd2', 1,
   (SELECT body FROM documents WHERE id = 'dddddddd-dddd-dddd-dddd-ddddddddddd2'),
   '22222222-2222-2222-2222-222222222222', 'Version initiale', now() - interval '5 days'),
  ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee3', 'dddddddd-dddd-dddd-dddd-ddddddddddd3', 1,
   (SELECT body FROM documents WHERE id = 'dddddddd-dddd-dddd-dddd-ddddddddddd3'),
   '33333333-3333-3333-3333-333333333333', 'Version initiale', now() - interval '120 days'),
  ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee4', 'dddddddd-dddd-dddd-dddd-ddddddddddd4', 1,
   (SELECT body FROM documents WHERE id = 'dddddddd-dddd-dddd-dddd-ddddddddddd4'),
   '33333333-3333-3333-3333-333333333333', 'Version initiale', now() - interval '30 days'),
  ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee5', 'dddddddd-dddd-dddd-dddd-ddddddddddd5', 1,
   (SELECT body FROM documents WHERE id = 'dddddddd-dddd-dddd-dddd-ddddddddddd5'),
   '11111111-1111-1111-1111-111111111111', 'Version initiale', now() - interval '1 day')
ON CONFLICT (document_id, version_no) DO NOTHING;

-- Tags ↔ documents
INSERT INTO document_tags (document_id, tag_id)
SELECT d.id, t.id
FROM (VALUES
  ('dddddddd-dddd-dddd-dddd-ddddddddddd1'::uuid, 'IAM'),
  ('dddddddd-dddd-dddd-dddd-ddddddddddd1'::uuid, 'Critique'),
  ('dddddddd-dddd-dddd-dddd-ddddddddddd2'::uuid, 'IAM'),
  ('dddddddd-dddd-dddd-dddd-ddddddddddd2'::uuid, 'RGPD'),
  ('dddddddd-dddd-dddd-dddd-ddddddddddd3'::uuid, 'Runbook'),
  ('dddddddd-dddd-dddd-dddd-ddddddddddd3'::uuid, 'Critique'),
  ('dddddddd-dddd-dddd-dddd-ddddddddddd4'::uuid, 'RGPD')
) AS x(doc_id, tag_name)
JOIN documents d ON d.id = x.doc_id
JOIN tags t ON t.name = x.tag_name
ON CONFLICT DO NOTHING;

-- Rebuild FTS pour les docs seedés
SELECT documents_rebuild_search_vector(id)
FROM documents
WHERE id IN (
  'dddddddd-dddd-dddd-dddd-ddddddddddd1',
  'dddddddd-dddd-dddd-dddd-ddddddddddd2',
  'dddddddd-dddd-dddd-dddd-ddddddddddd3',
  'dddddddd-dddd-dddd-dddd-ddddddddddd4',
  'dddddddd-dddd-dddd-dddd-ddddddddddd5'
);

-- ---------------------------------------------------------------------------
-- Notifications (contributeur)
-- ---------------------------------------------------------------------------
INSERT INTO notifications (id, user_id, type, payload, read_at, created_at)
VALUES
  (
    'ffffffff-ffff-ffff-ffff-fffffffffff1',
    '11111111-1111-1111-1111-111111111111',
    'publish',
    '{"documentId":"dddddddd-dddd-dddd-dddd-ddddddddddd1","title":"Procédure de provisionnement compte"}'::jsonb,
    NULL,
    now() - interval '2 hours'
  ),
  (
    'ffffffff-ffff-ffff-ffff-fffffffffff2',
    '11111111-1111-1111-1111-111111111111',
    'approval_request',
    '{"documentId":"dddddddd-dddd-dddd-dddd-ddddddddddd2","title":"Politique de révision des accès"}'::jsonb,
    NULL,
    now() - interval '1 hour'
  )
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Journal d'audit (événements de démo — metadata-only)
-- ---------------------------------------------------------------------------
INSERT INTO audit_log_events (
  actor_id, actor_is_system, action, resource_type, resource_id, metadata, created_at
)
SELECT * FROM (VALUES
  (
    '11111111-1111-1111-1111-111111111111'::uuid, false,
    'space.created', 'space', '00000000-0000-0000-0000-000000000002'::uuid,
    '{"name":"Identité & accès"}'::jsonb,
    now() - interval '15 days'
  ),
  (
    '33333333-3333-3333-3333-333333333333'::uuid, false,
    'space.updated', 'space', '00000000-0000-0000-0000-000000000004'::uuid,
    '{"name":"Conformité (restreint)","externalReference":"restricted"}'::jsonb,
    now() - interval '12 days'
  ),
  (
    '11111111-1111-1111-1111-111111111111'::uuid, false,
    'document.created', 'document', 'dddddddd-dddd-dddd-dddd-ddddddddddd1'::uuid,
    '{"title":"Procédure de provisionnement compte","spaceId":"00000000-0000-0000-0000-000000000002","status":"brouillon"}'::jsonb,
    now() - interval '10 days'
  ),
  (
    '11111111-1111-1111-1111-111111111111'::uuid, false,
    'document.updated', 'document', 'dddddddd-dddd-dddd-dddd-ddddddddddd1'::uuid,
    '{"title":"Procédure de provisionnement compte","status":"valide","currentVersionNo":1}'::jsonb,
    now() - interval '2 days'
  ),
  (
    '22222222-2222-2222-2222-222222222222'::uuid, false,
    'group.created', 'group', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'::uuid,
    '{"name":"Équipe conformité"}'::jsonb,
    now() - interval '8 days'
  ),
  (
    '55555555-5555-5555-5555-555555555555'::uuid, false,
    'siem.connector_created', 'siem_connector', '99999999-9999-9999-9999-999999999901'::uuid,
    '{"provider":"splunk"}'::jsonb,
    now() - interval '3 days'
  )
) AS v(actor_id, actor_is_system, action, resource_type, resource_id, metadata, created_at)
WHERE NOT EXISTS (
  SELECT 1 FROM audit_log_events e
   WHERE e.action = v.action AND e.resource_id = v.resource_id
);

-- ---------------------------------------------------------------------------
-- Connecteur SIEM + webhook (config intégrateur — secrets redacted / hash)
-- ---------------------------------------------------------------------------
INSERT INTO siem_connectors (id, provider, config, status, connected_at)
VALUES (
  '99999999-9999-9999-9999-999999999901',
  'splunk',
  '{"endpoint":"https://hec.example.local/services/collector","format":"hec","hec_token_prefix":"demo-se…"}'::jsonb,
  'disconnected',
  NULL
)
ON CONFLICT (id) DO UPDATE SET config = EXCLUDED.config;

INSERT INTO webhook_endpoints (id, url, secret_hash, subscribed_events, status, created_at)
VALUES (
  '99999999-9999-9999-9999-999999999902',
  'https://hooks.example.local/socle',
  encode(digest('whsec_demo_seed_only_not_for_prod', 'sha256'), 'hex'),
  ARRAY['document.published','document.approved']::text[],
  'active',
  now()
)
ON CONFLICT (id) DO UPDATE SET
  url = EXCLUDED.url,
  status = 'active';

COMMIT;

-- Résumé
SELECT 'users' AS entity, count(*)::text AS n FROM users
UNION ALL SELECT 'spaces', count(*)::text FROM spaces WHERE deleted_at IS NULL
UNION ALL SELECT 'space_owners', count(*)::text FROM space_owners
UNION ALL SELECT 'groups', count(*)::text FROM groups
UNION ALL SELECT 'folders', count(*)::text FROM folders WHERE deleted_at IS NULL
UNION ALL SELECT 'documents_seed', count(*)::text FROM documents WHERE id::text LIKE 'dddddddd%'
UNION ALL SELECT 'tags', count(*)::text FROM tags
UNION ALL SELECT 'audit_events', count(*)::text FROM audit_log_events
UNION ALL SELECT 'notifications', count(*)::text FROM notifications
ORDER BY 1;
