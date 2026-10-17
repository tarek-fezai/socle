// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Accueil d'espace (SpaceHome.dc.html).
 * GET /spaces/{id} → SpaceDetail, GET /spaces/{id}/page → DocumentResponse,
 * commentaires → CommentsPage. Validées AJV dans fixture-contract.test.ts
 * (SpaceDetail et DocumentResponse).
 *
 * Les effectifs du rail (12 membres, 3 équipes, tag Critique, paragraphe long,
 * dates de la maquette) sont les valeurs visibles de SpaceHome.dc.html,
 * conformes au contrat. Le test Java get_matchesSpaceHomeMockup ne les fige pas.
 */
import { ME_TAREK, SPACE_IDENTITE } from './dashboard-fixtures.mjs'

export const SPACE_HOME_PAGE_TEXT =
  "Politiques, procédures et référentiels du cycle de vie des identités et des accès. Cet espace sert de référence pour les revues d'habilitations et les audits de conformité."

const PAGE_ID = 'd0000001-0000-4000-8000-0000000000e1'
const FOLDER_PROC = 'f0000001-0000-4000-8000-0000000000a1'
const FOLDER_REF = 'f0000001-0000-4000-8000-0000000000a2'
const DOC_POLITIQUE = 'd0000001-0000-4000-8000-000000000021'
const DOC_REGISTRE = 'd0000001-0000-4000-8000-000000000022'

const TAG_IAM = { id: 't0000001-0000-4000-8000-000000000001', name: 'IAM', color: '#3730E0' }
const TAG_CRIT = { id: 't0000001-0000-4000-8000-000000000002', name: 'Critique', color: '#3730E0' }

/** Réponse de GET /api/v1/spaces/{id}. */
export const SPACE_HOME_DETAIL = {
  ...SPACE_IDENTITE,
  createdAt: '2026-05-02T10:00:00.000Z',
  defaultVisibility: 'organisation',
  documentCount: 7,
  pageDocumentId: PAGE_ID,
  canEdit: true,
  canDelete: true,
  language: 'fr',
  status: 'actif',
  owner: {
    id: 'g0000001-0000-4000-8000-000000000001',
    name: 'Équipe Identité',
    owner: { id: ME_TAREK.id, displayName: 'Tarek Fezai', initials: 'TF' },
  },
  memberCount: 12,
  teamCount: 3,
  members: [{ initials: 'TF' }, { initials: 'RL' }, { initials: 'SL' }, { initials: 'CD' }],
  lastActivity: { who: 'Tarek Fezai', at: '2026-09-28T10:00:00.000Z' },
  reliabilityAverage: 91,
  statusCounts: { validated: 5, inReview: 1, draft: 1 },
  folders: [
    { id: FOLDER_PROC, name: 'Procédures', documentCount: 3, inReviewCount: 1, reliabilityAverage: 88 },
    { id: FOLDER_REF, name: 'Référence', documentCount: 2, inReviewCount: 0, reliabilityAverage: 95 },
  ],
  rootDocuments: [
    {
      id: DOC_POLITIQUE,
      title: 'Politique de gestion des accès',
      status: 'valide',
      docType: 'politique',
      updatedAt: '2026-09-12T10:00:00.000Z',
      updatedBy: 'Tarek Fezai',
      reviewSince: null,
      tags: [TAG_IAM],
    },
    {
      id: DOC_REGISTRE,
      title: 'Registre des accès à privilèges',
      status: 'brouillon',
      docType: 'politique',
      updatedAt: '2026-09-28T10:00:00.000Z',
      updatedBy: 'Tarek Fezai',
      reviewSince: null,
      tags: [],
    },
  ],
  toReview: [
    {
      id: 'd0000001-0000-4000-8000-000000000013',
      title: 'Revue périodique des droits',
      status: 'en_revue',
      docType: 'procedure',
      updatedAt: '2026-09-20T08:00:00.000Z',
      updatedBy: 'Yanis M.',
      reviewSince: '2026-09-20T08:00:00.000Z',
      tags: [TAG_CRIT],
    },
  ],
  tags: [TAG_IAM, TAG_CRIT],
}

/** Réponse de GET /api/v1/spaces/{id}/page. */
export const SPACE_HOME_PAGE = {
  id: PAGE_ID,
  spaceId: SPACE_IDENTITE.id,
  folderId: null,
  title: 'Identité & accès',
  body: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: SPACE_HOME_PAGE_TEXT }] }] },
  status: 'brouillon',
  docType: 'page',
  currentVersionNo: 1,
  createdAt: '2026-05-02T10:00:00.000Z',
  updatedAt: '2026-09-28T10:00:00.000Z',
  createdBy: { kind: 'user', id: ME_TAREK.id, displayName: 'Tarek Fezai' },
  updatedBy: { kind: 'user', id: ME_TAREK.id, displayName: 'Tarek Fezai' },
  owner: null,
  tags: [],
  permissions: { canRead: true, canComment: true, canEdit: true, canDelete: true, canPublish: true },
}

/** Réponse de GET /api/v1/documents/{id}/comments. */
export const SPACE_HOME_COMMENTS = {
  documentId: PAGE_ID,
  versionNo: 1,
  threads: [],
  detached: [],
  openThreadCount: 0,
}
