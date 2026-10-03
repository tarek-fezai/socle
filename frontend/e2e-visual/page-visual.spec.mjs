// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Document READ page (/docs/:id) vs Main.dc.html (desktop) and MobilePage.dc.html (mobile).
 * Pattern: dashboard-visual.spec.mjs (pixel diff + data-mock-id structural compare).
 *
 * Documented exceptions (see also page-fixtures.mjs):
 *  - Onglet « Index » : bouton désactivé (title="bientôt") au lieu d'un lien Index.dc.html.
 *  - « Champs personnalisés » : omis tant qu'aucune définition n'existe (masqué dans la maquette).
 *  - Figures draw.io / captures d'écran : « Bloc non pris en charge dans cette version » (lot 3).
 *  - Propriétaire : « Équipe {nom de l'espace} » (maquette : « Équipe Identité »).
 *  - Bouton d'attestation : police IBM Plex (app) vs Arial UA (maquette).
 *  - Sélecteur de langue retiré de la maquette (hors V1).
 *  - Shell (sidebar) : comparaison limitée à la colonne principale (x ≥ 268) en desktop.
 *  - Glyphes masqués dans les diffs pixel ; le texte est couvert par le test structurel.
 */
import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { PNG } from 'pngjs'
import pixelmatch from 'pixelmatch'
import { fileURLToPath } from 'node:url'
import {
  PAGE_BELOW_FOLD_IDS,
  PAGE_DESKTOP_IDS,
  PAGE_MOBILE_IDS,
  annotateMobilePageMockup,
  annotatePageMockup,
  assertFontsLoaded,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import {
  CAMPAIGN_ID,
  ME_TAREK,
  PAGE_ATTESTATION,
  PAGE_COMMENTS,
  PAGE_DOC_ID,
  PAGE_FEEDBACK_EDITOR,
  PAGE_LINKS,
  SPACE_IDENTITE,
  TREE_IDENTITE,
  VISUAL_NOW,
  pageDocument,
  pageDocumentViewer,
} from './page-fixtures.mjs'
import { FAVORITES_SEED, NOTIFICATIONS_SEED, SPACE_INFRA, TREE_INFRA } from './dashboard-fixtures.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const outDir = path.join(__dirname, 'test-results')

test.use({ timezoneId: 'Europe/Paris' })

const AUTH_CONFIG = {
  authority: 'http://127.0.0.1:9/realms/socle',
  clientId: 'socle-frontend',
  scopes: ['openid', 'profile', 'email'],
  organizationName: 'Organisation Démo',
  displayName: 'Organisation Démo',
  supportContact: 'identite@example.com',
  passkeyAcrValues: 'phr',
  idpDisplayName: 'Demo IdP',
}

const json = (route, body, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

async function injectOidcSession(page) {
  await page.addInitScript(
    ({ authority, clientId, now }) => {
      Date.now = () => now
      const key = `oidc.user:${authority}:${clientId}`
      const user = {
        id_token: 'visual.id',
        session_state: 'visual',
        access_token: 'visual-access-token',
        refresh_token: 'visual-refresh',
        token_type: 'Bearer',
        scope: 'openid profile email',
        profile: { sub: 'tarek-visual', name: 'Tarek Fezai', preferred_username: 'tarek', given_name: 'Tarek' },
        expires_at: Math.floor(now / 1000) + 3600,
      }
      sessionStorage.setItem(key, JSON.stringify(user))
      localStorage.setItem(
        'socle.shell.expandedSpaces',
        JSON.stringify(['s0000001-0000-4000-8000-000000000001', 's0000001-0000-4000-8000-000000000002']),
      )
    },
    { authority: AUTH_CONFIG.authority, clientId: AUTH_CONFIG.clientId, now: VISUAL_NOW },
  )
}

/**
 * @param {import('@playwright/test').Page} page
 * @param {{ variant?: 'desktop'|'mobile', attestation?: boolean, editor?: boolean, related?: boolean }} [opts]
 */
async function mockPageApis(page, { variant = 'desktop', attestation = true, editor = true, related = true } = {}) {
  await page.route('**/api/v1/public/auth-config', (route) => json(route, AUTH_CONFIG))
  await page.route('**/api/v1/me', (route) => json(route, ME_TAREK))
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/resolved`, (route) =>
    json(route, editor ? pageDocument(variant) : pageDocumentViewer(variant)),
  )
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/view`, (route) => route.fulfill({ status: 204 }))
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/comments**`, (route) => json(route, PAGE_COMMENTS))
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/feedback`, (route) =>
    json(route, editor ? PAGE_FEEDBACK_EDITOR : { myVote: null }),
  )
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/attestations/active`, (route) =>
    attestation ? json(route, PAGE_ATTESTATION) : route.fulfill({ status: 204 }),
  )
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/attestations/${CAMPAIGN_ID}/acknowledge`, (route) =>
    json(route, { ...PAGE_ATTESTATION, acknowledged: true, ackCount: PAGE_ATTESTATION.ackCount + 1 }),
  )
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/links`, (route) =>
    json(route, related ? PAGE_LINKS : { outgoing: [], incoming: [] }),
  )
  await page.route('**/api/v1/favorites/**', (route) => json(route, { favorited: true }))
  await page.route('**/api/v1/favorites', (route) => json(route, FAVORITES_SEED))
  await page.route('**/api/v1/notifications**', (route) => json(route, NOTIFICATIONS_SEED))
  await page.route('**/api/v1/spaces', (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    return json(route, [SPACE_IDENTITE, SPACE_INFRA])
  })
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}`, (route) => json(route, SPACE_IDENTITE))
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/owners`, (route) =>
    json(route, {
      spaceId: SPACE_IDENTITE.id,
      owners: [{ userId: ME_TAREK.id, email: ME_TAREK.email, displayName: ME_TAREK.displayName, responsible: true }],
    }),
  )
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/tree**`, (route) => json(route, TREE_IDENTITE))
  await page.route(`**/api/v1/spaces/${SPACE_INFRA.id}/tree**`, (route) => json(route, TREE_INFRA))
  await page.route('**/api/v1/search**', (route) => json(route, { query: '', results: [], total: 0 }))
}

