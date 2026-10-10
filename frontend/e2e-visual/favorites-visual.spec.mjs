// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * /favorites vs Favorites.dc.html @ 1440×900 (desktop uniquement ; colonne principale — le shell
 * est celui d'AppShell, déjà couvert par les autres specs).
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé.
 * Annotation maquette : data-* uniquement.
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/favorites-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/favorites-before.json, n'échoue pas).
 */
import { test, expect } from '@playwright/test'
import { collectMetrics, compareMetrics } from './structural-compare.mjs'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { FAVORITES_LIST_SEED, SPACES_SEED } from './five-screens-fixtures.mjs'
import {
  MOCK_ORIGIN,
  assertNotImplementedGuards,
  compareSectionShots,
  expectNoStructuralDiffs,
  finishPixelResults,
  injectOidcSession,
  json,
  mockBaseApis,
  reportNotImplemented,
  settleFonts,
  shotSection,
} from './visual-parity-kit.mjs'

/**
 * Zones maquette sans donnée produit — NON comparées, jamais « passées ».
 * @type {import('./visual-parity-kit.mjs').NotImplementedZone[]}
 */
export const NOT_IMPLEMENTED = [
  {
    id: 'favorites-row-meta',
    page: 'favorites',
    reason:
      'Ligne méta sous le titre (« Validé · révisé le … », « Brouillon · modifié il y a … », « Espace · 14 documents ») : FavoriteItem n’a ni statut ni date de révision — l’app n’affiche que le type de ressource',
    backlog: 'FAVORITES-META',
    mockMask: 'favorites-row-meta',
    appPlaceholderSelector: '[data-visual-mask="favorites-row-meta"]',
    appImplementedProbe: 'text=/révisé le/',
  },
  {
    id: 'favorites-group-head',
    page: 'favorites',
    reason:
      'Regroupement par espace (« Identité & accès », « Infrastructure ») : FavoriteItem ne porte pas l’espace parent — liste plate dans l’app',
    backlog: 'FAVORITES-GROUP-BY-SPACE',
    mockMask: 'favorites-group-head',
    appImplementedProbe: '[data-mock-id^="favorites-group-"]',
  },
]

/**
 * La maquette ancre le contenu à gauche avec la même largeur de colonne (900 px) : tailles
 * attendues identiques, aucune SIZE_EXCEPTION.
 */
const SIZE_EXCEPTIONS = {}

const ROWS = [0, 1, 2, 3, 4]

const PIXEL_SECTIONS = [
  { id: 'favorites-title', name: 'title', masks: [] },
  { id: 'favorites-lead', name: 'lead', masks: [] },
  ...ROWS.map((i) => ({
    id: `favorites-row-${i}`,
    name: `row-${i}`,
    masks: ['favorites-row-meta'],
  })),
]

const STRUCTURAL_IDS = [
  'favorites-title',
  'favorites-lead',
  'favorites-row-0',
  'favorites-row-0-title',
  'favorites-row-1',
  'favorites-row-1-title',
  'favorites-row-3',
  'favorites-row-3-title',
]

async function mockApis(page) {
  await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED, spaces: SPACES_SEED })
  // Dernier enregistré gagne : surcharge de la liste de favoris par défaut (vide).
  await page.route('**/api/v1/favorites', (route) => json(route, FAVORITES_LIST_SEED))
}

/** Annotation Favorites.dc.html — data-* uniquement. */
async function annotateFavoritesMockup(page) {
  await page.evaluate(() => {
    const h1 = document.querySelector('h1')
    h1?.setAttribute('data-mock-id', 'favorites-title')
    h1?.nextElementSibling?.setAttribute('data-mock-id', 'favorites-lead')
    const rows = [...document.querySelectorAll('a.row')]
    rows.forEach((a, i) => {
      a.setAttribute('data-mock-id', `favorites-row-${i}`)
      const text = a.children[1]
      text?.children[0]?.setAttribute('data-mock-id', `favorites-row-${i}-title`)
      text?.children[1]?.setAttribute('data-visual-mask', 'favorites-row-meta')
    })
    const heads = new Set()
    rows.forEach((a) => heads.add(a.parentElement?.previousElementSibling))
    ;[...heads].forEach((h) => h?.setAttribute('data-visual-mask', 'favorites-group-head'))
  })
}

test.describe('favorites visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop Favoris — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Favorites.dc.html`)
    await annotateFavoritesMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'favorites')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `favorites-sec-${s.name}-mock`,
      })
    }

    await page.goto('/favorites')
    await page.waitForSelector('[data-mock-id="favorites-row-4"]')
    await page.mouse.move(1, 1)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'favorites')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `favorites-sec-${s.name}-app`,
      })
      results.push(compare(`favorites-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('favorites', results)
  })

  test('Favoris structural — titre, intro, lignes', async ({ page }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Favorites.dc.html`)
    await annotateFavoritesMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, STRUCTURAL_IDS)

    await page.goto('/favorites')
    await page.waitForSelector('[data-mock-id="favorites-row-4"]')
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, STRUCTURAL_IDS)

    const META =
      'Position verticale : la maquette intercale des titres de groupe (NOT_IMPLEMENTED favorites-group-head) → box.y décalé pour les lignes suivantes.'
    const TEXT_NOTE = 'Texte : ligne méta NOT_IMPLEMENTED (titre comparé via -title).'
    const pageExceptions = {
      'favorites-row-0': {
        skip: ['text', 'box.y'],
        reason: `${META} ${TEXT_NOTE}`,
      },
      'favorites-row-0-title': { skip: ['box.y'], reason: META },
      'favorites-row-1': {
        skip: ['text', 'box.y'],
        reason: `${META} ${TEXT_NOTE}`,
      },
      'favorites-row-1-title': { skip: ['box.y'], reason: META },
      'favorites-row-3': {
        skip: ['text', 'box.y'],
        reason: `${META} ${TEXT_NOTE}`,
      },
      'favorites-row-3-title': { skip: ['box.y'], reason: META },
    }
    const results = compareMetrics(mockMetrics, appMetrics, STRUCTURAL_IDS, { pageExceptions })
    expectNoStructuralDiffs(results)
  })

  test('étoile : retire le favori', async ({ page }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    let deleted = ''
    await page.route('**/api/v1/favorites/**', (route) => {
      if (route.request().method() === 'DELETE') {
        deleted = route.request().url()
        return route.fulfill({ status: 204 })
      }
      return route.fallback()
    })
    await page.goto('/favorites')
    await page.waitForSelector('[data-mock-id="favorites-row-0"]')
    await page.getByRole('button', { name: 'Retirer des favoris' }).first().click()
    await expect.poll(() => deleted).toContain('/favorites/document/')
  })
})
