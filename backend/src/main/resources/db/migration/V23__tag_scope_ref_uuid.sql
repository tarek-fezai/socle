-- Portée tag : scope_ref stocké uniquement en UUID (plus de noms).
-- Résout les valeurs existantes qui sont des noms de tag uniques.

UPDATE approval_role_assignments ara
   SET scope_ref = t.id::text
  FROM tags t
 WHERE ara.scope_type = 'tag'
   AND ara.scope_ref IS NOT NULL
   AND ara.scope_ref !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
   AND lower(t.name) = lower(ara.scope_ref)
   AND (
         SELECT count(*) FROM tags t2 WHERE lower(t2.name) = lower(ara.scope_ref)
       ) = 1;

-- Supprime les attributions tag encore non résolues (nom ambigu / introuvable)
DELETE FROM approval_role_assignments
 WHERE scope_type = 'tag'
   AND scope_ref IS NOT NULL
   AND scope_ref !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
