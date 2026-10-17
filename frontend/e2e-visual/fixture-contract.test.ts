// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Valide les fixtures e2e-visual contre les schémas OpenAPI commités.
 * Une fixture non conforme doit faire échouer la CI.
 *
 * Springdoc omet souvent `nullable` sur les records Java : on autorise `null`
 * en plus du type déclaré, et on assouplit le format UUID (fixtures historiques).
 */
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
import { describe, expect, it } from 'vitest'
import {
  FAVORITES_SEED,
  HOME_MOBILE_SEED,
  HOME_SEED,
  NOTIFICATIONS_SEED,
  SPACE_IDENTITE,
  SPACE_INFRA,
  TREE_IDENTITE,
  TREE_IDENTITE_MENU,
  TREE_INFRA,
} from './dashboard-fixtures.mjs'
import { BRANDING_ADMIN_VIEW, PUBLIC_BRANDING_VIEW } from './branding-admin-fixtures.mjs'
import {
  CUSTOM_FIELDS_ADMIN_LIST,
  SPACE_CONFORMITE,
  SPACE_INFRA as CF_SPACE_INFRA,
} from './custom-fields-admin-fixtures.mjs'
import { RETENTION_ADMIN_SETTINGS, RETENTION_LEGAL_HOLDS } from './retention-admin-fixtures.mjs'
import { TAGS_ADMIN_LIST } from './tags-admin-fixtures.mjs'
import { EDIT_CUSTOM_FIELDS, EDIT_WRITING_HINTS, editDocument } from './edit-fixtures.mjs'
import { COMPARE_11_12, HIST_VERSIONS_DESKTOP, HIST_VERSIONS_MOBILE } from './history-fixtures.mjs'
import {
  APPR_DETAIL_DESKTOP,
  APPR_DETAIL_MOBILE,
  APPR_ITEM_DESKTOP,
  APPR_ITEM_MOBILE,
} from './approval-fixtures.mjs'
import {
  PAGE_ATTESTATION,
  PAGE_FEEDBACK_EDITOR,
  PAGE_FEEDBACK_VIEWER,
  pageDocument,
} from './page-fixtures.mjs'
import { ACCOUNT_VISUAL_NOW, ADMIN_OVERVIEW_SEED, PAT_TOKENS_SEED } from './account-admin-fixtures.mjs'
import {
  FAVORITES_LIST_SEED,
  NOTIFICATIONS_LIST_SEED,
  SEARCH_SEED,
  SPACES_SEED,
  TEMPLATES_SEED,
  VISUAL_NOW as FIVE_SCREENS_NOW,
} from './five-screens-fixtures.mjs'
import {
  ACCESS_DOC_SEED,
  FOLDER_PROCEDURES,
  SPACE_IDENTITE_SETTINGS,
  SPACE_OWNERS_SEED,
  TRASH_SEED,
} from './parity-screens-fixtures.mjs'
import { TEAM_ADMIN_USERS } from './team-fixtures.mjs'
import { SPACE_HOME_DETAIL, SPACE_HOME_PAGE } from './space-home-fixtures.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const openapi = JSON.parse(readFileSync(resolve(root, 'openapi/openapi.json'), 'utf8'))

/** Autorise null sur chaque type scalaire/objet (parité Jackson nullable). */
function allowNullTypes(node: unknown): unknown {
  if (Array.isArray(node)) return node.map(allowNullTypes)
  if (!node || typeof node !== 'object') return node
  const obj = node as Record<string, unknown>
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(obj)) {
    if (k === 'type' && typeof v === 'string') {
      out.type = [v, 'null']
    } else if (k === 'type' && Array.isArray(v) && !v.includes('null')) {
      out.type = [...v, 'null']
    } else {
      out[k] = allowNullTypes(v)
    }
  }
  return out
}

const ajv = new Ajv2020({
  allErrors: true,
  strict: false,
  validateFormats: true,
})
addFormats(ajv)
// Fixtures historiques utilisent parfois des UUID non RFC (préfixe s-/d-/f-).
ajv.addFormat('uuid', true)

const doc = allowNullTypes({
  ...openapi,
  $id: 'https://socle.local/openapi.json',
}) as object
ajv.addSchema(doc)

