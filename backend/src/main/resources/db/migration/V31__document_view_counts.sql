-- Compteurs de vues agrégés par document et par jour.
-- Aucune colonne user_id : pas de tracking individuel (voir docs/privacy.md).

CREATE TABLE document_view_counts (
  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  day DATE NOT NULL,
  count BIGINT NOT NULL DEFAULT 0 CHECK (count >= 0),
  PRIMARY KEY (document_id, day)
);
