-- Fil d'activité équipe (home) — rétention 90 jours (purge planifiée).

CREATE TABLE activity_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_type TEXT NOT NULL, -- comment | edit_proposal | submission | publication
  actor_user_id UUID NOT NULL REFERENCES users(id),
  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  space_id UUID REFERENCES spaces(id),
  payload JSONB NOT NULL DEFAULT '{}',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX activity_events_created_idx ON activity_events(created_at DESC);
CREATE INDEX activity_events_document_idx ON activity_events(document_id, created_at DESC);
