# Commentaires historiques dans les migrations Flyway (V1–V21)

Certaines migrations appliquées (`backend/src/main/resources/db/migration/V1__*.sql` … `V21__*.sql`)
contiennent encore des commentaires mentionnant l’ancienne marque « Meridian ».

## Effet

- Ces chaînes sont **uniquement des commentaires SQL** (ou exemples dans des commentaires).
- Elles **n’affectent pas** le schéma runtime, les données, ni le branding produit.
- Le nom d’organisation / d’affichage de l’instance vient de la config
  (`socle.instance.display-name`), jamais d’une constante hardcodée.

## Règle

**Ne pas modifier** les migrations déjà appliquées (V1–V21) pour « nettoyer » la marque.
Réécrire une migration déjà déployée casse le checksum Flyway et force des réparations
dangereuses en production.

Toute évolution de schéma passe par une **nouvelle** migration `Vn__…` (n > 21).

Voir aussi le garde-fou `MeridianBrandGuardTest` (les V1–V21 restent hors scope du scan).
