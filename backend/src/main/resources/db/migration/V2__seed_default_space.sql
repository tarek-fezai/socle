-- Default space so documents can be created without a full spaces UI yet.
INSERT INTO spaces (id, name, color, created_at)
SELECT '00000000-0000-0000-0000-000000000001'::uuid,
       'Espace par défaut',
       '#2F6FED',
       now()
WHERE NOT EXISTS (
    SELECT 1 FROM spaces WHERE id = '00000000-0000-0000-0000-000000000001'::uuid
);
