-- Migrations OpenFGA journalisées (exécution unique) + backfill created_by si null.

CREATE TABLE IF NOT EXISTS authz_migrations (
    name       TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    report     JSONB
);

COMMENT ON TABLE authz_migrations IS
    'Journal des migrations de tuples OpenFGA (ex. visibility-v1) — une seule application effective';

-- Heuristique : si created_by est null, tenter de le déduire de la première version (author).
UPDATE documents d
   SET created_by = v.author_id
  FROM (
        SELECT DISTINCT ON (document_id) document_id, author_id
          FROM document_versions
         WHERE author_id IS NOT NULL
         ORDER BY document_id, version_no ASC
       ) v
 WHERE d.id = v.document_id
   AND d.created_by IS NULL;
