-- Résumé de la version courante (non archivée).
-- Avant ce correctif, update/restore/soumission écrivaient le résumé de vN+1 sur
-- l'archive vN ; la version courante n'avait aucun résumé en mode relational.
--
-- Le décalage des change_summary historiques est fait par
-- VersionChangeSummaryBackfillService (ApplicationRunner, provider=relational
-- uniquement) : le provider est une propriété Spring globale, non stockée en SQL,
-- et un backfill SQL aveugle casserait les instances git (messages de commit déjà
-- corrects sur HEAD, métadonnées document_versions éventuellement divergentes).

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS current_change_summary TEXT;

COMMENT ON COLUMN documents.current_change_summary IS
    'Résumé de la version courante (current_version_no). '
    'Les versions archivées portent le leur dans document_versions.change_summary.';