async function prep(page, opts) {
  await injectOidcSession(page)
  await mockPageApis(page, opts)
}

function diffRatio(a, b, label) {
  const imgA = PNG.sync.read(a)
  const imgB = PNG.sync.read(b)
  if (imgA.width !== imgB.width || imgA.height !== imgB.height) {
    throw new Error(`${label}: size mismatch ${imgA.width}x${imgA.height} vs ${imgB.width}x${imgB.height}`)
  }
  const diff = new PNG({ width: imgA.width, height: imgA.height })
  const mismatched = pixelmatch(imgA.data, imgB.data, diff.data, imgA.width, imgA.height, {
    threshold: 0.2,
    includeAA: false,
  })
  fs.mkdirSync(outDir, { recursive: true })
  fs.writeFileSync(path.join(outDir, `${label}-maquette.png`), a)
  fs.writeFileSync(path.join(outDir, `${label}-app.png`), b)
  fs.writeFileSync(path.join(outDir, `${label}-diff.png`), PNG.sync.write(diff))
  return mismatched / (imgA.width * imgA.height)
}

async function settleFonts(page) {
  await page.evaluate(() => document.fonts.ready)
}

/** Glyphes transparents : le pixel-diff compare la géométrie / les aplats, pas le rendu des polices. */
async function maskGlyphs(page) {
  await page.addStyleTag({
    content: `* { color: transparent !important; -webkit-text-fill-color: transparent !important; text-shadow: none !important; caret-color: transparent !important; }
      svg text { fill: transparent !important; }
      ::selection { background: transparent; }`,
  })
}

const MOCK = 'http://127.0.0.1:4174'

test.describe('document page visual parity', () => {
  test('desktop Page vs Main.dc.html @ 1440×900 (colonne principale)', async ({ page }, testInfo) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await page.waitForSelector('[data-mock-id="doc-title"]')
    await page.waitForSelector('[data-mock-id="doc-attestation"]')
    await page.waitForSelector('[data-mock-id="rail-reliability"]')
    await page.waitForSelector('[data-mock-id="doc-related-link"]')
    await page.evaluate(() => {
      document.body.style.margin = '0'
      document.documentElement.style.overflow = 'hidden'
    })
    await settleFonts(page)
    await maskGlyphs(page)
    await page.waitForTimeout(150)
    const clip = { x: 268, y: 0, width: 1172, height: 900 }
    const appShot = await page.screenshot({ fullPage: false, clip })

    await page.goto(`${MOCK}/Main.dc.html`)
    await page.evaluate(() => {
      // Exception : « Champs personnalisés » absent de l'app sans définition de champs.
      const label = Array.from(document.querySelectorAll('div')).find(
        (d) => d.children.length === 0 && (d.textContent || '').trim() === 'Champs personnalisés',
      )
      const block = label?.parentElement?.parentElement
      if (block) block.style.visibility = 'hidden'
    })
    await settleFonts(page)
    await maskGlyphs(page)
    await page.waitForTimeout(150)
    const mockShot = await page.screenshot({ fullPage: false, clip })

    await testInfo.attach('maquette-page', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-page', { body: appShot, contentType: 'image/png' })
    const ratio = diffRatio(mockShot, appShot, 'page-desktop')
    expect(ratio, `desktop page diff ${ratio}`).toBeLessThanOrEqual(0.03)
  })

  test('mobile Page vs MobilePage.dc.html @ 390×844', async ({ page }, testInfo) => {
    await prep(page, { variant: 'mobile', attestation: false, related: false })
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto(`${MOCK}/MobilePage.dc.html`)
    await page.evaluate(() => {
      const root = Array.from(document.querySelectorAll('div')).find((d) =>
        (d.getAttribute('style') || '').includes('390px'),
      )
      if (root) {
        root.style.position = 'fixed'
        root.style.left = '0'
        root.style.top = '0'
      }
    })
    await settleFonts(page)
    await maskGlyphs(page)
    await page.waitForTimeout(100)
    const clip = { x: 0, y: 0, width: 390, height: 844 }
    const mockShot = await page.screenshot({ fullPage: false, clip })

    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await page.waitForSelector('[data-mock-id="doc-title"]')
    await page.waitForSelector('[data-mock-id="doc-mobile-tabs"]')
    await page.evaluate(() => {
      document.body.style.margin = '0'
    })
    await settleFonts(page)
    await maskGlyphs(page)
    await page.waitForTimeout(100)
    const appShot = await page.screenshot({ fullPage: false, clip })

    const ratio = diffRatio(mockShot, appShot, 'page-mobile')
    expect(ratio, `mobile page diff ${ratio}`).toBeLessThanOrEqual(0.04)
  })
})

