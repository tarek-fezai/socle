# Identity providers

Socle découple l’identité IdP des rôles plateforme et de la clé utilisateur locale.
Le produit ne gère **aucun mot de passe** : authentification 100 % OIDC.

## Principes

| Concept | Détail |
|---------|--------|
| Clé utilisateur | `user_identities(issuer, subject)` — e-mail non unique |
| Rôles Socle | `CONTRIBUTEUR`, `AUDITEUR`, `INTEGRATEUR`, `ADMINISTRATEUR_SYSTEME` |
| Autorités Spring | `ROLE_contributeur`, `ROLE_auditeur`, … (noms historiques Keycloak) |
| Lecture JWT | **uniquement** `IdentityClaimsMapper` (`jwt.getClaim` / chemins type `realm_access.roles`) |
| Source des rôles | `socle.identity.role-source` : `CLAIMS` (défaut) \| `INTERNAL` \| `BOTH` |

## Configuration (`socle.identity`)

Défauts = comportement Keycloak historique (realm `socle` local) :

```yaml
socle:
  identity:
    issuer-uri: http://localhost:8081/realms/socle
    subject-claim: sub
    email-claim: email
    name-claim: name
    roles-claim: realm_access.roles
    role-source: CLAIMS
    default-role: CONTRIBUTEUR
    role-mapping:
      contributeur: CONTRIBUTEUR
      auditeur: AUDITEUR
      integrateur: INTEGRATEUR
      administrateur-systeme: ADMINISTRATEUR_SYSTEME
    client-id: socle-frontend
    scopes: [openid, profile, email]
```

Le frontend lit `GET /api/v1/public/auth-config` (`authority`, `clientId`, `scopes`, `displayName` / `organizationName`, endpoints optionnels) — **aucun build Vite par client**.

Le nom d'organisation / d'affichage de l'instance **n'est jamais hardcodé** : il vient de `socle.instance.display-name` (défaut `Socle`, surcharge via `SOCLE_INSTANCE_DISPLAY_NAME`). Exposé aussi sur `GET /api/v1/me` comme `organizationName`.

## Exemples IdP

### Keycloak (exemple de développement)

```yaml
socle.identity:
  issuer-uri: http://localhost:8081/realms/socle
  roles-claim: realm_access.roles
  # mapping 1:1 par défaut
```

Realm local : `infra/keycloak/realm-socle.dev.json` (**DEV UNIQUEMENT** — comptes de test). Comptes de démo : contributeur / auditeur / integrateur.

### Microsoft Entra ID

```yaml
socle.identity:
  issuer-uri: https://login.microsoftonline.com/<tenant-id>/v2.0
  subject-claim: oid
  email-claim: preferred_username   # ou email selon le token
  roles-claim: roles                # App roles ; sinon groups
  role-mapping:
    Socle.Contributeur: CONTRIBUTEUR
    Socle.Auditeur: AUDITEUR
    Socle.Integrateur: INTEGRATEUR
    Socle.AdministrateurSysteme: ADMINISTRATEUR_SYSTEME
```

### Okta (OIDC)

```yaml
socle.identity:
  issuer-uri: https://<org>.okta.com/oauth2/default
  roles-claim: groups
  role-mapping:
    Socle-Contributeur: CONTRIBUTEUR
    Socle-Auditeur: AUDITEUR
    Socle-Integrateur: INTEGRATEUR
    Socle-AdministrateurSysteme: ADMINISTRATEUR_SYSTEME
```

### Zitadel

```yaml
socle.identity:
  issuer-uri: https://<instance>.zitadel.cloud
  roles-claim: urn:zitadel:iam:org:project:roles   # ou claim custom
  role-mapping:
    auditeur: AUDITEUR
    # …
```

### Authentik

```yaml
socle.identity:
  issuer-uri: https://authentik.example/application/o/socle/
  roles-claim: groups
  role-mapping:
    socle-auditeur: AUDITEUR
```

### LemonLDAP::NG / autres OIDC

Tout IdP OIDC standard : pointer `issuer-uri`, ajuster `subject-claim` / `roles-claim` / `role-mapping`.

## SAML

L’écran Admin de la maquette peut mentionner « Okta · SAML 2.0 ». **Socle ne parle qu’OIDC** vers le navigateur et l’API.

