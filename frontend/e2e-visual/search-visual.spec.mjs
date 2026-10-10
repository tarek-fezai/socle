// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * Palette de recherche (Cmd/Ctrl+K, composant CommandPalette) vs Search.dc.html @ 1440×900
 * (desktop uniquement). La page plein écran `/search` n'est pas couverte ici.
 *
 * La maquette dessine une fausse page grisée derrière la palette : seule la boîte de dialogue est
 * comparée, section par section (saisie, filtres, groupe « Documents », 3 résultats, aperçu,
 * pied de palette).
 *
 * Pixel honnête (texte inclus, ≤ 1 % par section) ; seul exclu : `mask` Playwright nommé.
 * Annotation maquette : data-* uniquement.
 *
 * Mesure avant polish : `VISUAL_BASELINE=1 npx playwright test e2e-visual/search-visual.spec.mjs`
 * (journalise tous les % / tailles, écrit test-results/search-before.json, n'échoue pas).
 */
import { test, expect } from '@playwright/test'
import { collectMetrics, compareMetrics } from './structural-compare.mjs'
import { ME_TAREK, NOTIFICATIONS_SEED, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { SEARCH_SEED, SPACES_SEED } from './five-screens-fixtures.mjs'
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
    id: 'search-filters',
    page: 'search',
    reason:
      'Rangée de filtres (Domaine · Statut · Tag · + Auteur · + Tag) : pas d’API de facettes de recherche — placeholder « Bientôt » de même taille',
    backlog: 'SEARCH-FACETS',
    mockMask: 'search-filters',
    appPlaceholderSelector: '[data-visual-mask="search-filters"]',
  },
  {
    id: 'search-preview',
    page: 'search',
    reason:
      'Aperçu rapide (statut, titre serif, extrait surligné, « Ouvrir le document → ») : pas d’endpoint d’extrait enrichi pour le résultat actif',
    backlog: 'SEARCH-QUICK-PREVIEW',
    mockMask: 'search-preview',
    appPlaceholderSelector: '[data-visual-mask="search-preview"]',
    appImplementedProbe: 'text=/Ouvrir le document/',
  },
  {
    id: 'search-result-meta',
    page: 'search',
    reason:
      'Portion méta sans donnée API : contexte « mentionne « … » » et pastille tag (ex. IAM) — SearchHit n’expose ni match context ni tags ; l’app compare espace + statut (SearchHit.status)',
    backlog: 'SEARCH-HIT-TAGS',
    mockMask: 'search-result-meta',
    appPlaceholderSelector: '[data-visual-mask="search-result-meta"]',
  },
  {
    id: 'search-result-star',
    page: 'search',
    reason: 'Étoile favori dans la palette : état favori absent de SearchHit',
    backlog: 'SEARCH-HIT-FAVORITE',
    mockMask: 'search-result-star',
    appPlaceholderSelector: '[data-visual-mask="search-result-star"]',
  },
  {
    id: 'search-groups-extra',
    page: 'search',
    reason:
      'Groupes « Espaces · 1 » et « Personnes · 1 » : la recherche ne renvoie que des documents (l’app propose un lien « Tous les espaces »)',
    backlog: 'SEARCH-SPACES-PEOPLE',
    mockMask: 'search-groups-extra',
    appImplementedProbe: 'text=/^Personnes · /',
  },
]

/**
 * Hauteur du corps de palette : la maquette empile en plus les groupes « Espaces » et « Personnes »
 * (NOT_IMPLEMENTED search-groups-extra) → colonne d'aperçu plus haute de 44 px que dans l'app.
 */
const SIZE_EXCEPTIONS = {
  'search-preview': {
    dw: 0,
    dh: -44,
    reason: 'corps de palette plus court : groupes Espaces / Personnes NOT_IMPLEMENTED',
  },
}

const ROWS = [0, 1, 2]

const PIXEL_SECTIONS = [
  { id: 'search-input', name: 'input', masks: [] },
  { id: 'search-filters', name: 'filters', masks: ['search-filters'] },
  { id: 'search-results-head', name: 'results-head', masks: [] },
  ...ROWS.map((i) => ({
    id: `search-result-${i}`,
    name: `result-${i}`,
    masks: ['search-result-meta', 'search-result-star'],
  })),
  { id: 'search-preview', name: 'preview', masks: ['search-preview'] },
  { id: 'search-footer', name: 'footer', masks: [] },
]

const STRUCTURAL_IDS = [
  'search-palette',
  'search-input',
  'search-result-0',
  'search-result-0-title',
  'search-result-1-title',
  'search-footer',
]

async function mockApis(page) {
  await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED, spaces: SPACES_SEED })
  await page.route('**/api/v1/search**', (route) => json(route, SEARCH_SEED))
}

