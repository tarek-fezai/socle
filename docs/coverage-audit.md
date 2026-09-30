# Audit de couverture — maquettes `.dc.html` ↔ produit réel

**Date :** 2026-09-28  
**Référence maquette :** `systeme-documentation-direction-ui/` (83 écrans)  
**Frontend audité :** `frontend/src/` (routes dans `App.tsx`, pages listées ci-dessous)  
**Méthode :** lecture seule — aucune modification de code dans cette tâche.  
**Décisions produit déjà documentées :** `docs/design-alignment.md` (et docs techniques associées).

## Synthèse

| Catégorie | Nombre |
|-----------|--------|
| **COMPLET** | 20 |
| **PARTIEL** | 5 |
| **ABSENT** | 57 |
| **HORS SCOPE JUSTIFIÉ** | 1 |
| **Total** | **83** |

### Routes React réellement présentes

| Route | Page |
|-------|------|
| `/` | Accueil login / liens (≠ Dashboard maquette) |
| `/docs` | Liste documents + rail fiabilité |
| `/docs/:id` | Édition document + score + soumission approbation |
| `/docs/:id/view` | Page composite (transclusion résolue) |
| `/docs/:id/history` | Historique / Diff / Restore |
| `/approvals` | File d’approbation + diff (DiffApproval) |
| `/notifications` | Notifications |
| `/audit` | Journal d’audit |
| `/trash` | Corbeille |
| `/integrations` | Intégrations (SIEM + webhooks CRUD) |
| `/integrations/webhooks/deliveries` | Historique livraisons webhook |
| `/admin/workflows` | Admin définitions d’approbation |
| `/spaces` | Liste + création d’espaces |
| `/spaces/:id` | Paramètres / gouvernance espace |
| `/spaces/:id/graph` | Graphe de transclusion (Cytoscape) |
| `/spaces/:id/content-health` | Santé du contenu (docs stale) |
| `/team` | Groupes & membres |
| `/search` | Recherche full-text (FTS + OpenFGA) |
| `/spaces\|folders\|documents/…/access` | Accès & permissions |
| *(Keycloak)* | Login via `keycloak-js` (pas d’écran Login.dc.html) |

### Légende backend

- **BE OK** — API utilisable aujourd’hui  
- **BE schéma** — tables présentes, pas d’API CRUD/UI métier  
- **BE none** — ni table dédiée ni API  

---

## Tableau des 83 écrans

