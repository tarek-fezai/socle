-- Favoris utilisateur (pages, espaces, dossiers).
-- L'autorisation de lecture reste décidée à la lecture via OpenFGA ;
-- les lignes inaccessibles sont omises, jamais supprimées ici.

CREATE TABLE favorites (
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  target_type TEXT NOT NULL CHECK (target_type IN ('document','space','folder')),
  target_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, target_type, target_id)
);

CREATE INDEX favorites_user_created_idx ON favorites(user_id, created_at DESC);
