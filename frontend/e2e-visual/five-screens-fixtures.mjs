// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Fixtures des 5 écrans « parité visuelle » (Spaces, Search, Notifications, Favorites, NewDocument).
 * Règles : docs/visual-parity.md
 *
 * Toutes les valeurs sont celles que le serveur renvoie réellement (schémas OpenAPI validés par
 * `fixture-contract.test.ts`) : aucun champ backend inventé. Les libellés identiques à la maquette
 * viennent de données (noms d'espaces, titres, horodatages), jamais d'une copie hors contrat.
 */
import { SPACE_IDENTITE, VISUAL_NOW } from './dashboard-fixtures.mjs'

export { VISUAL_NOW }

const iso = (ms) => new Date(ms).toISOString()

/* ------------------------------------------------------------------ */
/* /api/v1/spaces → SpaceView[]                                        */
/* ------------------------------------------------------------------ */

/**
 * Noms et couleurs = tuiles de Spaces.dc.html. SPACE_IDENTITE vient du dashboard ; les autres sont
 * ajoutés ici (SPACE_INFRA du dashboard est noir, la maquette Espaces le veut vert #0D8A7C).
 * @type {import('../src/lib/api-types.ts').components['schemas']['SpaceView'][]}
 */
export const SPACES_SEED = [
  { ...SPACE_IDENTITE },
  {
    ...SPACE_IDENTITE,
    id: 's0000001-0000-4000-8000-000000000002',
    name: 'Infrastructure',
    color: '#0D8A7C',
  },
  {
    ...SPACE_IDENTITE,
    id: 's0000001-0000-4000-8000-000000000003',
    name: 'Conformité',
    color: '#B7791F',
  },
  {
    ...SPACE_IDENTITE,
    id: 's0000001-0000-4000-8000-000000000004',
    name: 'Produit',
    color: '#7C3AED',
  },
  {
    ...SPACE_IDENTITE,
    id: 's0000001-0000-4000-8000-000000000005',
    name: 'Ressources humaines',
    color: '#1E8E5A',
  },
]

/* ------------------------------------------------------------------ */
/* /api/v1/notifications → NotificationPage                            */
/* ------------------------------------------------------------------ */

const DOC_POLITIQUE = 'd0000001-0000-4000-8000-000000000001'
const APPROVAL_REQUEST = 'a0000001-0000-4000-8000-000000000001'

/**
 * Types réellement produits par le backend uniquement. Horodatages relatifs à VISUAL_NOW
 * (jeudi 10/09/2026 14:00 Europe/Paris) : « Il y a 5 minutes », « Il y a 20 minutes »,
 * « Hier à 17:42 », « Hier à 09:15 ».
 * @type {import('../src/lib/api-types.ts').components['schemas']['NotificationPage']}
 */
export const NOTIFICATIONS_LIST_SEED = {
  items: [
    {
      id: 'n0000001-0000-4000-8000-000000000001',
      type: 'approval_chain_exhausted',
      payload: { document_id: DOC_POLITIQUE, approval_request_id: APPROVAL_REQUEST, steps_traversed: 2 },
      documentTitle: 'Politique de gestion des accès',
      readAt: null,
      createdAt: iso(VISUAL_NOW - 5 * 60_000),
    },
    {
      id: 'n0000001-0000-4000-8000-000000000002',
      type: 'comment_mention',
      payload: { document_id: DOC_POLITIQUE },
      documentTitle: 'Politique de gestion des accès',
      readAt: null,
      createdAt: iso(VISUAL_NOW - 20 * 60_000),
    },
    {
      // Hier 17:42 Europe/Paris = 15:42Z
      id: 'n0000001-0000-4000-8000-000000000003',
      type: 'pat_expiring',
      payload: { name: 'CI', last4: 'a1b2', expires_at: '2026-09-17T12:00:00Z' },
      readAt: '2026-09-09T16:00:00Z',
      createdAt: '2026-09-09T15:42:00Z',
    },
    {
      // Hier 09:15 Europe/Paris = 07:15Z
      id: 'n0000001-0000-4000-8000-000000000004',
      type: 'external_reference_first',
      payload: { source_space_name: 'Infrastructure' },
      readAt: '2026-09-09T08:00:00Z',
      createdAt: '2026-09-09T07:15:00Z',
    },
  ],
  offset: 0,
  limit: 50,
  total: 4,
  unreadCount: 2,
}

/* ------------------------------------------------------------------ */
/* /api/v1/favorites → FavoriteItem[] (tableau, pas { items })          */
/* ------------------------------------------------------------------ */

/**
 * Réponse réelle de `FavoriteController#list` : tableau de `FavoriteItem`
 * `{ targetType, targetId, createdAt, title }` — aucun `spaceName` / statut / date de révision.
 * @type {import('../src/lib/api-types.ts').components['schemas']['FavoriteItem'][]}
 */
export const FAVORITES_LIST_SEED = [
  {
    targetType: 'document',
    targetId: 'd0000001-0000-4000-8000-000000000001',
    createdAt: iso(VISUAL_NOW - 1 * 86_400_000),
    title: 'Politique de gestion des accès',
  },
  {
    targetType: 'document',
    targetId: 'd0000001-0000-4000-8000-000000000014',
    createdAt: iso(VISUAL_NOW - 2 * 86_400_000),
    title: 'Procédure de provisioning',
  },
  {
    targetType: 'document',
    targetId: 'd0000001-0000-4000-8000-000000000015',
    createdAt: iso(VISUAL_NOW - 3 * 86_400_000),
    title: 'Registre des accès à privilèges',
  },
  {
    targetType: 'space',
    targetId: SPACE_IDENTITE.id,
    createdAt: iso(VISUAL_NOW - 4 * 86_400_000),
    title: 'Identité & accès',
  },
  {
    targetType: 'document',
    targetId: 'd0000001-0000-4000-8000-000000000002',
    createdAt: iso(VISUAL_NOW - 5 * 86_400_000),
    title: "Plan de reprise d'activité",
  },
  // 6ᵉ favori hors maquette (rangs 0-4 comparés) : la phrase d'intro de la maquette
  // annonce « 5 documents et 1 espace » (alors qu'elle n'en liste que 4 + 1).
  {
    targetType: 'document',
    targetId: 'd0000001-0000-4000-8000-000000000016',
    createdAt: iso(VISUAL_NOW - 6 * 86_400_000),
    title: 'Architecture réseau',
  },
]

/* ------------------------------------------------------------------ */
/* /api/v1/search → SearchResponse                                     */
/* ------------------------------------------------------------------ */

/** @type {import('../src/lib/api-types.ts').components['schemas']['SearchResponse']} */
export const SEARCH_SEED = {
  query: 'provisioning',
  results: [
    {
      id: 'd0000001-0000-4000-8000-000000000014',
      title: 'Procédure de provisioning',
      excerpt: "L'événement d'embauche déclenche la création du compte dans le moteur IGA…",
      spaceId: SPACE_IDENTITE.id,
      spaceName: 'Identité & accès',
      docType: 'procedure',
      status: 'valide',
      updatedAt: iso(VISUAL_NOW - 3 * 86_400_000),
      rank: 0.9,
    },
    {
      id: 'd0000001-0000-4000-8000-000000000001',
      title: 'Politique de gestion des accès',
      excerpt: 'Les comptes sont créés par provisioning automatique…',
      spaceId: SPACE_IDENTITE.id,
      spaceName: 'Identité & accès',
      docType: 'politique',
      status: 'valide',
      updatedAt: iso(VISUAL_NOW - 5 * 86_400_000),
      rank: 0.6,
    },
    {
      id: 'd0000001-0000-4000-8000-000000000015',
      title: 'Registre des accès à privilèges',
      excerpt: 'Brouillon du registre.',
      spaceId: SPACE_IDENTITE.id,
      spaceName: 'Identité & accès',
      docType: null,
      status: 'brouillon',
      updatedAt: iso(VISUAL_NOW - 20 * 60_000),
      rank: 0.3,
    },
  ],
  total: 3,
  totalIsEstimate: true,
}

/* ------------------------------------------------------------------ */
/* /api/v1/templates → TemplateSummary[]                               */
/* ------------------------------------------------------------------ */

const tpl = (n, name, description, extra = {}) => ({
  id: `t0000001-0000-4000-8000-00000000000${n}`,
  name,
  description,
  docType: null,
  scope: 'global',
  spaceId: null,
  defaultTagIds: [],
  version: 1,
  createdAt: '2026-01-01T00:00:00.000Z',
  updatedAt: '2026-01-01T00:00:00.000Z',
  canManage: false,
  ...extra,
})

/**
 * Modèles livrés (sans `createdBy` — seed_key serveur) + un modèle personnalisé d'organisation
 * (`createdBy` renseigné → badge au nom de l'organisation).
 * @type {import('../src/lib/api-types.ts').components['schemas']['TemplateSummary'][]}
 */
export const TEMPLATES_SEED = [
  tpl(
    1,
    'Politique',
    "Règles et principes normatifs, avec cycle d'approbation et revue périodique.",
    { docType: 'politique' },
  ),
  tpl(
    2,
    'Procédure',
    'Étapes opérationnelles séquencées, avec rôles et points de contrôle.',
    { docType: 'procedure' },
  ),
  tpl(
    3,
    'Guide utilisateur',
    'Explications pas à pas destinées aux utilisateurs finaux, illustrées.',
    { docType: 'guide' },
  ),
  tpl(
    4,
    'Fiche fournisseur externe',
    "Modèle personnalisé — coordonnées, périmètre d'accès, contact de sécurité.",
    {
      createdBy: '11111111-1111-1111-1111-111111111111',
      updatedBy: '11111111-1111-1111-1111-111111111111',
      canManage: true,
    },
  ),
]

/** GET /api/v1/spaces/{id}/tree — dossier « Procédures » (racine + 1 dossier). */
export const NEWDOC_TREE = {
  spaceId: SPACE_IDENTITE.id,
  spaceName: SPACE_IDENTITE.name,
  folders: [
    {
      id: 'f0000001-0000-4000-8000-000000000001',
      name: 'Procédures',
      parentFolderId: null,
      position: 0,
      documentCount: 0,
      folderCount: 0,
      documents: [],
    },
  ],
  documents: [],
}