| # | Maquette | Titre | Catégorie | Frontend réel | Backend | Écart / notes |
|---|----------|-------|-----------|---------------|---------|---------------|
| 1 | `Main.dc.html` | Page (lecture doc) | **PARTIEL** | `/docs/:id` oriente surtout édition | BE OK documents | Pas de vue lecture seule type Main (onglets Lire/Commentaires/Accès, bannière attestation, chrome document). Édition + score partiellement présents. |
| 2 | `Graph.dc.html` | Vue graphe | **COMPLET** | `/spaces/:spaceId/graph` | BE `GET …/spaces/{id}/graph` | Transclusion + OpenFGA ; intra/inter ; `docs/transclusion-graph.md`. |
| 3 | `Index.dc.html` | Index | **ABSENT** | — | BE none | Index alphabétique / TOC espace. |
| 4 | `Dashboard.dc.html` | Accueil | **ABSENT** | `/` = landing auth, pas dashboard | BE none | Pas de widgets « récemment vus / à approuver / santé ». |
| 5 | `Edit.dc.html` | Modifier | **PARTIEL** | `DocumentEditPage` + TipTap | BE OK + soft-lock + `expectedVersionNo` | Soft-lock advisory + 409 concurrency ; manque shell onglets maquette, commentaires inline. `docs/editing-concurrency.md`. |
| 6 | `History.dc.html` | Historique | **COMPLET** | `/docs/:id/history` | BE OK versions | Aligné (écarts connus : compteurs +/−, toggle unifié — design-alignment). |
| 7 | `Review.dc.html` | Commentaires | **ABSENT** | — | BE schéma `document_comments` | Pas d’API commentaires / UI Review. |
| 8 | `Access.dc.html` | Accès | **COMPLET** | `/spaces|folders|documents/…/access` | BE OK OpenFGA | Visibilité org/space/restricted livrée (design-alignment). |
| 9 | `Components.dc.html` | Composants d’interface | **HORS SCOPE JUSTIFIÉ** | — | BE none | Catalogue design system pour designers, pas un écran métier produit. |
| 10 | `Spaces.dc.html` | Espaces | **COMPLET** | `/spaces` | **BE OK** CRUD `/api/v1/spaces` | Liste + création ; soft-delete via Trash. |
| 11 | `SpaceSettings.dc.html` | Espace — paramètres | **COMPLET** | `/spaces/:id` | **BE OK** owners/responsible | Métadonnées + gouvernance (`docs/spaces-governance.md`). |
| 12 | `Search.dc.html` | Recherche | **COMPLET** | `/search` | **BE OK** `/api/v1/search` FTS | Postgres FTS + filtre `listViewableDocumentIds` (docs/search.md). |
| 13 | `MobilePage.dc.html` | Mobile — page | **ABSENT** | — | (même BE que Main) | Zéro app mobile / responsive dédié. |
| 14 | `MobileMenu.dc.html` | Menu mobile | **ABSENT** | — | BE none | |
| 15 | `Login.dc.html` | Connexion | **PARTIEL** | Redirect Keycloak | BE OK OIDC | Auth réelle OK ; UI Login.dc.html (marque Socle) non reproduite. |
| 16 | `LoginError.dc.html` | Connexion refusée | **ABSENT** | — | BE OK Keycloak | Pas d’écran d’erreur Auth dédié côté SPA. |
| 17 | `Composite.dc.html` | Guide composite / page composite | **COMPLET** | `/docs/:id/view` | BE `GET …/resolved` + OpenFGA | Maquette = récit pédagogique ; produit = transclusion dynamique (`docs/transclusion.md`). |
| 18 | `GenerateApiKey.dc.html` | Générer une clé API | **ABSENT** | — | BE schéma `api_keys` | |
| 19 | `DiffApproval.dc.html` | Comparer versions (approbation) | **COMPLET** | Intégré `/approvals` | BE OK | Diff structurel JSON (écarts design-alignment acceptés). |
| 20 | `Team.dc.html` | Membres & équipes | **COMPLET** | `/team` | **BE OK** `/api/v1/groups` + members | Groupes + membres ; invitations email (Invite*) toujours ABSENT. |
| 21 | `InviteOrgMember.dc.html` | Inviter un membre (org) | **ABSENT** | — | BE schéma `team_invitations` | Intra-tenant (équipes du déploiement), pas multi-org. |
| 22 | `TeamInvitations.dc.html` | Invitations en attente | **ABSENT** | — | BE schéma | |
| 23 | `GlobalRoles.dc.html` | Rôles globaux | **ABSENT** | — | BE schéma + seed V4 | Rôles utilisés côté approbations ; pas d’admin UI. |
| 24 | `Retention.dc.html` | Rétention & conformité | **ABSENT** | — | BE schéma `retention_policies` (lu par reliability) | Pas de CRUD UI/API admin. |
| 25 | `Billing.dc.html` | Facturation | **ABSENT** | — | BE schéma `plan_tier` informatif | Entitlement réel = control plane externe (schéma) ; écran facturation SaaS multi-tenant **non** prévu. UI « plan déployé » possible plus tard sans être HORS SCOPE. |
| 26 | `DeleteDocument.dc.html` | Déplacer vers la corbeille | **ABSENT** | — | **BE OK** trash soft-delete | Confirm dialog maquette ; API `DELETE /documents/{id}` existe, **aucune UI**. |
| 27 | `Trash.dc.html` | Corbeille | **COMPLET** | `/trash` | **BE OK** `/api/v1/trash` | Liste + filtre `resourceType` + Restaurer (409 parent affiché). Purge définitive UI non exposée (API DELETE existe). |
| 28 | `FolderProcedures.dc.html` | Dossier Procédures | **PARTIEL** | Rail fiabilité sur `/docs` | BE schéma `folders` | Pas de page dossier / arborescence ; rail = substitut partiel. |
| 29 | `FolderReference.dc.html` | Dossier Référence | **ABSENT** | — | BE schéma | Même famille que FolderProcedures. |
| 30 | `NewFolder.dc.html` | Nouveau dossier | **ABSENT** | — | BE schéma (pas d’API CRUD) | |
| 31 | `TagsAdmin.dc.html` | Tags | **ABSENT** | — | BE schéma `tags` | |
| 32 | `Account.dc.html` | Compte | **ABSENT** | Affichage `me` minimal sur `/` | BE OK `/api/v1/me` | Pas d’écran préférences / sécurité / notifs. |
| 33 | `Notifications.dc.html` | Notifications | **COMPLET** | `/notifications` | BE OK | Écarts connus (mark-all-read, préférences). |
| 34 | `Glossary.dc.html` | Glossaire | **ABSENT** | — | BE schéma `glossary_terms` | |
| 35 | `Diff.dc.html` | Comparer versions | **COMPLET** | Dans History | BE OK | |
| 36 | `AuditLog.dc.html` | Journal d’audit | **COMPLET** | `/audit` | BE OK | Badge SIEM UI manquant (diffusion BE OK). |
| 37 | `NewDocument.dc.html` | Nouveau document | **PARTIEL** | Bouton create sur `/docs` | BE OK POST | Pas d’assistant / template / choix espace-dossier maquette. |
| 38 | `Approval.dc.html` | Approbation | **COMPLET** | `/approvals` | BE OK Temporal | Stepper riche / docs liés en backlog. |
| 39 | `Admin.dc.html` | Administration | **ABSENT** | — | BE schéma `sso_connections` etc. | Hub admin (SSO, etc.). |
| 40 | `Export.dc.html` | Exporter (document) | **COMPLET** | `/docs/:id/export` | `GET …/documents/{id}/export` | PDF via `TransclusionResolver` ; audit `document.exported` ; `docs/export.md`. |
| 41 | `AccessDenied.dc.html` | Accès restreint | **ABSENT** | Erreurs inline 403 | BE OK (403 API) | Pas de page dédiée. |
| 42 | `Integrations.dc.html` | Intégrations & API | **COMPLET** | `/integrations` | **BE OK** CRUD SIEM + webhooks (`integrateur`) | SoD : rôle `integrateur` ≠ `auditeur`. SCIM / clés API hors scope. |
| 43 | `InviteMember.dc.html` | Inviter (sur ressource) | **ABSENT** | Accès = Accorder ACL | BE OK grant | Invite email ≠ grant OpenFGA actuel. |
| 44 | `CreateSpace.dc.html` | Nouvel espace | **COMPLET** | Formulaire sur `/spaces` | **BE OK** `POST /api/v1/spaces` | Créateur = responsible + owner. |
| 45 | `ContentHealth.dc.html` | Santé du contenu | **COMPLET** | `/spaces/:spaceId/content-health` | BE `GET …/content-health` + badge | Fraîcheur dérivée (90j défaut) ; OpenFGA ; `docs/content-staleness.md`. |
| 46 | `NotFound.dc.html` | Document introuvable | **ABSENT** | — | — | Pas de route `*` 404 soignée. |
| 47 | `RestoreVersion.dc.html` | Restaurer une version | **COMPLET** | Modal History | BE OK | |
| 48 | `ExportFolder.dc.html` | Exporter dossier | **COMPLET** | `/folders/:id/export` | `GET …/folders/{id}/export` | ∩ `listViewableDocumentIds` ; audit `folder.exported`. |
| 49 | `ExportTag.dc.html` | Exporter tag | **COMPLET** | `/tags/:id/export` | `GET …/tags/{id}/export` | ∩ `listViewableDocumentIds` ; audit `tag.exported`. |
| 50 | `TemplatesAdmin.dc.html` | Modèles | **ABSENT** | — | BE schéma `templates` | |
| 51 | `Onboarding.dc.html` | Bienvenue | **ABSENT** | — | BE none | |
| 52 | `Favorites.dc.html` | Favoris | **ABSENT** | — | BE schéma `favorites` | |
| 53 | `Analytics.dc.html` | Analytique | **ABSENT** | — | BE none | |
| 54 | `WebhookDeliveries.dc.html` | Historique livraisons webhook | **COMPLET** | `/integrations/webhooks/deliveries` | **BE OK** `GET /api/v1/webhooks/deliveries` + worker Go | Lecture + filtre statut. **Backlog :** Relancer manuel (maquette) — pas d’endpoint BE ; KPIs 24h / latence / corps de réponse non stockés. |
| 55 | `Shortcuts.dc.html` | Raccourcis clavier | **ABSENT** | — | BE none | |
| 56 | `ServerError.dc.html` | Erreur serveur | **ABSENT** | — | — | |
| 57 | `Offline.dc.html` | Hors ligne | **ABSENT** | — | BE none | |
| 58 | `GeneratePersonalToken.dc.html` | Jeton personnel | **ABSENT** | — | BE schéma `personal_access_tokens` | |
| 59 | `ApiDocs.dc.html` | Portail développeur | **ABSENT** | — | BE none (pas de portal OpenAPI UI) | |
| 60 | `Changelog.dc.html` | Nouveautés | **ABSENT** | — | BE none | |
| 61 | `Attestations.dc.html` | Attestations | **ABSENT** | — | BE schéma `attestation_*` (entrée reliability) | Pas d’API ack / campagne. |
| 62 | `Workflows.dc.html` | Workflows d’approbation | **COMPLET** | `/admin/workflows` | **BE OK** CRUD `/api/v1/approval-workflows` + résolution scope à la soumission | Liste + édition N étapes (SLA, rôle, escalade) ; aperçu workflow applicable sur `/docs/:id`. |
| 63 | `CustomFields.dc.html` | Champs personnalisés | **ABSENT** | — | BE schéma | |
| 64 | `StatusPage.dc.html` | État du système | **ABSENT** | — | BE schéma `status_page_*` | |
| 65 | `Branding.dc.html` | Personnalisation de marque | **ABSENT** | Tokens hardcodés Tailwind | BE schéma `branding_settings` | |
| 66 | `ExportPersonalData.dc.html` | Télécharger mes données | **ABSENT** | — | BE schéma RGPD | |
| 67 | `HelpCenter.dc.html` | Centre d’aide | **ABSENT** | — | BE schéma | |
| 68 | `HelpArticle.dc.html` | Article d’aide | **ABSENT** | — | BE schéma | |
| 69–83 | `Mobile*.dc.html` (15) | Variantes mobiles | **ABSENT** | — | selon écran desktop equivalent | Aucun code mobile (design-alignment). Liste : Dashboard, Search, Notifications, Spaces, Favorites, Folder, Edit, History, Review, Access, Approval, Account, Login, Onboarding, HelpCenter, Page, Menu. |

