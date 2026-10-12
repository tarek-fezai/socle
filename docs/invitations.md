# Invitations par e-mail

Référence pour inviter un membre (`InviteOrgMember.dc.html`) et gérer les
invitations en attente (`TeamInvitations.dc.html`). Les tests d’intégration,
SMTP et e2e s’alignent sur ce document — pas l’inverse.

Décision propriétaire (tf) : **Socle envoie l’e-mail via SMTP** ; la liaison
au compte a lieu au **premier login**, avec les garde-fous § 4 **tous
requis**.

La table historique `team_invitations` (`V1`) n’est **pas** utilisée. Le
modèle vivant est `invitations` (`V49`).

Aucune migration d’autorisation OpenFGA dans ce chantier.

## 1. Flux

```
Admin (JWT IdP, pas PAT)
  → POST /api/v1/admin/invitations
  → e-mail texte + HTML (SMTP)
  → lien {SOCLE_PUBLIC_BASE_URL}/invitations/accept?token=…

Invité
  → page d’acceptation (explication + « Se connecter »)
  → jeton en sessionStorage le temps du login OIDC
  → retour /invitations/accept
  → POST /api/v1/invitations/accept { token }  (JWT IdP)
  → si tous les garde-fous § 4 : compte + rôle global + équipe (atomique)
  → sessionStorage vidé
```

Renvoyer : **nouveau** jeton (l’ancien hash est remplacé — l’ancien lien
est mort), limité en débit. Annuler : `status = revoked`. Tâche planifiée :
`pending` dont `expires_at < now()` → `expired`.

Une seule invitation `pending` par e-mail (index unique partiel).

## 2. SMTP

| Variable | Rôle |
|---|---|
| `SOCLE_SMTP_HOST` | Hôte. Vide = SMTP non configuré (canal `log` en dev). |
| `SOCLE_SMTP_PORT` | Port (587 STARTTLS / 465 implicit). |
| `SOCLE_SMTP_USERNAME` | Identifiant (optionnel). |
| `SOCLE_SMTP_PASSWORD` | Mot de passe. **Jamais loggé.** |
| `SOCLE_SMTP_FROM` | Expéditeur technique (`From`). Priorité UI branding si l’admin a configuré un expéditeur. |
| `SOCLE_SMTP_TLS` | `starttls` \| `implicit`. |

**TLS obligatoire** dès que `SOCLE_SMTP_HOST` est posé, **hors** profils
`dev` / `local` : démarrage refusé sinon. En `dev`/`local`, l’absence de TLS
est tolérée (Mailpit).

Le bouton admin « Envoyer un e-mail de test » (branding) utilise **le même**
service d’envoi (`BrandingMailSender`).

Indicateur de santé Actuator `smtp` : `up` / `down` / `not_configured` —
**aucun secret**, aucun mot de passe, aucun jeton.

Démo locale : service **Mailpit** dans le profil Compose `demo-idp` (UI HTTP
locale). CI / ITs backend : **GreenMail**. e2e : Mailpit dans la stack
`demo-idp`.

## 3. Données (`V49` — `invitations`)

| Colonne | Règle |
|---|---|
| `id` | UUID |
| `email` | minuscule, normalisé (`trim` + `toLowerCase`) |
| `team_id` | UUID équipe, **NULL** si aucune équipe |
| `team_role` | `proprietaire` \| `approbateur` \| `contributeur` \| `lecteur`, **NULL** si pas d’équipe |
| `global_role` | rôle plateforme (`CONTRIBUTEUR` = « Membre » UI, `ADMINISTRATEUR_SYSTEME` = « Administrateur ») |
| `token_hash` | HMAC-SHA256(jeton, `SOCLE_INVITE_PEPPER`) — **jamais** le jeton en clair |
| `status` | `pending` \| `accepted` \| `revoked` \| `expired` |
| `expires_at` | NOT NULL ; défaut **7 jours** ; **max 30 jours** (CHECK SQL) |
| `invited_by` | `users.id` de l’admin |
| `accepted_user_id` | renseigné à l’acceptation |
| `accepted_at` | idem |
| `created_at` | |
| `last_sent_at` | dernier envoi (création ou renvoi) |
| `send_count` | entier ≥ 1 |

Index unique partiel : une seule ligne `pending` par `email`.