Pour un IdP SAML (Okta SAML, ADFS, etc.), brancher un **broker OIDC** devant Socle :

- Keycloak Identity Provider SAML → clients OIDC Socle
- Zitadel / Authentik / LemonLDAP::NG en mode broker

Aucune implémentation SAML dans Socle.

## Modes `role-source`

| Mode | Comportement |
|------|----------------|
| `CLAIMS` (défaut) | Rôles IdP mappés + `default-role` |
| `INTERNAL` | Table `user_platform_roles` uniquement (claims ignorés) |
| `BOTH` | Union claims ∪ table |

### Bootstrap admin (`INTERNAL` / `BOTH`)

```yaml
socle.identity:
  role-source: INTERNAL
  bootstrap-admin-subjects:
    - "<subject OIDC du premier admin>"
```

Au **premier** login d’un subject listé → grant `ADMINISTRATEUR_SYSTEME` dans `user_platform_roles`.

Admin API : `GET|POST|DELETE /api/v1/admin/platform-roles` (réservé `ADMINISTRATEUR_SYSTEME`, audité).  
Impossible de retirer le **dernier** `ADMINISTRATEUR_SYSTEME`.

Ne pas confondre avec `global_roles` / `user_global_roles` (rôles d’approbation workflow).

## Changer de fournisseur d'identité

Les comptes sont liés via `user_identities(issuer, subject)` — **pas** via l'e-mail.
L'e-mail n'est plus unique (`V21`) : deux IdP peuvent partager la même adresse sans collision.

### Procédure recommandée (Keycloak → Entra, etc.)

1. Configurer le nouvel IdP (`socle.identity.issuer-uri`, claims, mapping).
2. Pour chaque utilisateur à conserver, **rattacher** la nouvelle identité au compte existant :

```http
POST /api/v1/admin/users/{userId}/identities
{"issuer":"https://login.microsoftonline.com/<tenant>/v2.0","subject":"<oid>"}
```

3. Ou import CSV (dry-run d'abord) :

```http
POST /api/v1/admin/users/identities/import?dryRun=true
Content-Type: text/plain

user_id,new_issuer,new_subject
aaaaaaaa-bbbb-…,https://login.microsoftonline.com/t/v2.0,00000000-1111-…
```

Colonnes : `user_id` **ou** `old_subject`, puis `new_issuer`, `new_subject`.  
`dryRun=true` (défaut) : rapport sans écriture. Application idempotente et auditée (`user.identities_imported`).

4. Retrait : `DELETE /api/v1/admin/users/{id}/identities?issuer=…&subject=…`  
   Impossible de retirer la **dernière** identité.

Après rattachement, la connexion via le nouvel IdP réutilise le même `users.id` (tuples OpenFGA, groupes, historique).

### Option `link-by-verified-email` (désactivée par défaut)

```yaml
socle.identity.link-by-verified-email: false   # défaut
```

Si `true`, à la première connexion d'une identité inconnue, rattachement **automatique** seulement si :

1. `email_verified == true` dans le JWT ;
2. **exactement un** compte porte cet e-mail ;
3. ce compte n'a encore **aucune** identité pour le même issuer.

**Risque** : un IdP qui ment sur `email_verified`, ou une réattribution d'e-mail mal gérée, peut permettre une **prise de contrôle de compte**. Ne l'activer que si l'intégrateur fait confiance absolue à la vérification e-mail de l'IdP. Le rattachement admin / CSV reste le chemin sûr.

## Migration schéma

| Version | Contenu |
|---------|---------|
| `V20` | `users.issuer` / `users.subject`, `user_platform_roles` |
| `V21` | `user_identities` ; drop unique e-mail ; colonnes issuer/subject migrées hors `users` |

## Tests de non-régression

- `AuditorSoDSecurityTest` / `IntegrationsSecurityTest` posent encore `ROLE_auditeur` etc. directement — ne pas affaiblir leurs assertions.
- `IdentityClaimUsageArchitectureTest` échoue si `getClaim(` ou `realm_access` réapparaissent hors mapper / défauts `IdentityProperties`.
- Aucun code d'autorisation ne résout un utilisateur par e-mail (grants / invitations / membres → `users.id`).
