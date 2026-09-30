INSERT INTO flyway_schema_history (
  installed_rank, version, description, type, script, checksum, installed_by, execution_time, success
)
SELECT * FROM (VALUES
  (9,  '9',  'document doc type', 'SQL', 'V9__document_doc_type.sql', NULL::integer, 'manual', 0, true),
  (10, '10', 'approval workflow active scope unique', 'SQL', 'V10__approval_workflow_active_scope_unique.sql', NULL, 'manual', 0, true),
  (11, '11', 'space owners and group created by', 'SQL', 'V11__space_owners_and_group_created_by.sql', NULL, 'manual', 0, true),
  (12, '12', 'document search fts', 'SQL', 'V12__document_search_fts.sql', NULL, 'manual', 0, true),
  (13, '13', 'document versions git sha col', 'SQL', 'V13__document_versions_git_commit.sql', NULL, 'manual', 0, true),
  (14, '14', 'documents git head sha', 'SQL', 'V14__documents_git_head_sha.sql', NULL, 'manual', 0, true),
  (15, '15', 'space external reference', 'SQL', 'V15__space_external_reference.sql', NULL, 'manual', 0, true),
  (16, '16', 'document edit locks', 'SQL', 'V16__document_edit_locks.sql', NULL, 'manual', 0, true)
) AS v(installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
WHERE NOT EXISTS (
  SELECT 1 FROM flyway_schema_history f WHERE f.version = v.version
);
