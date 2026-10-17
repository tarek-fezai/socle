// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * SpaceHome.dc.html vs /spaces/:id @ 1440×900.
 * Texte inclus, seuil 1 % par section. La sidebar n'est pas comparée
 * (décision tf 2026-10-10 : shell canonique). Annotation : data-mock-id seulement.
 * Fixture : GET /spaces/{id} (SpaceDetail) et GET /spaces/{id}/page (DocumentResponse),
 * validées par AJV (fixture-contract.test.ts). Même jeu que get_matchesSpaceHomeMockup
 * (7 documents, 2 dossiers, fiabilités 88 / 95 / 91).
 * Les effectifs du rail (12 membres, 3 équipes, tag Critique, paragraphe long,
 * dates de la maquette) sont les valeurs de SpaceHome.dc.html, conformes au contrat
 * et validées par AJV.
 */
import { test } from '@playwright/test'
import { ME_TAREK, NOTIFICATIONS_SEED, SPACE_IDENTITE, VISUAL_NOW } from './dashboard-fixtures.mjs'
import {
  SPACE_HOME_COMMENTS,
  SPACE_HOME_DETAIL,
  SPACE_HOME_PAGE,
  SPACE_HOME_PAGE_TEXT,
} from './space-home-fixtures.mjs'
import {
  MOCK_ORIGIN,
  assertNotImplementedGuards,
  compareSectionShots,
  finishPixelResults,
  injectOidcSession,
  json,
  mockBaseApis,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

const MAC_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'

const SPACE_ID = SPACE_HOME_DETAIL.id
const PAGE_ID = SPACE_HOME_PAGE.id

/** Le menu n'est pas ouvert : la surface mesurable est le bouton qui annonce Archiver. */
export const NOT_IMPLEMENTED = [
  {
    id: 'space-home-archive',
    page: 'space-home',
    reason: "Pas d'API d'archivage d'espace.",
    backlog: 'SPACE-ARCHIVE',
    mockMeasureSelector: 'button[aria-label="Autres actions de l\'espace"]',
    appPlaceholderSelector: 'button[aria-label="Autres actions de l\'espace"]',
    appImplementedProbe: '[data-testid="space-archived"]',
  },
]

const SECTIONS = [
  { id: 'sh-crumbs', name: 'crumbs' },
  { id: 'sh-actions', name: 'actions' },
  { id: 'sh-tabs', name: 'tabs' },
  { id: 'sh-meta', name: 'meta' },
  { id: 'sh-title', name: 'title' },
  { id: 'sh-body', name: 'body' },
  { id: 'sh-count', name: 'count' },
  { id: 'sh-folders-label', name: 'folders-label' },
  { id: 'sh-folder-0', name: 'folder-0' },
  { id: 'sh-folder-1', name: 'folder-1' },
  { id: 'sh-root-label', name: 'root-label' },
  { id: 'sh-card-0', name: 'card-0' },
  { id: 'sh-card-1', name: 'card-1' },
  { id: 'sh-rail-owner', name: 'rail-owner' },
  { id: 'sh-rail-members', name: 'rail-members' },
  { id: 'sh-rail-activity', name: 'rail-activity' },
  { id: 'sh-rail-tags', name: 'rail-tags' },
  { id: 'sh-rail-reliability', name: 'rail-reliability' },
  { id: 'sh-rail-review', name: 'rail-review' },
  { id: 'sh-rail-export', name: 'rail-export' },
]

test.use({
  timezoneId: 'Europe/Paris',
  userAgent: MAC_UA,
  launchOptions: { args: ['--disable-lcd-text'] },
})

async function annotateSpaceHome(page) {
  await page.evaluate(() => {
    const set = (el, id) => {
      if (!el) throw new Error(`annotation manquante : ${id}`)
      el.setAttribute('data-mock-id', id)
    }
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    const main = root?.children?.[1]
    const topbar = main?.children?.[0]
    const tabs = main?.children?.[1]
    const content = main?.children?.[2]
    const article = content?.children?.[0]
    const rail = content?.children?.[1]
    set(topbar?.children?.[0], 'sh-crumbs')
    set(topbar?.children?.[1], 'sh-actions')
    set(tabs, 'sh-tabs')
    const k = [...(article?.children ?? [])]
    set(k[0], 'sh-meta')
    set(k[1], 'sh-title')
    set(k[2], 'sh-body')
    set(k[3], 'sh-count')
    set(k[4], 'sh-folders-label')
    const folderCards = [...(k[5]?.children ?? [])]
    set(folderCards[0], 'sh-folder-0')
    set(folderCards[1], 'sh-folder-1')
    set(k[6], 'sh-root-label')
    const docs = [...(k[7]?.children ?? [])]
    set(docs[0], 'sh-card-0')
    set(docs[1], 'sh-card-1')
    const blocks = [...(rail?.firstElementChild?.children ?? [])]
    ;[
      'sh-rail-owner',
      'sh-rail-members',
      'sh-rail-activity',
      'sh-rail-tags',
      'sh-rail-reliability',
      'sh-rail-review',
      'sh-rail-export',
    ].forEach((id, i) => set(blocks[i], id))
  })
}

async function mockSpaceHome(page) {
  await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED, spaces: [SPACE_IDENTITE] })
  await page.route(`**/api/v1/spaces/${SPACE_ID}/page`, (route) => json(route, SPACE_HOME_PAGE))
  await page.route(`**/api/v1/documents/${PAGE_ID}/comments**`, (route) => json(route, SPACE_HOME_COMMENTS))
  await page.route(`**/api/v1/spaces/${SPACE_ID}`, (route) => {
    const path = new URL(route.request().url()).pathname
    if (!path.endsWith(`/spaces/${SPACE_ID}`)) return route.fallback()
    return json(route, SPACE_HOME_DETAIL)
  })
  await page.route('**/api/v1/favorites/**', (route) => json(route, { favorited: false }))
}

test('desktop SpaceHome — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
  await injectOidcSession(page, VISUAL_NOW)
  await page.addInitScript((spaceId) => {
    localStorage.setItem('socle.shell.expandedSpaces', JSON.stringify([spaceId]))
  }, SPACE_ID)
  await mockSpaceHome(page)
  await page.setViewportSize({ width: 1440, height: 900 })

  await page.goto(`${MOCK_ORIGIN}/SpaceHome.dc.html`)
  await annotateSpaceHome(page)
  await settleFonts(page)
  await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'space-home')
  const mock = {}
  for (const s of SECTIONS) {
    mock[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
      label: `space-home-${s.name}-mock`,
    })
  }

  await page.goto(`/spaces/${SPACE_ID}`)
  await page.waitForSelector('[data-mock-id="sh-title"]')
  await page.waitForSelector(`text=${SPACE_HOME_PAGE_TEXT.slice(0, 24)}`)
  await page.waitForSelector('text=7 documents · 2 dossiers')
  await page.waitForSelector('text=En revue depuis le 20 septembre 2026')
  await page.waitForSelector('text=Créé le 28 septembre 2026')
  await settleFonts(page)
  await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'space-home')

  const compare = compareSectionShots()
  const results = []
  for (const s of SECTIONS) {
    await page.mouse.move(1, 1)
    const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
      label: `space-home-${s.name}-app`,
    })
    results.push(compare(`space-home-${s.name}`, mock[s.name].shot, app.shot, testInfo))
  }
  finishPixelResults('space-home', results)
})