*(Les **17** écrans `Mobile*` sont tous ABSENT ; détaillés dans le sous-tableau suivant. Comptage : 20 COMPLET + 5 PARTIEL + 1 HORS SCOPE + 40 ABSENT desktop-équivalents + 17 Mobile = **83**.)*

### Détail des 17 écrans Mobile* (tous ABSENT)

| Maquette | Équivalent desktop | Backend |
|----------|--------------------|---------|
| `MobilePage.dc.html` | Main | BE OK docs |
| `MobileMenu.dc.html` | Nav | — |
| `MobileDashboard.dc.html` | Dashboard | — |
| `MobileSearch.dc.html` | Search | — |
| `MobileNotifications.dc.html` | Notifications | BE OK |
| `MobileSpaces.dc.html` | Spaces | schéma |
| `MobileFavorites.dc.html` | Favorites | schéma |
| `MobileFolder.dc.html` | Folder* | schéma |
| `MobileEdit.dc.html` | Edit | BE OK |
| `MobileHistory.dc.html` | History | BE OK |
| `MobileReview.dc.html` | Review | schéma |
| `MobileAccess.dc.html` | Access | BE OK |
| `MobileApproval.dc.html` | Approval | BE OK |
| `MobileAccount.dc.html` | Account | `/me` |
| `MobileLogin.dc.html` | Login | OIDC |
| `MobileOnboarding.dc.html` | Onboarding | — |
| `MobileHelpCenter.dc.html` | HelpCenter | schéma |