test.describe('document page structural', () => {
  const desktopExceptions = {
    'doc-topbar': { skip: ['text'], reason: 'Boutons d’action (aide, favori, export, Publier) : libellés dans les enfants' },
    'doc-breadcrumb': { skip: ['text'], reason: 'Séparateurs → et liens : textContent agrégé' },
    'doc-switch-index': {
      skip: ['box', 'lineHeight'],
      reason: 'Onglet Index désactivé (title="bientôt") : bouton au lieu du lien Index.dc.html',
    },
    'doc-tabs': { skip: ['text'], reason: 'Libellés dans les enfants ; Modifier/Accès conditionnels aux droits' },
    'doc-attestation': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés' },
    'doc-attestation-title': {
      skip: ['box'],
      reason: 'Largeur du bloc texte = flex-grow : dépend de la largeur du bouton (police)',
    },
    'doc-attestation-text': { skip: ['box'], reason: 'Idem' },
    'doc-attestation-cta': {
      skip: ['box', 'fontFamily'],
      reason: 'Bouton de la maquette sans font-family (UA Arial) ; l’app applique IBM Plex Sans',
    },
    'rail-owner-name': {
      skip: ['text', 'box'],
      reason: 'Propriétaire = « Équipe {nom de l’espace} » (maquette : « Équipe Identité »)',
    },
  }

  const belowFoldExceptions = {
    // Box.y : figures lot 3 absentes côté app (exception lot 3).
    'doc-related-title': { skip: ['box'], reason: 'Figures lot 3 absentes → décalage vertical' },
    'doc-related-link': { skip: ['box'], reason: 'Figures lot 3 absentes → décalage vertical' },
    'doc-feedback-label': {
      skip: ['box', 'text'],
      reason: 'Libellé desktop/mobile partagé ; y dépend des figures lot 3',
    },
  }
  test('desktop Page structural match', async ({ page }, testInfo) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK}/Main.dc.html`)
    await settleFonts(page)
    await annotatePageMockup(page)
    const mockMap = await collectMetrics(page, PAGE_DESKTOP_IDS)

    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await page.waitForSelector('[data-mock-id="doc-title"]')
    await page.waitForSelector('[data-mock-id="doc-attestation"]')
    await page.waitForSelector('[data-mock-id="doc-related-link"]')
    await settleFonts(page)
    const appMap = await collectMetrics(page, PAGE_DESKTOP_IDS)

    const fonts = await assertFontsLoaded(page)
    expect(fonts.find((f) => f.family === 'Instrument Serif')?.loaded).toBe(true)
    expect(fonts.find((f) => f.family === 'IBM Plex Sans')?.loaded).toBe(true)

    const results = compareMetrics(mockMap, appMap, PAGE_DESKTOP_IDS, { pageExceptions: desktopExceptions })
    fs.mkdirSync(outDir, { recursive: true })
    fs.writeFileSync(path.join(outDir, 'structural-page-desktop.json'), JSON.stringify(results, null, 2))
    await testInfo.attach('structural-page-desktop.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })

  test('mobile Page structural match', async ({ page }) => {
    await prep(page, { variant: 'mobile', attestation: false, related: false })
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto(`${MOCK}/MobilePage.dc.html`)
    await settleFonts(page)
    await annotateMobilePageMockup(page)
    const mockMap = await collectMetrics(page, PAGE_MOBILE_IDS)

    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await page.waitForSelector('[data-mock-id="doc-title"]')
    await page.waitForSelector('[data-mock-id="doc-mobile-tabs"]')
    await settleFonts(page)
    const appMap = await collectMetrics(page, PAGE_MOBILE_IDS)

    // Aucune exception : textes, typographie et boîtes identiques à MobilePage.dc.html.
    const pageExceptions = {}
    const results = compareMetrics(mockMap, appMap, PAGE_MOBILE_IDS, { pageExceptions })
    fs.mkdirSync(outDir, { recursive: true })
    fs.writeFileSync(path.join(outDir, 'structural-page-mobile.json'), JSON.stringify(results, null, 2))
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })

  test('desktop below-fold : Documents liés + feedback (après défilement)', async ({ page }, testInfo) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK}/Main.dc.html`)
    await settleFonts(page)
    await annotatePageMockup(page)
    // Masquer figures draw.io / captures dans la maquette pour aligner la hauteur relative.
    await page.evaluate(() => {
      document.querySelectorAll('figure, .doc-unsupported').forEach((el) => {
        el.style.display = 'none'
      })
      // Also hide the draw.io SVG blocks and dashed screenshot placeholders in the maquette
      document.querySelectorAll('figure').forEach((el) => {
        el.style.display = 'none'
      })
    })
    for (const id of PAGE_BELOW_FOLD_IDS) {
      const el = page.locator(`[data-mock-id="${id}"]`).first()
      if (await el.count()) await el.scrollIntoViewIfNeeded()
    }
    const mockMap = await collectMetrics(page, PAGE_BELOW_FOLD_IDS)

    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await page.waitForSelector('[data-mock-id="doc-related-title"]')
    await page.waitForSelector('[data-mock-id="doc-feedback-label"]')
    await settleFonts(page)
    for (const id of PAGE_BELOW_FOLD_IDS) {
      await page.locator(`[data-mock-id="${id}"]`).first().scrollIntoViewIfNeeded()
    }
    const appMap = await collectMetrics(page, PAGE_BELOW_FOLD_IDS)

    const results = compareMetrics(mockMap, appMap, PAGE_BELOW_FOLD_IDS, {
      pageExceptions: belowFoldExceptions,
    })
    fs.mkdirSync(outDir, { recursive: true })
    fs.writeFileSync(path.join(outDir, 'structural-page-below-fold.json'), JSON.stringify(results, null, 2))
    await testInfo.attach('structural-page-below-fold.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })
})