### Pepper

`SOCLE_INVITE_PEPPER` : **dédié**, distinct de `SOCLE_PAT_PEPPER`, ≥ 32
octets. Hors `dev`/`local` : absent ou trop court ⇒ **démarrage refusé**.
En `dev`/`local` : pepper éphémère (invitations invalidées au redémarrage).
Changer le pepper invalide tous les liens en cours. Jamais commité.

Lien d’acceptation : `{SOCLE_PUBLIC_BASE_URL}/invitations/accept?token=…`
(`SOCLE_PUBLIC_URL` n’existe pas — on réutilise `SOCLE_PUBLIC_BASE_URL`).

## 4. Envoi (admin v1)

`POST /api/v1/admin/invitations` `{ email, teamId?, teamRole?, globalRole }`

- Réservé `ADMINISTRATEUR_SYSTEME`. **PAT interdit** (`/api/v1/admin/**` +
  contrôle IdP sur l’acceptation).
- **409** si l’e-mail appartient déjà à un compte (`users.email`,
  insensible à la casse).
- Inviter en **administrateur** (`ADMINISTRATEUR_SYSTEME`) : confirmation
  explicite côté UI (`confirmAdmin: true` requis) + audit dédié
  `invitation.admin_role_invited`.
- E-mail texte + HTML : nom d’organisation (branding / instance), lien
  ci-dessus. **Aucun pixel de suivi.** Jeton jamais loggé ni stocké en
  clair.
- Avertissement UI **avant** l’envoi si licence en évaluation ou sièges
  épuisés (`GET /api/v1/admin/licence`) — l’API refuse aussi à
  l’acceptation (`licence_user_limit`).

`POST /api/v1/admin/invitations/{id}/resend` — nouveau jeton, débit limité
(1 renvoi / 60 s par invitation).

`POST /api/v1/admin/invitations/{id}/revoke` — `revoked`.

`GET /api/v1/admin/invitations` — liste `pending` (colonnes UI : invité,
équipe, rôle, envoyée par, date).

## 5. Acceptation — garde-fous

`POST /api/v1/invitations/accept` `{ token }`

Côté utilisateur, tout échec de garde-fou (sauf licence déjà exposée par
l’API licence) renvoie **403 générique** (`invitation_rejected`). La
**raison précise** va uniquement dans l’audit
(`invitation.accept_denied`, metadata `reason`).

