-- SPDX-License-Identifier: LicenseRef-Socle-Proprietary
-- Empreinte du contenu soumis pour approbation (défense : ne pas publier une
-- version mutée pendant la revue). Rempli à recordSubmission ; comparé à
-- recordFinalDecision avant de passer le document en 'valide'.
ALTER TABLE approval_requests
  ADD COLUMN IF NOT EXISTS submitted_content_version_no INTEGER,
  ADD COLUMN IF NOT EXISTS submitted_git_head_sha TEXT;

COMMENT ON COLUMN approval_requests.submitted_content_version_no IS
  'documents.current_version_no au moment de la soumission (contenu sous revue)';
COMMENT ON COLUMN approval_requests.submitted_git_head_sha IS
  'documents.git_head_sha du contenu soumis (NULL en provider relational)';
