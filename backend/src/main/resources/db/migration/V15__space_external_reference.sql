-- external_reference : politique inter-workspace par espace (open | restricted)
ALTER TABLE spaces
    ADD COLUMN external_reference TEXT NOT NULL DEFAULT 'open'
        CHECK (external_reference IN ('open', 'restricted'));

COMMENT ON COLUMN spaces.external_reference IS
    'open = transclusion entrante depuis d''autres espaces autorisée ; '
    'restricted = rupture rétroactive des transclusions inter (OpenFGA inchangé pour accès direct).';

-- Première notification owners cible : une fois par paire (source → cible)
CREATE TABLE space_external_ref_notices (
    source_space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    target_space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    notified_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source_space_id, target_space_id),
    CHECK (source_space_id <> target_space_id)
);

COMMENT ON TABLE space_external_ref_notices IS
    'Déduplique la notif owners : première référence externe B→A uniquement.';