---

## Détail des PARTIEL (ce qui manque précisément)

| Écran | Existe | Manque |
|-------|--------|--------|
| **Main** | Edit page | Mode lecture, onglets, attestation « J’ai lu », commentaires, chrome doc |
| **Edit** | TipTap + save + score + soft-lock + 409 version | Shell maquette, métadonnées, Review |
| **Login** | Keycloak | Branding / layout Login.dc.html ; gestion d’erreur LoginError |
| **NewDocument** | Create one-click | Wizard titre/espace/dossier/template |
| **FolderProcedures** | Rail fiabilité liste | Page dossier, arbo, docs du dossier, `folders.reliability_score` |
| **ContentHealth** | `/spaces/:id/content-health` + badge stale | Campagnes / attestations (hors fraîcheur) |

~~**Integrations**~~ — passé **COMPLET** (`/integrations`, rôle `integrateur` ; SoD vs `auditeur`).

---

## HORS SCOPE JUSTIFIÉ (revérifié)

| Écran | Justification |
|-------|----------------|
| `Components.dc.html` | Kit UI / storybook maquette — pas une feature utilisateur Socle. |

**Composite** : passé **COMPLET** (`/docs/:id/view`, `docs/transclusion.md`) — la maquette reste un récit design, le produit livré est la lecture résolue.

