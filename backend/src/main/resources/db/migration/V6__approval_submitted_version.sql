-- Lien version ↔ demande d'approbation (diff DiffApproval.dc.html)
ALTER TABLE approval_requests
  ADD COLUMN IF NOT EXISTS submitted_version_no INTEGER;
