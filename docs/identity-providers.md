# Identity providers

Socle dÃ©couple lâ€™identitÃ© IdP des rÃ´les plateforme et de la clÃ© utilisateur locale.
Le produit ne gÃ¨re **aucun mot de passe** : authentification 100 % OIDC.

## Principes

| Concept | DÃ©tail |
|---------|--------|
| ClÃ© utilisateur | `user_identities(issuer, subject)` â€” e-mail non unique |
| RÃ´les Socle | `CONTRIBUTEUR`, `AUDITEUR`, `INTEGRATEUR`, `ADMINISTRATEUR_SYSTEME` |
| AutoritÃ©s Spring | `ROLE_contributeur`, `ROLE_auditeur`, â€¦ (noms historiques Keycloak) |
| Lecture JWT | **uniquement** `IdentityClaimsMapper` (`jwt.getClaim` / chemins type `realm_access.roles`) |
| Source des rÃ´les | `socle.identity.role-source` : `CLAIMS` (dÃ©faut) \| `INTERNAL` \| `BOTH` |

## Configuration (`socle.identity`)

DÃ©fauts = comportement Keycloak historique (realm `socle` local) :

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

Le frontend lit `GET /api/v1/public/auth-config` (`authority`, `clientId`, `scopes`, `displayName` / `organizationName`, endpoints optionnels) â€” **aucun build Vite par client**.

Le nom d'organisation / d'affichage de l'instance **n'est jamais hardcodÃ©** : il vient de `socle.instance.display-name` (dÃ©faut `Socle`, surcharge via `SOCLE_INSTANCE_DISPLAY_NAME`). ExposÃ© aussi sur `GET /api/v1/me` comme `organizationName`.

## Exemples IdP

### Keycloak (exemple de dÃ©veloppement)

```yaml
socle.identity:
  issuer-uri: http://localhost:8081/realms/socle
  roles-claim: realm_access.roles
  # mapping 1:1 par dÃ©faut
```

Realm local : `infra/keycloak/realm-socle.dev.json` (**DEV UNIQUEMENT** â€” comptes de test). Comptes de dÃ©mo : contributeur / auditeur / integrateur.

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
    # â€¦
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

Lâ€™Ã©cran Admin de la maquette peut mentionner Â« Okta Â· SAML 2.0 Â». **Socle ne parle quâ€™OIDC** vers le navigateur et lâ€™API.

Pour un IdP SAML (Okta SAML, ADFS, etc.), brancher un **broker OIDC** devant Socle :

- Keycloak Identity Provider SAML â†’ clients OIDC Socle
- Zitadel / Authentik / LemonLDAP::NG en mode broker

Aucune implÃ©mentation SAML dans Socle.

## Modes `role-source`

| Mode | Comportement |
|------|----------------|
| `CLAIMS` (dÃ©faut) | RÃ´les IdP mappÃ©s + `default-role` |
| `INTERNAL` | Table `user_platform_roles` uniquement (claims ignorÃ©s) |
| `BOTH` | Union claims âˆª table |

### Bootstrap admin (`INTERNAL` / `BOTH`)

```yaml
socle.identity:
  role-source: INTERNAL
  bootstrap-admin-subjects:
    - "<subject OIDC du premier admin>"
```

Au **premier** login dâ€™un subject listÃ© â†’ grant `ADMINISTRATEUR_SYSTEME` dans `user_platform_roles`.

Admin API : `GET|POST|DELETE /api/v1/admin/platform-roles` (rÃ©servÃ© `ADMINISTRATEUR_SYSTEME`, auditÃ©).  
Impossible de retirer le **dernier** `ADMINISTRATEUR_SYSTEME`.

Ne pas confondre avec `global_roles` / `user_global_roles` (rÃ´les dâ€™approbation workflow).

## Changer de fournisseur d'identitÃ©

Les comptes sont liÃ©s via `user_identities(issuer, subject)` â€” **pas** via l'e-mail.
L'e-mail n'est plus unique (`V21`) : deux IdP peuvent partager la mÃªme adresse sans collision.

### ProcÃ©dure recommandÃ©e (Keycloak â†’ Entra, etc.)