**Non classés HORS SCOPE malgré ambiguïté mono-tenant :**

- Bloc **Visibilité** d'`Access.dc.html` — **livré** (`organisation` / `space` / `restricted`) ; voir `docs/spaces-governance.md`.
- `InviteOrgMember` / `Team*` / `Billing` (plan informatif) — restent **ABSENT** produit à construire ou non selon priorité, pas « obsolètes multi-tenant ».

---

## Ordre de priorité **suggéré** (éclairer, ne pas trancher)

Critères utilisés pour **ordonner la suggestion** (pas une décision produit) :

1. Backend déjà prêt → ROI UI immédiat  
2. Trous dans le parcours document quotidien (Main / dossiers / corbeille)  
3. Admin / conformité visibles en maquette  
4. Mobile (large surface, après socle desktop)  
5. Design-only / périphérique  

### Vague A — UI sur BE déjà prêt (gain rapide)

1. ~~**Trash**~~ — branché (`/trash`) · **DeleteDocument** (confirm dialog) reste à faire  
2. **Main** (vue lecture) vs Edit — parcours doc réel  
3. ~~**Integrations** (CRUD)~~ — branché (`integrateur`, SoD vs `auditeur`) · SCIM / clés API backlog  
4. ~~**WebhookDeliveries** (lecture)~~ — branché · **backlog** Relancer manuel + KPIs 24h  

### Vague B — Parcours contenu / navigation

5. ~~**Spaces + CreateSpace + SpaceSettings**~~ → **COMPLET** (`/spaces`, gouvernance)  
6. **Folders** (NewFolder, FolderProcedures/Reference) + API folders  
7. ~~**Search**~~ → **COMPLET** (`/search`, docs/search.md)  
8. **NewDocument** assistant  
9. **Dashboard** (agrégats approvals / docs)  
10. **Favorites** (schéma + API légère)  

### Vague C — Collaboration & conformité

11. **Review** (commentaires)  
12. **Attestations**  
13. ~~**Workflows** admin~~ → **COMPLET** (`/admin/workflows`)  
14. ~~**Team**~~ → **COMPLET** (`/team`) · **Invitations / GlobalRoles** backlog  
15. **Retention** admin UI  
16. ~~**ContentHealth**~~ → **COMPLET** (`/spaces/:id/content-health`)  
17. ~~**Export** / ExportFolder / ExportTag~~ → **COMPLET** (`docs/export.md`) · **ExportPersonalData** backlog  

### Vague D — Plateforme & DX

18. **Account**, **Login/LoginError** branding  
19. **Admin** (SSO), **Branding**, **StatusPage**  
20. **GenerateApiKey / PersonalToken**, **ApiDocs**  
21. **TagsAdmin**, **TemplatesAdmin**, **CustomFields**, **Glossary**  
22. **Analytics**, **Changelog**, **Onboarding**, **Help***  
23. **Graph / Index**, **Shortcuts**, pages erreur (404/500/Offline/AccessDenied)  

### Vague E — Mobile (17 écrans)

24. Stratégie responsive ou app dédiée **après** stabilisation desktop des équivalents.

---

## Références croisées

| Doc | Lien utile |
|-----|------------|
| Alignement Accès→Audit | `docs/design-alignment.md` |
| Soft-delete / corbeille BE | `docs/trash-soft-delete.md` |
| SIEM | `docs/siem-delivery.md` |
| Reliability | `docs/reliability-score.md` |
| Schéma | `socle_schema.sql` |

---

## Méthode & limites de cet audit

- Classification **par fonction**, pas par homonymie de fichiers.  
- « COMPLET » = route/page branchée API + alignement design documenté pour le cœur de l’écran (écarts mineurs listés dans design-alignment restent possibles).  
- Les tables sans API sont notées **BE schéma** : le frontend seul ne suffit pas.  
- Priorisation = **suggestion** pour discussion produit, pas un backlog engagé.
