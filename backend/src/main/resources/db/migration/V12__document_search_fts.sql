-- Full-text search Postgres (titre + corps JSONB + tags) — pas de moteur externe.
-- Index GIN + triggers : l'index suit create/update (et tags) sans toucher au versioning applicatif.

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS search_vector tsvector;

CREATE OR REPLACE FUNCTION documents_rebuild_search_vector(p_document_id UUID)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    tag_text TEXT;
BEGIN
    SELECT coalesce(string_agg(t.name, ' '), '')
      INTO tag_text
      FROM document_tags dt
      JOIN tags t ON t.id = dt.tag_id
     WHERE dt.document_id = p_document_id;

    UPDATE documents d
       SET search_vector =
               setweight(to_tsvector('french', coalesce(d.title, '')), 'A')
            || setweight(to_tsvector('french', coalesce(d.body::text, '')), 'B')
            || setweight(to_tsvector('french', coalesce(tag_text, '')), 'C')
     WHERE d.id = p_document_id;
END;
$$;

CREATE OR REPLACE FUNCTION trg_documents_search_vector()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM documents_rebuild_search_vector(NEW.id);
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION trg_document_tags_search_vector()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM documents_rebuild_search_vector(OLD.document_id);
        RETURN OLD;
    END IF;
    PERFORM documents_rebuild_search_vector(NEW.document_id);
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS documents_search_vector_aiud ON documents;
CREATE TRIGGER documents_search_vector_aiud
    AFTER INSERT OR UPDATE OF title, body ON documents
    FOR EACH ROW
    EXECUTE FUNCTION trg_documents_search_vector();

DROP TRIGGER IF EXISTS document_tags_search_vector_aiud ON document_tags;
CREATE TRIGGER document_tags_search_vector_aiud
    AFTER INSERT OR UPDATE OR DELETE ON document_tags
    FOR EACH ROW
    EXECUTE FUNCTION trg_document_tags_search_vector();

-- Backfill documents existants
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT id FROM documents LOOP
        PERFORM documents_rebuild_search_vector(r.id);
    END LOOP;
END $$;

CREATE INDEX IF NOT EXISTS idx_documents_search_vector
    ON documents USING GIN (search_vector);
