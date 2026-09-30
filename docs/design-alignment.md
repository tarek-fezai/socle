# Alignement design — écrans React ↔ maquettes `.dc.html`

Référence : `systeme-documentation-direction-ui/`.  
Périmètre : Accès, Approbations (+ DiffApproval), Historique (+ Diff + RestoreVersion),
Notifications, Audit. Passage **visuel / structure / libellés** uniquement — aucune
modification de logique métier, d’API ou de tests.

## Tokens globaux (tous écrans)

| Élément | Avant | Après (maquettes) |
|---------|-------|-------------------|
| Accent | `#2F6FED` | `#3730E0` (`hover` `#2C27C7`) |
| Encre | `#0B1F33` | `#0E0E10` |
| Fond | dégradé bleu | `#FAFAFB` / blanc |
| Titres | Source Serif 4 | **Instrument Serif** |
| Texte | IBM Plex Sans | IBM Plex Sans (+ **IBM Plex Mono**) |
| Nav | liens texte | barre 60px, logo carré indigo, cloche notifications |

Fichiers : `tailwind.config.js`, `index.css`, `index.html`, `AppNav.tsx`.

---

## Accès — `Access.dc.html` ↔ `/spaces|folders|documents/.../access`

### Ajusté
- Titre **Accès et permissions** (h1 serif) + fil d’Ariane Accueil → …
- Section **Personnes et équipes** ; CTA **Accorder** (libellé test/API ; maquette « + Inviter »)
- Badges source (Direct / Via groupe / Hérité) aux teintes vert / indigo / ambre
- Cartes blanches bordées `#ECECEE`, alertes danger `#FCEEEA`
- Bloc **Visibilité** à trois niveaux (`organisation` / `space` / `restricted`) — libellés
  « Tous les utilisateurs » / « Membres de l’espace » / « Personnes et équipes listées » ;
  modifiable par owners d’espace uniquement (`PUT /documents/{id}/visibility`)

### Laissé de côté
- Sidebar collapsible + arborescence d’espace (chrome document, hors scope navigation actuelle)
- Onglets Lire / Modifier / Historique / Commentaires / Accès (pas de shell document unifié)
- **Corbeille** — soft-delete réel (tombstones + index `trash_items`) ; UI React encore à brancher — `docs/trash-soft-delete.md`
- Avatars + « Actif il y a… » — données absentes de l’API access

### Visibilité org — décision produit (mono-tenant VPC-per-client)

La maquette `Access.dc.html` propose trois radios, **implémentées** :

| Libellé UI | Valeur | Effet OpenFGA |
|------------|--------|---------------|
| Tous les utilisateurs | `organisation` | `user:* viewer` + `inherit_from` |
| Membres de l’espace | `space` | `inherit_from` (héritage espace/dossier) |
| Personnes et équipes listées | `restricted` | accès directs + owners via `parent` uniquement |

**Ce n’est pas** un réglage multi-org / `org_id` / RLS cross-tenant. En modèle
**mono-tenant, VPC-per-client** (pas de `org_id`, pas de RLS), « Organisation » =
l’entreprise cliente **dans** son instance.

**Ne pas construire :** couche multi-tenant, sélecteur d’organisation, isolation
cross-client dans une même DB.

Détail technique : `docs/spaces-governance.md` § Visibilité des pages.

---

## Approbations — `Approval.dc.html` + `DiffApproval.dc.html` ↔ `/approvals`

### Ajusté
- Fil d’Ariane **Approbation requise** ; badge **EN ATTENTE DE VOTRE DÉCISION**
- Rail latéral sticky (échéance SLA, étape, versions, date de soumission)
- Libellés **Justification de la décision**, placeholder refus, **Refuser**
- Bouton vert **Approuver la révision vN** (contient encore « Approuver » pour les tests)
- DiffViewer : fonds vert/rouille comme DiffApproval

### Laissé de côté
- Stepper N1/N2/Publication complet (rôles nommés, états Approuvé/Verrouillée) — le backend
  n’expose que `currentStepOrder`, pas l’historique des étapes