function validate(schemaName: string, data: unknown) {
  const schema = {
    $ref: `https://socle.local/openapi.json#/components/schemas/${schemaName}`,
  }
  const validateFn = ajv.compile(schema)
  const ok = validateFn(data)
  if (!ok) {
    const detail = (validateFn.errors ?? [])
      .map((e) => `${e.instancePath || '/'} ${e.message}`)
      .join('; ')
    expect.fail(`${schemaName}: ${detail}`)
  }
}

describe('e2e-visual fixtures ↔ OpenAPI', () => {
  it('HOME_SEED matches HomeResponse', () => {
    validate('HomeResponse', HOME_SEED)
  })

  describe('fixtures distinctes par maquette (Dashboard desktop / MobileHome / MobileMenu)', () => {
    it('HOME_MOBILE_SEED matches HomeResponse et diffère de HOME_SEED', () => {
      validate('HomeResponse', HOME_MOBILE_SEED)
      expect(HOME_MOBILE_SEED).not.toEqual(HOME_SEED)
    })

    it('arbres d’espace : SpaceTreeResponse', () => {
      validate('SpaceTreeResponse', TREE_IDENTITE)
      validate('SpaceTreeResponse', TREE_IDENTITE_MENU)
      validate('SpaceTreeResponse', TREE_INFRA)
      expect(TREE_IDENTITE_MENU).not.toEqual(TREE_IDENTITE)
    })

    it.each([
      ['TREE_IDENTITE', TREE_IDENTITE],
      ['TREE_IDENTITE_MENU', TREE_IDENTITE_MENU],
      ['TREE_INFRA', TREE_INFRA],
    ])('%s : identifiants de document uniques', (_label, tree) => {
      const ids: string[] = []
      const walk = (node: unknown) => {
        if (Array.isArray(node)) return node.forEach(walk)
        if (!node || typeof node !== 'object') return
        for (const [k, v] of Object.entries(node)) {
          if (k === 'documents' && Array.isArray(v)) for (const d of v) ids.push((d as { id: string }).id)
          walk(v)
        }
      }
      walk(tree)
      expect(ids.length).toBeGreaterThan(0)
      expect(new Set(ids).size).toBe(ids.length)
    })

    it('espaces : SpaceView', () => {
      for (const s of [SPACE_IDENTITE, SPACE_INFRA, SPACE_CONFORMITE, CF_SPACE_INFRA]) {
        validate('SpaceView', s)
      }
    })

    it('shell : NotificationPage, FavoriteItem[]', () => {
      validate('NotificationPage', NOTIFICATIONS_SEED)
      expect(Array.isArray(FAVORITES_SEED)).toBe(true)
      for (const f of FAVORITES_SEED) validate('FavoriteItem', f)
    })
  })

  describe('écrans d’administration', () => {
    it('BRANDING_ADMIN_VIEW / PUBLIC_BRANDING_VIEW', () => {
      validate('BrandingAdminView', BRANDING_ADMIN_VIEW)
      validate('PublicBrandingView', PUBLIC_BRANDING_VIEW)
    })

    it('CUSTOM_FIELDS_ADMIN_LIST matches FieldListResponse', () => {
      validate('FieldListResponse', CUSTOM_FIELDS_ADMIN_LIST)
    })

    it('TAGS_ADMIN_LIST matches TagListResponse', () => {
      validate('TagListResponse', TAGS_ADMIN_LIST)
    })

    it('RETENTION_* match RetentionSettingsView / LegalHoldListResponse', () => {
      validate('RetentionSettingsView', RETENTION_ADMIN_SETTINGS)
      validate('LegalHoldListResponse', RETENTION_LEGAL_HOLDS)
    })
  })

  it('pageDocument matches DocumentResponse', () => {
    validate('DocumentResponse', pageDocument('desktop'))
  })

  it('editDocument matches DocumentResponse', () => {
    validate('DocumentResponse', editDocument())
  })

  describe('pièces jointes dans les corps TipTap', () => {
    type Node = { type: string; attrs?: Record<string, unknown>; content?: Node[] }
    const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
    const bodies: Array<[string, Node]> = [
      ['page desktop', pageDocument('desktop').body as Node],
      ['page mobile', pageDocument('mobile').body as Node],
      ['edit', editDocument().body as Node],
    ]

    it.each(bodies)('%s : un nœud image + un nœud attachment valides', (_label, body) => {
      const top = body.content ?? []
      const images = top.filter((n) => n.type === 'image')
      const files = top.filter((n) => n.type === 'attachment')
      expect(images).toHaveLength(1)
      expect(files).toHaveLength(1)

      const img = images[0].attrs ?? {}
      expect(img.id).toMatch(UUID)
      expect(img.filename).toEqual(expect.any(String))
      expect(String(img.mediaType)).toMatch(/^image\//)
      expect(img.sizeBytes).toEqual(expect.any(Number))
      expect(img.width).toEqual(expect.any(Number))
      expect(img.height).toEqual(expect.any(Number))

      const file = files[0].attrs ?? {}
      expect(file.id).toMatch(UUID)
      expect(file.filename).toEqual(expect.any(String))
      expect(file.mediaType).toEqual(expect.any(String))
      expect(file.sizeBytes).toEqual(expect.any(Number))
      expect(file.id).not.toBe(img.id)
    })
  })

  it('PAGE_ATTESTATION matches ActiveAttestation', () => {
    validate('ActiveAttestation', PAGE_ATTESTATION)
  })

  it('PAGE_FEEDBACK_* match FeedbackView', () => {
    validate('FeedbackView', PAGE_FEEDBACK_EDITOR)
    validate('FeedbackView', PAGE_FEEDBACK_VIEWER)
  })

  it('HIST_VERSIONS_* items match VersionSummary', () => {
    for (const v of HIST_VERSIONS_DESKTOP) validate('VersionSummary', v)
    for (const v of HIST_VERSIONS_MOBILE) validate('VersionSummary', v)
  })

  it('COMPARE_11_12 matches VersionCompareResponse', () => {
    validate('VersionCompareResponse', COMPARE_11_12)
  })

  it('EDIT_WRITING_HINTS matches Hints', () => {
    validate('Hints', EDIT_WRITING_HINTS)
  })

  it('aucune fixture brokenLinks ne peut contenir accessible: true', () => {
    const fixtures: unknown[] = [EDIT_WRITING_HINTS]
    for (const fixture of fixtures) {
      const links = (fixture as { brokenLinks?: Array<{ accessible?: boolean }> }).brokenLinks ?? []
      for (const link of links) {
        expect(link.accessible, 'brokenLinks.accessible must not be true').not.toBe(true)
      }
    }
  })

  it('EDIT_CUSTOM_FIELDS items match CustomFieldView', () => {
    for (const f of EDIT_CUSTOM_FIELDS) validate('CustomFieldView', f)
  })

  it('APPR_ITEM_* match ApprovalView', () => {
    validate('ApprovalView', APPR_ITEM_DESKTOP)
    validate('ApprovalView', APPR_ITEM_MOBILE)
  })

  it('APPR_DETAIL_* match ApprovalDetailView', () => {
    validate('ApprovalDetailView', APPR_DETAIL_DESKTOP)
    validate('ApprovalDetailView', APPR_DETAIL_MOBILE)
  })

  it('ADMIN_OVERVIEW_SEED matches AdminOverviewView', () => {
    validate('AdminOverviewView', ADMIN_OVERVIEW_SEED)
  })

  it('PAT_TOKENS_SEED matches PersonalAccessToken (expiration obligatoire ≤ 90 jours)', () => {
    expect(PAT_TOKENS_SEED).toHaveLength(2)
    for (const t of PAT_TOKENS_SEED) {
      validate('PersonalAccessToken', t)
      expect(t.expiresAt, `${t.name}: expiresAt`).toBeTruthy()
      const days = (Date.parse(t.expiresAt!) - Date.parse(t.createdAt!)) / 86_400_000
      expect(days).toBeGreaterThan(0)
      expect(days).toBeLessThanOrEqual(90)
      expect(Date.parse(t.createdAt!)).toBeLessThanOrEqual(ACCOUNT_VISUAL_NOW)
    }
    expect(PAT_TOKENS_SEED.some((t) => t.scope === 'read_write')).toBe(true)
  })

  describe('cinq écrans parité visuelle (Spaces / Search / Notifications / Favorites / NewDocument)', () => {
    it('SPACES_SEED : 5 SpaceView', () => {
      expect(SPACES_SEED).toHaveLength(5)
      for (const s of SPACES_SEED) validate('SpaceView', s)
      expect(SPACES_SEED.map((s) => s.name)).toEqual([
        'Identité & accès',
        'Infrastructure',
        'Conformité',
        'Produit',
        'Ressources humaines',
      ])
    })

    it('NOTIFICATIONS_LIST_SEED : NotificationPage, types réels uniquement', () => {
      validate('NotificationPage', NOTIFICATIONS_LIST_SEED)
      for (const n of NOTIFICATIONS_LIST_SEED.items ?? []) validate('NotificationView', n)
      const real = new Set([
        'comment_mention',
        'approval_chain_exhausted',
        'pat_expiring',
        'external_reference_first',
      ])
      for (const n of NOTIFICATIONS_LIST_SEED.items ?? []) {
        expect(real.has(n.type as string), `type ${n.type}`).toBe(true)
      }
      const unread = (NOTIFICATIONS_LIST_SEED.items ?? []).filter((n) => !n.readAt).length
      expect(NOTIFICATIONS_LIST_SEED.unreadCount).toBe(unread)
      expect(NOTIFICATIONS_LIST_SEED.total).toBe(NOTIFICATIONS_LIST_SEED.items?.length)
      // Fenêtre relative figée : toutes les notifications précèdent VISUAL_NOW.
      for (const n of NOTIFICATIONS_LIST_SEED.items ?? []) {
        expect(Date.parse(n.createdAt as string)).toBeLessThanOrEqual(FIVE_SCREENS_NOW)
      }
    })

    it('FAVORITES_LIST_SEED : tableau de FavoriteItem (targetType / targetId + méta espace)', () => {
      expect(Array.isArray(FAVORITES_LIST_SEED)).toBe(true)
      const allowed = new Set([
        'createdAt',
        'targetId',
        'targetType',
        'title',
        'status',
        'updatedAt',
        'spaceId',
        'spaceName',
        'documentCount',
      ])
      for (const f of FAVORITES_LIST_SEED) {
        validate('FavoriteItem', f)
        expect(Object.keys(f).every((k) => allowed.has(k)), `clé inattendue ${Object.keys(f)}`).toBe(
          true,
        )
        expect(f).toHaveProperty('targetType')
        expect(f).toHaveProperty('targetId')
        expect(f).toHaveProperty('spaceName')
        expect(['document', 'folder', 'space']).toContain(f.targetType)
      }
    })

    it('SEARCH_SEED : SearchResponse + SearchHit', () => {
      validate('SearchResponse', SEARCH_SEED)
      for (const h of SEARCH_SEED.results ?? []) validate('SearchHit', h)
      expect(SEARCH_SEED.query).toBe('provisioning')
    })

    it('TEMPLATES_SEED : TemplateSummary[]', () => {
      for (const t of TEMPLATES_SEED) validate('TemplateSummary', t)
    })
  })

  describe('écrans Access / Settings / Trash / Export / CreateSpace', () => {
    it('TRASH_SEED matches TrashPage / TrashItemView', () => {
      validate('TrashPage', TRASH_SEED)
      for (const item of TRASH_SEED.items ?? []) validate('TrashItemView', item)
    })

    it('ACCESS_DOC_SEED matches AccessListResponse / AccessEntry', () => {
      validate('AccessListResponse', ACCESS_DOC_SEED)
      for (const e of ACCESS_DOC_SEED.entries ?? []) validate('AccessEntry', e)
    })

    it('TEAM_ADMIN_USERS matches AdminUsersResponse', () => {
      validate('AdminUsersResponse', TEAM_ADMIN_USERS)
    })

    it('SPACE_IDENTITE_SETTINGS matches SpaceView', () => {
      validate('SpaceView', SPACE_IDENTITE_SETTINGS)
    })

    it('SPACE_OWNERS_SEED matches GovernanceView', () => {
      validate('GovernanceView', SPACE_OWNERS_SEED)
    })

    it('FOLDER_PROCEDURES matches FolderView', () => {
      validate('FolderView', FOLDER_PROCEDURES)
    })
  })

  describe('accueil d’espace', () => {
    it('SPACE_HOME_DETAIL matches SpaceDetail', () => {
      validate('SpaceDetail', SPACE_HOME_DETAIL)
    })

    it('SPACE_HOME_PAGE matches DocumentResponse', () => {
      validate('DocumentResponse', SPACE_HOME_PAGE)
    })
  })
})

