-- Décale les change_summary historiques (résumé de vN+1 était stocké sur l'archive vN).
-- SQL pur, tous providers, tous documents (y compris corbeille).
-- Flyway s'exécute avant le démarrage HTTP, sous verrou de migration (multi-réplicas sûr).
-- Si l'ancien ApplicationRunner a déjà décalé (marqueur authz_migrations), no-op.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM authz_migrations WHERE name = 'version-change-summary-v1'
    ) THEN
        RETURN;
    END IF;

    -- a) résumé courant = ancien résumé de la dernière archive
    UPDATE documents d
       SET current_change_summary = lv.change_summary
      FROM (
            SELECT DISTINCT ON (document_id)
                   document_id, change_summary
              FROM document_versions
             ORDER BY document_id, version_no DESC
           ) lv
     WHERE lv.document_id = d.id
       AND d.current_change_summary IS NULL;

    -- b) vN ← ancien résumé de vN−1 ; v1 ← NULL
    UPDATE document_versions v
       SET change_summary = s.prev
      FROM (
            SELECT id,
                   LAG(change_summary) OVER (
                       PARTITION BY document_id ORDER BY version_no
                   ) AS prev
              FROM document_versions
           ) s
     WHERE v.id = s.id;

    INSERT INTO authz_migrations (name, applied_at, report)
    VALUES (
        'version-change-summary-v1',
        now(),
        '{"source":"V37"}'::jsonb
    );
END $$;