- **Demandeur** affiché avec avatar — `requestedBy` est un UUID, pas de profil résolu
- **Documents liés impactés**, **Voir les commentaires** — non disponibles
- Vue pleine page DiffApproval côte-à-côte — le diff reste structurel JSON (contrat API actuel)

---

## Historique — `History.dc.html` + `Diff.dc.html` + `RestoreVersion.dc.html`

### Ajusté
- Titre **Historique des versions** ; lien **Retour à la page**
- Timeline verticale (pastille verte sur la version courante)
- Badge statut style maquette (libellé conservé **version courante** — tests + sous-titre ;
  maquette : « Actuelle »)
- Section **Comparer les versions** ; modal **Restaurer la vN ?** (copy alignée)
- Diff colorisé (ajout/suppression)

### Laissé de côté
- Compteurs `+N −N` par version — non fournis par l’API versions
- Toggle **Côte à côte / Unifié** — rendu JSON path-based, pas textuel ligne à ligne
- Sidebar + onglets document (même raison que Accès)
- CTA modal maquette « Restaurer cette version » → conservé **Confirmer la restauration**
  (assertion test existante)

---

## Notifications — `Notifications.dc.html` ↔ `/notifications`

### Ajusté
- Fil d’Ariane ; titre serif ; largeur ~620px
- Groupement **Aujourd’hui / Hier / Cette semaine / Plus ancien**
- Point non-lu ambre (approval) ou indigo ; fond léger non-lu

### Laissé de côté
- **Tout marquer comme lu** — pas d’endpoint backend `read-all`
- **Préférences →** — écran Account / préférences notif hors scope
- Avatars, extraits de commentaire, CTA Accepter/Décliner invitation — types non produits
  (seul `approval_chain_exhausted` est réellement émis aujourd’hui)

---

## Audit — `AuditLog.dc.html` ↔ `/audit`

### Ajusté
- Sous-titre maquette (24 mois, non modifiable)
- Bouton **Exporter (CSV)** ; chips **Toutes les actions / Publications / Accès & permissions**
- Table **Horodatage | Auteur | Action | IP** (IP réelle si présente)

### Laissé de côté
- Badge **Diffusion SIEM active** — diffusion outbox implémentée (`siem_deliveries` + worker) ;
  le badge UI reste à brancher sur l’état des connecteurs
- Chips Tags & dossiers / Corbeille / Connexions — pas de mapping d’actions dédié côté API
  (filtres manuels action/ressource restent disponibles)
- Prose « a ajouté le tag Critique à … » — on affiche `action` + résumé metadata existant,
  pas un narratif inventé

---

## Données maquette absentes du backend (ne pas inventer)

| Écran | Donnée manquante | Statut |
|-------|------------------|--------|
| Accès | Visibilité org/équipe/personnes | **Livré** — `visibility` + UI Accès / SpaceSettings |
| Accès | Activité récente, avatars | Backlog |
| Approbation | Stepper multi-étapes nommé, documents liés, commentaires | Backlog |
| Historique / Diff | Compteurs +/− lignes, diff textuel unifié | Backlog |
| Notifications | Mark-all-read, préférences, invitations | Backlog |
| Audit | Chips métier riches / badge SIEM UI | Backlog UI — diffusion SIEM backend : voir `docs/siem-delivery.md` |

## Backlog hors périmètre Accès → Fiabilité (identifié, non attaqué)

| Pièce | État | Note |
|-------|------|------|
| Diffusion SIEM | **Implémenté** (outbox + worker HEC) | CRUD UI connecteurs + formats CEF avancés en backlog — `docs/siem-delivery.md` |
| Corbeille / soft-delete | **Implémenté** (backend) | Tombstones + cascade app + restore/purge — UI React en backlog — `docs/trash-soft-delete.md` |
| Mobile | 15× `Mobile*.dc.html` | Zéro code mobile |
| Recherche | — | Jamais abordée |

---

## Tests

Frontend : suite existante inchangée (assertions de libellés critiques préservées :
`Approuver`, `version courante`, `Confirmer la restauration`, `notif-badge`, etc.).
