-- Campagnes d'attestation (page de lecture document).
-- V1 creait deja attestation_campaigns (target_group_id, due_date NOT NULL) et
-- attestation_acknowledgments (document_id, user_id) sans aucune ecriture applicative.
-- Cette migration fait evoluer ces tables (V1 reste inchange) :
--   * audience gelee a la creation (audience_type / audience_ref / audience_size = Y)
--   * un accuse appartient a une campagne et a une version du document
--   * au plus une campagne ouverte (closed_at IS NULL) par document

-- ---------------------------------------------------------------------------
-- Campagnes
-- ---------------------------------------------------------------------------
ALTER TABLE attestation_campaigns
    ADD COLUMN version_no    INT NOT NULL DEFAULT 1,
    ADD COLUMN audience_type TEXT,
    ADD COLUMN audience_ref  UUID,
    ADD COLUMN audience_size INT NOT NULL DEFAULT 0,
    ADD COLUMN created_by    UUID,
    ADD COLUMN closed_at     TIMESTAMPTZ;

UPDATE attestation_campaigns c
   SET version_no = COALESCE(
           (SELECT d.current_version_no FROM documents d WHERE d.id = c.document_id), 1),
       audience_type = CASE WHEN c.target_group_id IS NULL THEN 'space_members' ELSE 'group' END,
       audience_ref = c.target_group_id,
       audience_size = CASE
           WHEN c.target_group_id IS NULL THEN 0
           ELSE (SELECT count(*)::int FROM group_members gm WHERE gm.group_id = c.target_group_id)
       END;

ALTER TABLE attestation_campaigns
    ALTER COLUMN audience_type SET NOT NULL,
    ALTER COLUMN due_date DROP NOT NULL,
    DROP COLUMN target_group_id,
    ADD CONSTRAINT attestation_campaigns_audience_type_check
        CHECK (audience_type IN ('space_members', 'group')),
    ADD CONSTRAINT attestation_campaigns_audience_ref_check
        CHECK (audience_type <> 'group' OR audience_ref IS NOT NULL),
    ADD CONSTRAINT attestation_campaigns_audience_size_check
        CHECK (audience_size >= 0);

-- Donnees heritees : ne garder ouverte que la campagne la plus recente par document.
UPDATE attestation_campaigns c
   SET closed_at = now()
 WHERE c.closed_at IS NULL
   AND EXISTS (
       SELECT 1 FROM attestation_campaigns n
        WHERE n.document_id = c.document_id
          AND n.closed_at IS NULL
          AND (n.created_at, n.id) > (c.created_at, c.id)
   );

CREATE UNIQUE INDEX uq_attestation_campaign_open
    ON attestation_campaigns (document_id)
    WHERE closed_at IS NULL;

CREATE INDEX idx_attestation_campaigns_document
    ON attestation_campaigns (document_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Accuses de lecture
-- ---------------------------------------------------------------------------
ALTER TABLE attestation_acknowledgments
    ADD COLUMN campaign_id UUID REFERENCES attestation_campaigns(id) ON DELETE CASCADE,
    ADD COLUMN version_no  INT NOT NULL DEFAULT 1;

-- Rattachement des accuses existants a la campagne la plus recente du document.
UPDATE attestation_acknowledgments a
   SET campaign_id = (
           SELECT c.id FROM attestation_campaigns c
            WHERE c.document_id = a.document_id
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT 1),
       version_no = COALESCE(
           (SELECT d.current_version_no FROM documents d WHERE d.id = a.document_id), 1);

-- Accuses sans campagne : non rattachables (aucun chemin applicatif ne les ecrivait).
DELETE FROM attestation_acknowledgments WHERE campaign_id IS NULL;

ALTER TABLE attestation_acknowledgments
    ALTER COLUMN campaign_id SET NOT NULL,
    DROP COLUMN document_id,
    ADD CONSTRAINT uq_attestation_ack_campaign_user UNIQUE (campaign_id, user_id);
