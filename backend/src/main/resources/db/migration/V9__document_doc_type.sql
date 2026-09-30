-- Type de document libre — apparié à approval_workflows.scope_doc_type à la soumission.
ALTER TABLE documents ADD COLUMN IF NOT EXISTS doc_type TEXT;
