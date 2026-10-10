// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Fixtures des écrans Access / SpaceSettings / Trash / Export / CreateSpace.
 * Règles : docs/visual-parity.md — valeurs OpenAPI uniquement, validées AJV.
 */
import { ME_TAREK, SPACE_IDENTITE, TREE_IDENTITE, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { SPACES_SEED } from './five-screens-fixtures.mjs'
import { pageDocument, PAGE_DOC_ID } from './page-fixtures.mjs'
import { TAGS_ADMIN_LIST } from './tags-admin-fixtures.mjs'

export { VISUAL_NOW, PAGE_DOC_ID }

const iso = (ms) => new Date(ms).toISOString()

export const SPACE_IDENTITE_SETTINGS = {
  ...SPACES_SEED[0],
  defaultVisibility: 'organisation',
  externalReference: 'restricted',
  commentPolicy: 'members',
}

/** GET /api/v1/spaces/:id/owners → GovernanceView */
export const SPACE_OWNERS_SEED = {
  spaceId: SPACE_IDENTITE.id,
  owners: [
    {
      userId: ME_TAREK.id,
      email: ME_TAREK.email,
      displayName: ME_TAREK.displayName,
      responsible: true,
    },
  ],
}

/**
 * Trash.dc.html : 3 documents, 4 / 12 / 27 jours, 26 / 18 / 3 jours restants.
 * @type {import('../src/lib/api-types.ts').components['schemas']['TrashPage']}
 */
export const TRASH_SEED = {
  items: [
    {
      id: 'b0000001-0000-4000-8000-000000000001',
      resourceType: 'document',
      resourceId: 'd0000001-0000-4000-8000-000000000021',
      title: 'Registre des accès à privilèges',
      snapshot: null,
      deletedBy: '22222222-2222-2222-2222-222222222222',
      deletedByName: 'Claire Dubois',
      deletedAt: iso(VISUAL_NOW - 4 * 24 * 60 * 60_000),
      purgeAt: iso(VISUAL_NOW + 26 * 24 * 60 * 60_000),
    },
    {
      id: 'b0000001-0000-4000-8000-000000000002',
      resourceType: 'document',
      resourceId: 'd0000001-0000-4000-8000-000000000022',
      title: 'Ancienne procédure de badge physique',
      snapshot: null,
      deletedBy: ME_TAREK.id,
      deletedByName: 'Tarek Fezai',
      deletedAt: iso(VISUAL_NOW - 12 * 24 * 60 * 60_000),
      purgeAt: iso(VISUAL_NOW + 18 * 24 * 60 * 60_000),
    },
    {
      id: 'b0000001-0000-4000-8000-000000000003',
      resourceType: 'document',
      resourceId: 'd0000001-0000-4000-8000-000000000023',
      title: 'Brouillon — Politique mobile (obsolète)',
      snapshot: null,
      deletedBy: '33333333-3333-3333-3333-333333333333',
      deletedByName: 'Yanis M.',
      deletedAt: iso(VISUAL_NOW - 27 * 24 * 60 * 60_000),
      purgeAt: iso(VISUAL_NOW + 3 * 24 * 60 * 60_000),
    },
  ],
  offset: 0,
  limit: 50,
  total: 3,
}

export const ACCESS_DOC = pageDocument('desktop')

const CLAIRE_ID = '22222222-2222-2222-2222-222222222222'
const YANIS_ID = '33333333-3333-3333-3333-333333333333'
const TEAM_ID = 'g0000001-0000-4000-8000-000000000001'

/**
 * GET /api/v1/access/document/:id — champs AccessEntry uniquement (pas de nom / initiales / last-active).
 * @type {import('../src/lib/api-types.ts').components['schemas']['AccessListResponse']}
 */
export const ACCESS_DOC_SEED = {
  objectType: 'document',
  objectId: PAGE_DOC_ID,
  canManage: true,
  entries: [
    {
      subject: `user:${ME_TAREK.id}`,
      subjectType: 'user',
      subjectId: ME_TAREK.id,
      relation: 'owner',
      source: 'direct',
      revocable: true,
    },
    {
      subject: `user:${CLAIRE_ID}`,
      subjectType: 'user',
      subjectId: CLAIRE_ID,
      relation: 'editor',
      source: 'direct',
      revocable: true,
    },
    {
      subject: `user:${YANIS_ID}`,
      subjectType: 'user',
      subjectId: YANIS_ID,
      relation: 'editor',
      source: 'direct',
      revocable: true,
    },
    {
      subject: `group:${TEAM_ID}`,
      subjectType: 'group',
      subjectId: TEAM_ID,
      relation: 'viewer',
      source: 'inherited',
      inheritedFrom: `space:${SPACE_IDENTITE.id}`,
      revocable: false,
    },
  ],
}

export const FOLDER_PROCEDURES = {
  id: TREE_IDENTITE.folders[0].id,
  spaceId: SPACE_IDENTITE.id,
  parentFolderId: null,
  name: 'Procédures',
  position: 0,
  createdBy: ME_TAREK.id,
  createdAt: '2026-01-01T00:00:00.000Z',
  updatedAt: '2026-01-01T00:00:00.000Z',
  documentCount: 3,
  folderCount: 0,
}

export const TAG_IAM = TAGS_ADMIN_LIST.tags[0]

export const EXPORT_DOC_ID = PAGE_DOC_ID
export const EXPORT_FOLDER_ID = FOLDER_PROCEDURES.id
export const EXPORT_TAG_ID = TAG_IAM.id