test.describe('document page behaviour', () => {
  test('print : masque shell, barre, onglets et rail ; garde titre et contenu', async ({ page }) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await page.waitForSelector('[data-mock-id="doc-title"]')
    await page.waitForSelector('[data-mock-id="doc-attestation"]')
    await page.emulateMedia({ media: 'print' })

    const display = (sel) =>
      page.evaluate((s) => {
        const el = document.querySelector(s)
        return el ? getComputedStyle(el).display : 'absent'
      }, sel)

    expect(await display('.shell-sidebar')).toBe('none')
    expect(await display('.doc-topbar')).toBe('none')
    expect(await display('.doc-tabs')).toBe('none')
    expect(await display('.doc-rail')).toBe('none')
    expect(await display('.doc-feedback')).toBe('none')
    expect(await display('.doc-title')).not.toBe('none')
    expect(await display('.doc-body')).not.toBe('none')
    await expect(page.locator('.doc-title')).toBeVisible()
    await expect(page.locator('.doc-body')).toBeVisible()

    await page.emulateMedia({ media: 'screen' })
    expect(await display('.doc-topbar')).not.toBe('none')
    expect(await display('.doc-rail')).not.toBe('none')
  })

  test('/docs/:id/view redirige vers /docs/:id', async ({ page }) => {
    await prep(page)
    await page.goto(`/docs/${PAGE_DOC_ID}/view`)
    await page.waitForSelector('[data-mock-id="doc-title"]')
    expect(new URL(page.url()).pathname).toBe(`/docs/${PAGE_DOC_ID}`)
  })

  test('lecteur sans droit d’édition : pas de Modifier / Publier', async ({ page }) => {
    await prep(page, { editor: false })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await page.waitForSelector('[data-mock-id="doc-title"]')
    await expect(page.getByTestId('tab-edit')).toHaveCount(0)
    await expect(page.getByTestId('doc-publish')).toHaveCount(0)
    await expect(page.getByTestId('doc-feedback')).toBeVisible()
  })

  test('blocs non supportés : libellé neutre, pas de JSON brut', async ({ page }) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${PAGE_DOC_ID}`)
    await page.waitForSelector('[data-mock-id="doc-title"]')
    const blocks = page.getByTestId('unsupported-block')
    await expect(blocks.first()).toContainText('Bloc non pris en charge dans cette version')
    const body = await page.locator('.doc-body').innerText()
    expect(body).not.toContain('"type"')
    expect(body).not.toContain('drawio')
  })
})
