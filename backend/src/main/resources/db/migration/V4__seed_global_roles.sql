-- Seed global_roles aligned with Keycloak realm roles (realm "socle").
INSERT INTO global_roles (id, name, description)
SELECT gen_random_uuid(), 'Administrateur système', 'administrateur-systeme'
WHERE NOT EXISTS (SELECT 1 FROM global_roles WHERE name = 'Administrateur système');

INSERT INTO global_roles (id, name, description)
SELECT gen_random_uuid(), 'Analyste conformité', 'analyste-conformite'
WHERE NOT EXISTS (SELECT 1 FROM global_roles WHERE name = 'Analyste conformité');

INSERT INTO global_roles (id, name, description)
SELECT gen_random_uuid(), 'Éditeur de documents', 'editeur-documents'
WHERE NOT EXISTS (SELECT 1 FROM global_roles WHERE name = 'Éditeur de documents');

INSERT INTO global_roles (id, name, description)
SELECT gen_random_uuid(), 'Lecteur de documents', 'lecteur-documents'
WHERE NOT EXISTS (SELECT 1 FROM global_roles WHERE name = 'Lecteur de documents');