| | Garde-fou | Refus (audit `reason`) |
|---|---|---|
| a | Utilisateur authentifié par l’IdP (JWT). PAT interdit. | `pat` / `unauthenticated` |
| b | Claim `email_verified = true` | `email_not_verified` |
| c | `iss` ∈ émetteurs de confiance (`OIDC_ISSUER_URI` + `SOCLE_INVITE_TRUSTED_ISSUERS`) | `issuer_untrusted` |
| d | E-mail du JWT = e-mail de l’invitation (insensible à la casse) | `email_mismatch` |
| e | Invitation `pending`, non expirée, jeton correct (comparaison **à temps constant**), usage unique (passage à `accepted` dans **la même transaction**) | `expired` / `revoked` / `already_accepted` / `token_invalid` |
| f | Aucun compte existant avec cet e-mail **ni** lié à ce sujet `(issuer, sub)` | `identity_conflict` (même code API que #58 si collision sujet) |
| g | Création de compte : `LicenceService.assertCanCreateUser` | `licence_user_limit` (problem+json, comme #58) |
| h | Attribution **atomique** : `users` + identité + rôle global + membre d’équipe / rôle d’équipe ; audit `invitation.accepted` | échec d’écriture → rollback, pas d’état partiel |

Double acceptation concurrente : **une seule** transaction réussit
(contrainte / `UPDATE … WHERE status = 'pending'`).

### Politique d’admission

La politique existante (domaines / JIT / `require-group` /
`provisioned-only`) **n’est pas remplacée**.

Pour un utilisateur **non provisionné** dont l’e-mail correspond à une
invitation `pending` valide :

- le sync JWT **ne crée pas** le compte (pas de JIT) ;
- seul `POST /api/v1/invitations/accept` est laissé passer ;
- les autres `/api/**` restent refusés (`not_provisioned`) jusqu’à
  l’acceptation réussie.

Sans invitation pending, le comportement JIT / domaines / groupes est
inchangé.

Après acceptation : l’identité `(issuer, sub)` existe — les requêtes
suivantes suivent la politique normale.

## 6. Modèle de menace

| Menace | Mitigation |
|---|---|
| Vol du lien | Jeton opaque 256 bits, HMAC pepper dédié, TTL ≤ 30 j, usage unique, renvoi invalide l’ancien |
| Énumération (jeton faux / e-mail déjà pris) | 403 générique à l’acceptation ; 409 à l’envoi admin seulement (admin déjà de confiance) |
| Compte existant / prise inter-IdP | Garde-fous f + `identity_conflict` (#58) |
| IdP qui ment sur l’e-mail | `email_verified` obligatoire ; `iss` allow-list |
| PAT / automation | Admin + accept : JWT IdP uniquement |
| Fuite logs | Jeton, pepper, mot de passe SMTP exclus des logs (tests appender) |
| Seat / évaluation | Refus `licence_user_limit` à l’acceptation ; bandeau UI avant envoi |
| Replay / course | `UPDATE … pending` + unique pending-email |
| Tracking | Pas de pixel, pas de redirect tiers dans l’e-mail |
| Invitation admin | Confirmation UI + audit dédié |

## 7. UI

- Admin → Membres & équipes : onglet **Invitations** conforme à
  `TeamInvitations.dc.html` (invité, équipe, rôle, envoyée par, date ;
  Renvoyer / Annuler). CTA **+ Inviter un membre** → formulaire modal
  conforme à `InviteOrgMember.dc.html`. Retirer `TEAM-INVITATIONS` de
  `NOT_IMPLEMENTED`.
- Avertissement si licence en évaluation ou sièges épuisés **avant**
  l’envoi.
- `/invitations/accept` : explication + « Se connecter » ; jeton en
  `sessionStorage` (`socle.invite.token`) pendant le login, retiré après
  usage (succès ou refus définitif). Messages clairs : expirée, déjà
  utilisée, e-mail différent, e-mail non vérifié — issus d’un **code**
  dédié uniquement lorsque le serveur choisit de l’exposer (page
  d’acceptation : codes stables `invitation_expired`,
  `invitation_already_used`, `invitation_email_mismatch`,
  `invitation_email_unverified` mappés depuis l’audit côté serveur via un
  sous-ensemble **non énumérable** : le 403 générique porte un `code` UI
  seulement si cela n’aide pas un attaquant à distinguer « jeton faux »
  de « e-mail déjà pris »). Les quatre messages listés concernent un
  porteur du **vrai** jeton déjà authentifié — le serveur peut alors
  renvoyer le code UI correspondant. Un jeton inconnu reste 403
  générique sans distinction.
- Specs visuels Invitations + formulaire : ≤ 1 % (`docs/visual-parity.md`).
- Maquettes `*.dc.html` : **non modifiées**.

## 8. Audit

| Action | Mode | Metadata (pas de jeton, pas de secret) |
|---|---|---|
| `invitation.sent` | async | `email`, `teamId`, `teamRole`, `globalRole` |
| `invitation.admin_role_invited` | sync | `email`, `globalRole` |
| `invitation.resent` | async | `id` |
| `invitation.revoked` | async | `id` |
| `invitation.expired` | async | `id` (tâche) |
| `invitation.accepted` | sync | `invitationId`, `userId`, `teamId`, `globalRole` |
| `invitation.accept_denied` | async | `reason` (§ 5), pas de jeton |

## 9. Tests (oracle)

Intégration : Postgres + OpenFGA réels, SMTP GreenMail.

1. Chaque garde-fou a)–h) : un test de **refus** dédié + le **cas nominal**.
2. Renvoi : l’ancien jeton est refusé.
3. Double acceptation concurrente : une seule 2xx.
4. Appender capturé : aucun jeton, aucun `SOCLE_SMTP_PASSWORD`, aucun
   pepper dans les logs.
5. e2e : invitation envoyée → e-mail Mailpit → lien → login Keycloak
   (utilisateur `email_verified`) → compte créé avec équipe et rôle.

## 10. Hors périmètre

- Invitation par un non-admin ; invitation via PAT.
- Modification des maquettes.
- Signature ou import de licence.
- Migration d’autorisation OpenFGA.
- SCIM / synchro annuaire.
