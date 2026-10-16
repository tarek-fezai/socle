// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Données d'écran dossier (réponses API explicites, pas de mutation du DOM).
 * GET /folders/{id} est le JSON de fixtures/ : même forme que le backend, y compris
 * les fiches. Les identifiants de la fixture sont stables ; un test Postgres
 * accepte des identifiants différents.
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { ME_TAREK, NOTIFICATIONS_SEED, SPACE_IDENTITE, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { mockBaseApis, json } from './visual-parity-kit.mjs'

export const FOLDER_PROC = 'f0000001-0000-4000-8000-0000000000a1'
export const FOLDER_REF = 'f0000001-0000-4000-8000-0000000000a2'
export const PAGE_PROC = 'd0000001-0000-4000-8000-0000000000p1'
export const PAGE_REF = 'd0000001-0000-4000-8000-0000000000p2'

const DOC = {
  prov: 'd0000001-0000-4000-8000-000000000011',
  deprov: 'd0000001-0000-4000-8000-000000000012',
  revue: 'd0000001-0000-4000-8000-000000000013',
  roles: 'd0000001-0000-4000-8000-000000000021',
  gloss: 'd0000001-0000-4000-8000-000000000022',
}

const TAG_IAM = { id: 't0000001-0000-4000-8000-000000000001', name: 'IAM', color: '#3730E0', governed: false }
const TAG_CRIT = { id: 't0000001-0000-4000-8000-000000000002', name: 'Critique', color: '#B54708', governed: true }

const person = (id, displayName) => ({ id, displayName, email: null, initials: null })

const perms = {
  canEdit: true,
  canPublish: true,
  canManageAccess: true,
  canComment: true,
  canManageAttestations: false,
}

function text(value, marks) {
  return marks ? { type: 'text', text: value, marks } : { type: 'text', text: value }
}

function link(label, href) {
  return text(label, [{ type: 'link', attrs: { href } }])
}

function paragraph(parts) {
  return { type: 'paragraph', content: parts }
}

function item(parts) {
  return { type: 'listItem', content: [paragraph(parts)] }
}

const PROC_BODY = {
  type: 'doc',
  content: [
    paragraph([
      text(
        "Cette section rassemble les procédures opérationnelles du cycle de vie des accès — de la demande initiale à la révocation. Chaque procédure ci-dessous fait l'objet d'une revue indépendante et peut être approuvée séparément du reste de l'espace.",
      ),
    ]),
    {
      type: 'bulletList',
      content: [
        item([
          text('Toute nouvelle procédure doit référencer la '),
          link('Politique de gestion des accès', '/docs/politique'),
          text(' dans son introduction.'),
        ]),
        item([
          text('Les procédures marquées '),
          text('En revue', [{ type: 'bold' }]),
          text(' ne doivent pas être appliquées telles quelles.'),
        ]),
      ],
    },
  ],
}

const REF_BODY = {
  type: 'doc',
  content: [
    paragraph([
      text(
        'Cette section regroupe les documents de référence stables du domaine Identité & accès — rôles, habilitations et terminologie. Contrairement aux procédures, ces documents changent rarement et servent de source de vérité pour les autres espaces.',
      ),
    ]),
    {
      type: 'bulletList',
      content: [
        item([
          text("Toute modification d'un rôle référencé ici doit être répercutée dans "),
          link('Rôles globaux', '/admin/approval-roles'),
          text('.'),
        ]),
      ],
    },
  ],
}

function docView({ id, title, status, docType, updatedAt, author, tags }) {
  return {
    id,
    spaceId: SPACE_IDENTITE.id,
    folderId: id.endsWith('11') || id.endsWith('12') || id.endsWith('13') ? FOLDER_PROC : FOLDER_REF,
    title,
    body: { type: 'doc', content: [{ type: 'paragraph' }] },
    status,
    docType,
    currentVersionNo: 3,
    createdAt: updatedAt,
    updatedAt,
    createdBy: person(author.id, author.name),
    updatedBy: person(author.id, author.name),
    owner: null,
    tags,
    permissions: perms,
  }
}

const PROC_DOCS = [
  docView({
    id: DOC.prov,
    title: 'Provisioning des comptes',
    status: 'valide',
    docType: 'procedure',
    updatedAt: '2026-09-12T10:00:00.000Z',
    author: { id: ME_TAREK.id, name: 'Tarek Fezai' },
    tags: [TAG_IAM, TAG_CRIT],
  }),
  docView({
    id: DOC.deprov,
    title: 'Dé-provisioning des comptes',
    status: 'valide',
    docType: 'procedure',
    updatedAt: '2026-08-03T10:00:00.000Z',
    author: { id: '22222222-2222-2222-2222-222222222222', name: 'Claire Dubois' },
    tags: [TAG_IAM],
  }),
  docView({
    id: DOC.revue,
    title: 'Revue périodique des droits',
    status: 'en_revue',
    docType: 'procedure',
    updatedAt: '2026-09-20T10:00:00.000Z',
    author: { id: '33333333-3333-3333-3333-333333333333', name: 'Yanis M.' },
    tags: [TAG_CRIT],
  }),
]

const REF_DOCS = [
  docView({
    id: DOC.roles,
    title: 'Rôles & habilitations',
    status: 'valide',
    docType: 'Référence',
    updatedAt: '2026-07-18T10:00:00.000Z',
    author: { id: '44444444-4444-4444-4444-444444444444', name: 'Rania Lefèvre' },
    tags: [TAG_IAM],
  }),
  docView({
    id: DOC.gloss,
    title: 'Glossaire des permissions',
    status: 'valide',
    docType: 'Référence',
    updatedAt: '2026-09-02T10:00:00.000Z',
    author: { id: '55555555-5555-5555-5555-555555555555', name: 'Sami Lahlou' },
    tags: [TAG_IAM],
  }),
]

const FIXTURE_DIR = join(dirname(fileURLToPath(import.meta.url)), 'fixtures')

function folderView(kind) {
  const file = kind === 'procedures' ? 'folder-procedures-view.json' : 'folder-reference-view.json'
  return JSON.parse(readFileSync(join(FIXTURE_DIR, file), 'utf8'))
}

function pageView(kind) {
  const procedures = kind === 'procedures'
  return {
    id: procedures ? PAGE_PROC : PAGE_REF,
    spaceId: SPACE_IDENTITE.id,
    folderId: procedures ? FOLDER_PROC : FOLDER_REF,
    title: procedures ? 'Procédures' : 'Référence',
    body: procedures ? PROC_BODY : REF_BODY,
    status: 'brouillon',
    docType: 'page',
    currentVersionNo: 1,
    createdAt: procedures ? '2026-07-14T10:00:00.000Z' : '2026-06-03T10:00:00.000Z',
    updatedAt: procedures ? '2026-09-20T10:00:00.000Z' : '2026-07-18T10:00:00.000Z',
    createdBy: { kind: 'system', id: null, displayName: null },
    updatedBy: procedures ? person(ME_TAREK.id, 'Tarek Fezai') : person('44444444-4444-4444-4444-444444444444', 'Rania Lefèvre'),
    owner: null,
    tags: [],
    permissions: perms,
  }
}

function tree(kind) {
  const procedures = kind === 'procedures'
  const docs = procedures ? PROC_DOCS : REF_DOCS
  return {
    spaceId: SPACE_IDENTITE.id,
    spaceName: SPACE_IDENTITE.name,
    folders: [
      {
        id: procedures ? FOLDER_PROC : FOLDER_REF,
        name: procedures ? 'Procédures' : 'Référence',
        parentFolderId: null,
        position: 0,
        documentCount: docs.length,
        folderCount: 0,
      },
    ],
    documents: docs.map((d, i) => ({
      id: d.id,
      title: d.title,
      folderId: d.folderId,
      position: i,
      status: d.status,
    })),
  }
}

const byId = Object.fromEntries([...PROC_DOCS, ...REF_DOCS].map((d) => [d.id, d]))

export async function mockFolderApis(page, kind) {
  const folderId = kind === 'procedures' ? FOLDER_PROC : FOLDER_REF
  await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED, spaces: [SPACE_IDENTITE] })
  await page.route(`**/api/v1/folders/${folderId}/page`, (route) => json(route, pageView(kind)))
  const pageId = kind === 'procedures' ? PAGE_PROC : PAGE_REF
  await page.route(`**/api/v1/documents/${pageId}/comments**`, (route) =>
    json(route, {
      documentId: pageId,
      versionNo: 1,
      threads: [],
      detached: [],
      openThreadCount: 0,
    }),
  )
  await page.route(`**/api/v1/folders/${folderId}`, (route) => json(route, folderView(kind)))
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/tree**`, (route) => json(route, tree(kind)))
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}`, (route) => json(route, SPACE_IDENTITE))
  await page.route(/\/api\/v1\/documents\/[^/]+$/, (route) => {
    const id = route.request().url().split('/').pop()?.split('?')[0]
    const doc = byId[id]
    if (!doc) return json(route, {}, 404)
    return json(route, doc)
  })
  await page.route('**/api/v1/favorites/**', (route) => json(route, { favorited: false }))
}

