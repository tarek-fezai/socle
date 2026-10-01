-- Production Postgres init (runs once on empty data volume).
-- Credentials come from POSTGRES_USER / POSTGRES_PASSWORD (Compose .env); do not embed passwords here.

CREATE DATABASE socle_core;
CREATE DATABASE openfga;
CREATE DATABASE temporal;
CREATE DATABASE temporal_visibility;

-- Also created for Compose profile `demo-idp` (Keycloak). Harmless if unused.
CREATE DATABASE keycloak;