1. Configurer le nouvel IdP (`socle.identity.issuer-uri`, claims, mapping).
2. Pour chaque utilisateur Ã  conserver, **rattacher** la nouvelle identitÃ© au compte existant :

```http
POST /api/v1/admin/users/{userId}/identities
{"issuer":"https://login.microsoftonline.com/<tenant>/v2.0","subject":"<oid>"}
```

3. Ou import CSV (dry-run d'abord) :

```http
POST /api/v1/admin/users/identities/import?dryRun=true
Content-Type: text/plain

user_id,new_issuer,new_subject
aaaaaaaa-bbbb-â€¦,https://login.microsoftonline.com/t/v2.0,00000000-1111-â€¦
```

Colonnes : `user_id` **ou** `old_subject`, puis `new_issuer`, `new_subject`.  
`dryRun=true` (dÃ©faut) : rapport sans Ã©criture. Application idempotente et auditÃ©e (`user.identities_imported`).

4. Retrait : `DELETE /api/v1/admin/users/{id}/identities?issuer=â€¦&subject=â€¦`  
   Impossible de retirer la **derniÃ¨re** identitÃ©.

AprÃ¨s rattachement, la connexion via le nouvel IdP rÃ©utilise le mÃªme `users.id` (tuples OpenFGA, groupes, historique).

### Option `link-by-verified-email` (dÃ©sactivÃ©e par dÃ©faut)

```yaml
socle.identity.link-by-verified-email: false   # dÃ©faut
```

Si `true`, Ã  la premiÃ¨re connexion d'une identitÃ© inconnue, rattachement **automatique** seulement si :

1. `email_verified == true` dans le JWT ;
2. **exactement un** compte porte cet e-mail ;
3. ce compte n'a encore **aucune** identitÃ© pour le mÃªme issuer.

**Risque** : un IdP qui ment sur `email_verified`, ou une rÃ©attribution d'e-mail mal gÃ©rÃ©e, peut permettre une **prise de contrÃ´le de compte**. Ne l'activer que si l'intÃ©grateur fait confiance absolue Ã  la vÃ©rification e-mail de l'IdP. Le rattachement admin / CSV reste le chemin sÃ»r.

## Politique d'accÃ¨s (`socle.identity.access-policy`)

```yaml
socle.identity:
  access-policy:
    mode: jit                 # jit | require-group | provisioned-only
    allowed-groups: []        # require-group : au moins un groupe du JWT doit figurer ici
    groups-claim: groups      # chemin pointÃ© dans le JWT (lu via IdentityClaimsMapper)
    allowed-email-domains: [] # vide = tous ; sinon domaine âˆˆ liste ET email_verified=true
  passkey-acr-values: ""      # exposÃ©s via auth-config (affichage)
  idp-display-name: ""
  support-contact: ""
```

| Mode | Comportement |
|------|--------------|
| `jit` | compte crÃ©Ã© Ã  la premiÃ¨re connexion si domaine OK |
| `require-group` | idem, mais le JWT doit porter un des `allowed-groups` (liste vide = personne) |
| `provisioned-only` | l'identitÃ© `(issuer, sub)` doit dÃ©jÃ  exister dans `user_identities` â€” aucun compte crÃ©Ã© |

- Refus : HTTP `403 {"error":"access_denied","reason":"â€¦"}` avec `reason` âˆˆ `not_in_allowed_group`,
  `account_disabled`, `not_provisioned`, `email_domain_not_allowed`. Aucun compte n'est crÃ©Ã© sur refus.
- `AccessPolicyFilter` (aprÃ¨s `BearerTokenAuthenticationFilter`, `/api/**` authentifiÃ© hors `/api/v1/public/**`)
  met la dÃ©cision en cache 60 s par `(issuer, sub)` ; le cache est invalidÃ© par `disable` / `enable`.
- **DÃ©cision Â« accordÃ©e Â» en cache** : une fois l'accÃ¨s acceptÃ©, le filtre peut rÃ©utiliser ce rÃ©sultat
  jusqu'Ã  **60 s**. Pendant cette fenÃªtre, un compte dÃ©sactivÃ© peut encore passer tant que le cache
  n'a pas expirÃ© (l'invalidation Ã  `disable`/`enable` rÃ©duit ce risque cÃ´tÃ© instance qui Ã©met
  l'action).
- **Groupes dans le JWT** : un jeton d'accÃ¨s encore valide conserve les groupes (et autres claims)
  qu'il contient **jusqu'Ã  son expiration**. Le mode `require-group` ne re-interroge pas l'IdP :
  seuls les claims du jeton prÃ©sentÃ© sont Ã©valuÃ©s. **Recommandation** : configurer l'IdP pour des
  jetons d'accÃ¨s courts (**â‰¤ 10 min**) afin qu'un retrait de groupe prenne effet rapidement.
- Admin (`ADMINISTRATEUR_SYSTEME`) : `POST /api/v1/admin/users/{id}/disable` et `/enable`
  (`users.status` = `disabled` / `active`, migration `V29`). Le dernier administrateur systÃ¨me ne peut pas Ãªtre dÃ©sactivÃ©.
- Audit : `auth.access_granted` (premiÃ¨re connexion rÃ©ussie), `auth.access_denied` (au plus 1 par
  utilisateur et motif toutes les 10 min, par instance), `user.disabled`, `user.enabled`. Jamais de token.
- `GET /api/v1/public/auth-config` n'expose **jamais** `allowed-groups` ni `allowed-email-domains`
  (seulement `passkeyAcrValues`, `idpDisplayName`, `supportContact`, `organizationName`).

## ClÃ©s de sÃ©curitÃ© / passkeys (`passkey-acr-values`)

Le bouton Â« Continuer avec une clÃ© de sÃ©curitÃ© Â» envoie `signinRedirect` avec
`acr_values` = `socle.identity.passkey-acr-values`. **Vide â†’ bouton masquÃ©** (mise en page
inchangÃ©e). Valeurs typiques selon l'IdP :

| IdP | `passkey-acr-values` (exemple) | Notes |
|-----|-------------------------------|--------|
| **Keycloak** | `phr` ou `http://schemas.openid.net/pape/policies/2007/06/phishing-resistant` | Configurer un Authentication Flow / ACR map vers WebAuthn ; `phr` = Phishing-Resistant (OIDC) |
| **Zitadel** | `urn:zitadel:iam:org:project:id:zitadel:aud` n'est **pas** un ACR â€” utiliser le niveau configurÃ©, ex. `http://schemas.openid.net/pape/policies/2007/06/phishing-resistant` | Activer WebAuthn / Passkeys sur l'org ; mapper un ACR dÃ©diÃ© si dÃ©fini |
| **Authentik** | valeur du stage ACR (ex. `authentik_authenticator_webauthn`) ou `phr` | DÃ©pend de la policy Authentik ; exposer l'ACR dans le provider OIDC |
| **Entra ID** | `urn:microsoft:policies:conditionalaccess` / Conditional Access Â« Authentication strength Â» (Passkeys / phishing-resistant) | Entra n'utilise pas toujours `acr_values` OIDC classique ; prÃ©fÃ©rer Conditional Access cÃ´tÃ© tenant et laisser le bouton masquÃ© (`""`) si non supportÃ© |

Documenter la valeur rÃ©elle de votre IdP dans la config d'instance ; ne jamais hardcoder un ACR
IdP-spÃ©cifique dans le frontend.

## Migration schÃ©ma

| Version | Contenu |
|---------|---------|
| `V20` | `users.issuer` / `users.subject`, `user_platform_roles` |
| `V21` | `user_identities` ; drop unique e-mail ; colonnes issuer/subject migrÃ©es hors `users` |
| `V29` | `users.status` : ajout de `disabled` au CHECK |

## Tests de non-rÃ©gression

- `AuditorSoDSecurityTest` / `IntegrationsSecurityTest` posent encore `ROLE_auditeur` etc. directement â€” ne pas affaiblir leurs assertions.
- `IdentityClaimUsageArchitectureTest` Ã©choue si `getClaim(` ou `realm_access` rÃ©apparaissent hors mapper / dÃ©fauts `IdentityProperties`.
- Aucun code d'autorisation ne rÃ©sout un utilisateur par e-mail (grants / invitations / membres â†’ `users.id`).