/** data-mock-id sur la maquette (lecture seule du DOM : attributs data-* uniquement). */
export async function annotateFolderMock(page) {
  await page.evaluate(() => {
    const set = (el, id) => el?.setAttribute('data-mock-id', id)
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    const sidebar = root?.children?.[0]
    const tree = sidebar
      ? [...sidebar.querySelectorAll('div')].find((d) => {
          const st = d.getAttribute('style') || ''
          return st.includes('flex-direction: column') && st.includes('gap: 1px')
        })
      : null
    if (tree) {
      const kids = [...tree.children]
      const activeIdx = kids.findIndex((el) => {
        const st = el.getAttribute('style') || ''
        return st.includes('#F0EFFC') && st.includes('11.5px')
      })
      if (activeIdx >= 0) {
        const group = [kids[activeIdx]]
        for (let i = activeIdx + 1; i < kids.length; i++) {
          const st = kids[i].getAttribute('style') || ''
          if (st.includes('padding: 7px 10px 7px 22px')) group.push(kids[i])
          else break
        }
        const wrap = document.createElement('div')
        wrap.setAttribute('data-mock-id', 'shell-tree-folders')
        wrap.setAttribute('style', 'display: flex; flex-direction: column; gap: 1px; min-width: 0;')
        tree.insertBefore(wrap, kids[activeIdx])
        for (const el of group) {
          el.style.marginTop = '0'
          wrap.appendChild(el)
        }
      }
    }
    const main = root.children[1]
    const topbar = main.children[0]
    const tabs = main.children[1]
    const content = main.children[2]
    set(topbar.children[0], 'fp-crumbs')
    set(topbar.children[1], 'fp-actions')
    set(tabs, 'fp-tabs')
    const article = content.children[0]
    const rail = content.children[1]
    const k = [...article.children]
    set(k[0], 'fp-meta')
    set(k[1], 'fp-title')
    set(k[2], 'fp-body')
    set(k[3], 'fp-count')
    ;[...k[4].children].forEach((card, i) => set(card, `fp-card-${i}`))
    const blocks = [...rail.firstElementChild.children]
    ;['fp-rail-docs', 'fp-rail-owner', 'fp-rail-author', 'fp-rail-modified', 'fp-rail-tags', 'fp-rail-reliability', 'fp-rail-export'].forEach(
      (id, i) => set(blocks[i], id),
    )
  })
}

export function folderSections(cardCount) {
  return [
    { id: 'fp-crumbs', name: 'crumbs' },
    { id: 'fp-actions', name: 'actions' },
    { id: 'fp-tabs', name: 'tabs' },
    { id: 'fp-meta', name: 'meta' },
    { id: 'fp-title', name: 'title' },
    { id: 'fp-body', name: 'body' },
    { id: 'fp-count', name: 'count' },
    ...Array.from({ length: cardCount }, (_, i) => ({ id: `fp-card-${i}`, name: `card-${i}` })),
    { id: 'fp-rail-docs', name: 'rail-docs' },
    { id: 'fp-rail-owner', name: 'rail-owner' },
    { id: 'fp-rail-author', name: 'rail-author' },
    { id: 'fp-rail-modified', name: 'rail-modified' },
    { id: 'fp-rail-tags', name: 'rail-tags' },
    { id: 'fp-rail-reliability', name: 'rail-reliability' },
    { id: 'fp-rail-export', name: 'rail-export' },
    { id: 'shell-tree-folders', name: 'shell-tree-folders' },
  ]
}

export { VISUAL_NOW }