/** Annotation Search.dc.html — data-* uniquement. */
async function annotateSearchMockup(page) {
  await page.evaluate(() => {
    const palette = [...document.querySelectorAll('div')].find((d) => d.style.width === '760px')
    palette?.setAttribute('data-mock-id', 'search-palette')
    const [input, filters, body, footer] = [...(palette?.children ?? [])]
    input?.setAttribute('data-mock-id', 'search-input')
    filters?.setAttribute('data-mock-id', 'search-filters')
    filters?.setAttribute('data-visual-mask', 'search-filters')
    footer?.setAttribute('data-mock-id', 'search-footer')
    const [results, preview] = [...(body?.children ?? [])]
    results?.setAttribute('data-mock-id', 'search-results')
    preview?.setAttribute('data-mock-id', 'search-preview')
    preview?.setAttribute('data-visual-mask', 'search-preview')
    const kids = [...(results?.children ?? [])]
    // [head, a×3, head(Espaces), a, head(Personnes), a]
    kids[0]?.setAttribute('data-mock-id', 'search-results-head')
    kids.slice(1, 4).forEach((a, i) => {
      a.setAttribute('data-mock-id', `search-result-${i}`)
      const text = a.children[1]
      text?.children[0]?.setAttribute('data-mock-id', `search-result-${i}-title`)
      const meta = text?.children[1]
      if (meta) {
        // Masquer uniquement tag + « · mentionne … » ; laisser espace et statut visibles.
        const tag = [...meta.children].find((c) => c.tagName === 'SPAN')
        tag?.setAttribute('data-visual-mask', 'search-result-meta')
        for (const node of [...meta.childNodes]) {
          if (node.nodeType !== Node.TEXT_NODE) continue
          const full = node.textContent ?? ''
          const idx = full.indexOf(' · mentionne')
          if (idx < 0) continue
          node.textContent = full.slice(0, idx)
          const wrap = document.createElement('span')
          wrap.setAttribute('data-visual-mask', 'search-result-meta')
          wrap.textContent = full.slice(idx)
          meta.insertBefore(wrap, tag ?? null)
        }
      }
      a.children[2]?.setAttribute('data-visual-mask', 'search-result-star')
    })
    kids.slice(4).forEach((k) => k.setAttribute('data-visual-mask', 'search-groups-extra'))
  })
}

/** Ouvre la palette dans l'app (raccourci clavier du shell) et saisit la requête. */
async function openPaletteInApp(page) {
  await page.goto('/spaces')
  await page.waitForSelector('[data-mock-id="spaces-grid"]')
  await page.keyboard.press('Control+K')
  const input = page.getByTestId('command-palette-input')
  await input.waitFor({ state: 'visible' })
  await input.fill('provisioning')
  await page.waitForSelector('[data-mock-id="search-result-2"]')
  await page.mouse.move(1, 1)
}

/**
 * Anti-crénelage : dans l'app, la palette est un calque fixe composité au-dessus du shell
 * (conteneurs défilants) — Chrome y désactive le rendu LCD du texte, alors que la maquette
 * (aucun conteneur défilant) le garde. Même rendu des deux côtés : texte en niveaux de gris
 * (--disable-lcd-text), uniquement pour ce fichier. Le texte reste comparé pixel à pixel.
 */
test.use({ launchOptions: { args: ['--disable-lcd-text'] } })

test.describe('search (palette) visual', () => {
  test.use({ timezoneId: 'Europe/Paris' })

  test('desktop Palette — sections pixel ≤ 1 % (texte inclus)', async ({ page }, testInfo) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Search.dc.html`)
    await annotateSearchMockup(page)
    await settleFonts(page)
    await reportNotImplemented(page, testInfo, NOT_IMPLEMENTED, 'search')

    const mockSec = {}
    for (const s of PIXEL_SECTIONS) {
      mockSec[s.name] = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `search-sec-${s.name}-mock`,
      })
    }

    await openPaletteInApp(page)
    await settleFonts(page)
    await assertNotImplementedGuards(page, NOT_IMPLEMENTED, 'search')

    const compare = compareSectionShots(SIZE_EXCEPTIONS)
    const results = []
    for (const s of PIXEL_SECTIONS) {
      const app = await shotSection(page, `[data-mock-id="${s.id}"]`, {
        maskNames: s.masks,
        label: `search-sec-${s.name}-app`,
      })
      results.push(compare(`search-${s.name}`, mockSec[s.name].shot, app.shot, testInfo))
    }
    finishPixelResults('search', results)
  })

  test('Palette structural — boîte, saisie, titres de résultats, pied', async ({ page }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK_ORIGIN}/Search.dc.html`)
    await annotateSearchMockup(page)
    await settleFonts(page)
    const mockMetrics = await collectMetrics(page, STRUCTURAL_IDS)

    await openPaletteInApp(page)
    await settleFonts(page)
    const appMetrics = await collectMetrics(page, STRUCTURAL_IDS)

    const pageExceptions = {
      'search-palette': {
        skip: ['text', 'box.height'],
        reason:
          'Contenu : filtres / aperçu / groupes Espaces-Personnes NOT_IMPLEMENTED → texte et hauteur de la boîte non comparables (largeur et position le sont).',
      },
      'search-input': {
        skip: ['text'],
        reason: 'Maquette : texte + faux curseur ; app : <input> (valeur non incluse dans textContent).',
      },
      'search-footer': {
        skip: ['text', 'box.y'],
        reason:
          'box.y : le pied suit le corps de palette, plus court dans l’app (groupes Espaces/Personnes NOT_IMPLEMENTED). text : l’app n’insère pas d’espace entre les raccourcis (textContent concaténé) — texte vérifié par pixel.',
      },
      'search-result-0': {
        skip: ['text', 'lineHeight'],
        reason:
          'Texte : mention/tag méta NOT_IMPLEMENTED (espace + statut comparés en pixel). lineHeight : collectMetrics fixe 1 sur un <button> (app) et 1.2 sur un <a> (maquette) — titres comparés séparément.',
      },
    }
    const results = compareMetrics(mockMetrics, appMetrics, STRUCTURAL_IDS, { pageExceptions })
    expectNoStructuralDiffs(results)
  })

  test('Échap ferme la palette', async ({ page }) => {
    await injectOidcSession(page, VISUAL_NOW)
    await mockApis(page)
    await openPaletteInApp(page)
    await page.keyboard.press('Escape')
    await expect(page.getByTestId('command-palette')).toHaveCount(0)
  })
})
