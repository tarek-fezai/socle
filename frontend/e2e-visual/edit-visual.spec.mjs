// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Document EDIT screen (/docs/:id/edit) vs Edit.dc.html (desktop 1440×900).
 * Pattern: page-visual.spec.mjs (pixel diff ≤ 1 % + data-mock-id structural compare).
 *
 * ── Périmètre ─────────────────────────────────────────────────────────────────────────────
 *  - Colonne principale uniquement (x ≥ 268) : la barre latérale est celle du shell, déjà couverte
 *    par les specs Dashboard / Page.
 *  - Glyphes masqués (transparents) dans les diffs pixel ; le texte est couvert par le test structurel.
 *  - Pas de maquette mobile pour l'écran Modifier : aucun test mobile (barre compacte documentée dans
 *    document-edit.css).
 *
 * ── Fixture applicative (edit-fixtures.mjs) ──────────────────────────────────────────────
 *  - Le seul éditeur est l'utilisateur courant, qui détient le verrou exclusif ; aucun bandeau de
 *    co-édition (la maquette en montre un : co-édition hors V1, voir « Normalisation »).
 *  - Corps limité aux nœuds du StarterKit (+ zones à compléter, + un bloc `image` et un bloc
 *    `attachment` en fin de corps — la maquette n'en montre pas dans le corps, seulement l'entrée
 *    « Fichier joint » du menu Insérer, masquée ouverte) ; paragraphes de remplissage sous la
 *    ligne de flottaison pour afficher exactement « 1 240 mots · 6 min de lecture ».
 *  - Seuil « paragraphe long » du serveur calé entre le chapeau et le paragraphe « en cours d'édition ».
 *  - Menu « Insérer » fermé (la maquette le montre ouvert) ; le titre du menu « Titre 2 » est obtenu
 *    en plaçant le curseur dans le premier H2.
 *
 * ── Normalisation de la maquette (edit-normalize.mjs) : ce que l'app ne peut pas / ne doit pas montrer ──
 *  N1. Bandeau « Yanis M. modifie également ce document » masqué (co-édition hors V1 ; l'app n'affiche
 *      qu'un bandeau de verrou exclusif réel quand un AUTRE utilisateur détient le verrou).
 *  N2. Avatars de présence : « YM » retiré, « CD » devient l'avatar de l'utilisateur courant « TF ».
 *  N3. Texte d'aide « Un bloc réduit reste dans le document… » et panneau « Insérer » ouvert masqués
 *      (fonction « Réduire les blocs enrichis » non disponible ; menu ouvert = état transitoire).
 *  N4. Poignées de bloc (+ / glisser / réduire) masquées : glisser-déposer et blocs réduits hors V1.
 *  N5. Bulle de sélection flottante, surlignage de sélection, étiquette « Yanis modifie ici » et liseré
 *      de curseur distant retirés (états transitoires de co-édition).
 *  N6. Contour pointillé + curseur factice du paragraphe « en cours d'édition » retirés (focus transitoire).
 *  N7. Puce Jira IAM-482 remplacée par du texte brut (mentions hors V1). La puce date est aussi
 *      aplatie en texte dans la maquette pour coller à la fixture éditeur (compteur de mots +
 *      absence d'icône calendrier côté TipTap) — le nœud `date` est couvert par les tests unitaires
 *      / lecture, pas par le pixel-diff Edit.
 *  N8. Bloc de code : en-tête « bash · script de revue des accès / Copier » retiré (le bloc de code
 *      TipTap n'a pas de langue ni de bouton Copier) ; opacité « déplacement en cours » retirée.
 *  N9. Indicateur de dépôt, aperçu de lien réduit, encadré « Note » et liste de tâches masqués :
 *      blocs hors StarterKit (callout, tâches, liens enrichis) — hors V1 de l'éditeur.
 *  N10. Carte « Terme non conforme » (règle de glossaire) masquée : emplacement réservé, règle
 *      glossaire pas encore livrée côté assistant.
 *
 * ── Exceptions visuelles assumées (app ≠ maquette, hors normalisation) ─────────────────────
 *  E1. Barre d'outils : fonctions encore manquantes (souligné, couleur/surlignage, liste de tâches,
 *      retraits, alignements, lien, draw.io, @mention, « Réduire les blocs enrichis ») rendues
 *      désactivées (`aria-disabled`, info-bulle « Bientôt disponible », opacité réduite à 55 %)
 *      au lieu d'être actives. Tableau, image, et (via Insérer) date / bouton / vidéo sont actifs.
 *      Exception structurelle `edit-toolbar` (skip text) : libellés agrégés icônes SVG + texte —
 *      contrôles comparés par le pixel-diff.
 *  E2. Menu « Insérer » : actifs = Bloc de code, Image, Vidéo, Tableau, Date & heure, Bouton,
 *      Fichier joint ; les autres entrées restent désactivées (« Bientôt disponible »).
 *  E3. Propriétaire : « Équipe {nom de l'espace} » (maquette : « Équipe Identité »).
 *  E4. Carte « Paragraphe long » : message calculé (« Ce paragraphe dépasse N mots (M) — … ») au lieu
 *      du texte statique « Le paragraphe édité dépasse 60 mots — … » ; bouton « Aller au paragraphe »
 *      ajouté dans l'en-tête de la carte (absent de la maquette).
 *  E5. « Gérer → » et « + Champ » désactivés (« Bientôt disponible ») : la gestion des définitions de
 *      champs n'est pas dans le périmètre de l'écran.
 *  E6. Champs personnalisés : le premier champ de la maquette est en IBM Plex Mono ; l'app utilise
 *      IBM Plex Sans pour tous les champs (type `texte` générique).
 *  E7. Les contrôles de la barre haute sont des <button> (la maquette utilise des <a>) ; mêmes boîtes.
 *  E8. Titre : <textarea> auto-dimensionné (la maquette : <h1 contenteditable>) ; mêmes métriques.
 *  E9. « + Tag » est un bouton ouvrant l'autocomplétion (la maquette : lien vers TagsAdmin).
 *  E10. Fonctionnalités de la maquette absentes : poignées de bloc, glisser-déposer, blocs réduits,
 *       puces inline, callout, liste de tâches, bulle de formatage (voir N4–N9) — listées comme écarts
 *       restants dans le compte rendu.
 *  E11. Barre haute : bouton « Enregistrer la version » (ChangeSummaryPopover, résumé facultatif) absent
 *       de Edit.dc.html — décale présence / Aperçu (~153 px) et agrandit le déclencheur « Envoyer en
 *       révision » (popover). Boîtes des contrôles de la barre haute non comparées ; le pixel-diff
 *       (≤ 1 %, glyphes masqués) couvre le rendu.
 *  Les espaces de mise en forme du source de la maquette (textContent des onglets / de la carte
 *  « Lien cassé ») sont ignorés dans la comparaison structurelle (libellés identiques).
 */
import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { PNG } from 'pngjs'
import pixelmatch from 'pixelmatch'
import { fileURLToPath } from 'node:url'
import {
  EDIT_DESKTOP_IDS,
  annotateEditMockup,
  assertFontsLoaded,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'
import { normalizeEditMockup } from './edit-normalize.mjs'
import {
  EDIT_APPLICABLE_WORKFLOW,
  EDIT_CUSTOM_FIELDS,
  EDIT_DOC_ID,
  EDIT_WRITING_HINTS,
  ME_TAREK,
  PAGE_COMMENTS,
  SPACE_IDENTITE,
  TREE_IDENTITE,
  VISUAL_NOW,
  editDocument,
  editLockMine,
} from './edit-fixtures.mjs'
import { ATTACHMENT_FILE_ID, ATTACHMENT_IMAGE_ID, mockAttachmentRoutes } from './page-fixtures.mjs'
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
 * @param {{ lockHolder?: string|null, putCalls?: Array<Record<string, unknown>> }} [opts]
 */
async function mockEditApis(page, { lockHolder = null, putCalls = [], draftPutCalls = [], tags } = {}) {
  const doc = tags ? { ...editDocument(), tags } : editDocument()
  // Brouillon autosave : aucun brouillon à l'ouverture (404) ; PUT = écho + updatedAt.
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}/draft`, (route) => {
    const req = route.request()
    if (req.method() === 'PUT') {
      const payload = req.postDataJSON()
      draftPutCalls.push(payload)
      return json(route, { ...payload, updatedAt: new Date(VISUAL_NOW + 60_000).toISOString() })
    }
    if (req.method() === 'DELETE') return route.fulfill({ status: 204 })
    return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
  })
  await page.route('**/api/v1/public/auth-config', (route) => json(route, AUTH_CONFIG))
  await page.route('**/api/v1/me', (route) => json(route, ME_TAREK))
  await mockAttachmentRoutes(page)
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}`, (route) => {
    const req = route.request()
    if (req.method() === 'PUT') {
      const payload = req.postDataJSON()
      putCalls.push(payload)
      return json(route, {
        ...doc,
        title: payload.title,
        body: payload.body,
        currentVersionNo: (doc.currentVersionNo ?? 1) + putCalls.length,
        updatedAt: new Date(VISUAL_NOW + 60_000).toISOString(),
      })
    }
    return json(route, doc)
  })
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}/edit-lock`, (route) => {
    if (route.request().method() === 'DELETE') return route.fulfill({ status: 204 })
    if (lockHolder) {
      return json(route, {
        active: true,
        holderUserId: 'u-other',
        holderDisplayName: lockHolder,
        acquiredAt: new Date(VISUAL_NOW - 9 * 60_000).toISOString(),
        heartbeatAt: new Date(VISUAL_NOW).toISOString(),
        heldByCurrentUser: false,
        ttlSeconds: 45,
        heartbeatSeconds: 15,
      })
    }
    return json(route, editLockMine())
  })
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}/writing-assistant`, (route) => json(route, EDIT_WRITING_HINTS))
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}/custom-fields`, (route) => json(route, EDIT_CUSTOM_FIELDS))
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}/comments**`, (route) => json(route, PAGE_COMMENTS))
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}/approvals/current`, (route) => route.fulfill({ status: 204 }))
  await page.route(`**/api/v1/documents/${EDIT_DOC_ID}/approvals/applicable-workflow`, (route) =>
    json(route, EDIT_APPLICABLE_WORKFLOW),
  )
  await page.route('**/api/v1/tags**', (route) => json(route, []))
  await page.route('**/api/v1/favorites/**', (route) => json(route, { favorited: false }))
  await page.route('**/api/v1/favorites', (route) => json(route, FAVORITES_SEED))
  await page.route('**/api/v1/notifications**', (route) => json(route, NOTIFICATIONS_SEED))
  await page.route('**/api/v1/spaces', (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    return json(route, [SPACE_IDENTITE, SPACE_INFRA])
  })
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}`, (route) => json(route, SPACE_IDENTITE))
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/tree**`, (route) => json(route, TREE_IDENTITE))
  await page.route(`**/api/v1/spaces/${SPACE_INFRA.id}/tree**`, (route) => json(route, TREE_INFRA))
  await page.route('**/api/v1/search**', (route) => json(route, { query: '', results: [], total: 0 }))
}

async function prep(page, opts) {
  await injectOidcSession(page)
  await mockEditApis(page, opts)
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
      input::placeholder, textarea::placeholder { color: transparent !important; }
      ::selection { background: transparent; }`,
  })
}

/** Attend que l'écran soit complet (titre, assistant, métadonnées, compteur de commentaires). */
async function waitEditReady(page) {
  await page.waitForSelector('[data-mock-id="edit-title"]')
  await page.waitForSelector('[data-mock-id="edit-card-link"]')
  await page.waitForSelector('[data-mock-id="edit-meta-custom-field-2"]')
  await page.waitForSelector('[data-mock-id="edit-meta-tag"]')
  await page.waitForFunction(() => /3/.test(document.querySelector('[data-mock-id="edit-tabs"]')?.textContent ?? ''))
  // « Titre 2 » dans le menu de style : curseur dans le premier H2
  await page.click('.ProseMirror h2')
  await page.evaluate(() => {
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
  })
  await page.mouse.move(0, 0)
}

const MOCK = 'http://127.0.0.1:4174'

test.describe('document edit visual parity', () => {
  test('desktop Edit vs Edit.dc.html @ 1440×900 (colonne principale)', async ({ page }, testInfo) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`/docs/${EDIT_DOC_ID}/edit`)
    await waitEditReady(page)
    await page.evaluate(() => {
      document.body.style.margin = '0'
      document.documentElement.style.overflow = 'hidden'
    })
    await settleFonts(page)
    await maskGlyphs(page)
    await page.waitForTimeout(150)
    const clip = { x: 268, y: 0, width: 1172, height: 900 }
    const appShot = await page.screenshot({ fullPage: false, clip })

    await page.goto(`${MOCK}/Edit.dc.html`)
    await annotateEditMockup(page)
    await normalizeEditMockup(page)
    await settleFonts(page)
    await maskGlyphs(page)
    await page.waitForTimeout(150)
    const mockShot = await page.screenshot({ fullPage: false, clip })

    await testInfo.attach('maquette-edit', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-edit', { body: appShot, contentType: 'image/png' })
    const ratio = diffRatio(mockShot, appShot, 'edit-desktop')
    console.log(`edit-desktop pixel diff ratio = ${(ratio * 100).toFixed(3)} %`)
    expect(ratio, `desktop edit diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })
})

test.describe('document edit structural', () => {
  const desktopExceptions = {
    'edit-toolbar': {
      skip: ['text'],
      reason:
        'E1 : libellés agrégés (icônes SVG + texte) — contrôles comparés par le pixel-diff ; outils encore « bientôt » hors tableau/image',
    },
    'edit-tabs': {
      skip: ['text'],
      reason: 'Espaces de mise en forme du source de la maquette entre onglets (textContent) ; libellés identiques',
    },
    'edit-card-link': {
      skip: ['text'],
      reason: 'Idem : retours à la ligne du source entre titre et message ; libellés identiques',
    },
    'edit-card-long-text': {
      skip: ['text'],
      reason: 'E4 : message calculé (« Ce paragraphe dépasse N mots (M) ») au lieu du texte statique',
    },
    'edit-card-long': {
      skip: ['text'],
      reason: 'E4 : bouton « Aller au paragraphe » ajouté dans l’en-tête ; message calculé',
    },
    'edit-meta-owner': { skip: ['text'], reason: 'E3 : « Équipe {nom de l’espace} » (maquette : « Équipe Identité »)' },
    'edit-assistant': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés (N10 : carte glossaire masquée)' },
    'edit-meta': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés ; E3/E5' },
    'edit-topbar': { skip: ['text'], reason: 'Conteneur — textes comparés sur les enfants annotés' },
    'edit-meta-custom-field-1': {
      skip: ['fontFamily'],
      reason: 'E6 : champ en IBM Plex Mono dans la maquette ; IBM Plex Sans dans l’app',
    },
    'edit-meta-custom-manage': { skip: ['color'], reason: 'E5 : « Gérer → » désactivé (« Bientôt disponible »)' },
    'edit-breadcrumb-current': {
      skip: ['box'],
      reason: 'E11 : fil d’Ariane compressé par le bouton « Enregistrer la version » (absent de la maquette)',
    },
    'edit-save-status': {
      skip: ['box'],
      reason: 'E11 : statut décalé par « Enregistrer la version » (absent de Edit.dc.html)',
    },
    'edit-word-count': {
      skip: ['box'],
      reason: 'E11 : compteur décalé par « Enregistrer la version » (absent de Edit.dc.html)',
    },
    'edit-presence': {
      skip: ['box'],
      reason: 'E11 : présence décalée (~153 px) par « Enregistrer la version »',
    },
    'edit-preview': {
      skip: ['box'],
      reason: 'E11 : Aperçu décalé par « Enregistrer la version »',
    },
    'edit-send-review': {
      skip: ['box'],
      reason:
        'E11 : déclencheur ChangeSummaryPopover (hauteur/largeur) vs lien plat « Envoyer en révision » de la maquette',
    },
  }

  test('desktop Edit structural match', async ({ page }, testInfo) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto(`${MOCK}/Edit.dc.html`)
    await settleFonts(page)
    await annotateEditMockup(page)
    await normalizeEditMockup(page)
    const mockMap = await collectMetrics(page, EDIT_DESKTOP_IDS)

    await page.goto(`/docs/${EDIT_DOC_ID}/edit`)
    await waitEditReady(page)
    await settleFonts(page)
    const appMap = await collectMetrics(page, EDIT_DESKTOP_IDS)

    const fonts = await assertFontsLoaded(page)
    expect(fonts.find((f) => f.family === 'Instrument Serif')?.loaded).toBe(true)
    expect(fonts.find((f) => f.family === 'IBM Plex Sans')?.loaded).toBe(true)

    const results = compareMetrics(mockMap, appMap, EDIT_DESKTOP_IDS, { pageExceptions: desktopExceptions })
    fs.mkdirSync(outDir, { recursive: true })
    fs.writeFileSync(path.join(outDir, 'structural-edit-desktop.json'), JSON.stringify(results, null, 2))
    await testInfo.attach('structural-edit-desktop.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })
})

test.describe('document edit behaviour', () => {
  test('autosave : une frappe déclenche un seul PUT brouillon après ~1 s (aucune version)', async ({ page }) => {
    const putCalls = []
    const draftPutCalls = []
    await prep(page, { putCalls, draftPutCalls })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${EDIT_DOC_ID}/edit`)
    await page.waitForSelector('[data-mock-id="edit-title"]')
    await expect(page.getByTestId('edit-save-status')).toHaveText('Brouillon enregistré à 14:22')
    await expect(page.getByTestId('edit-word-count')).toContainText('1 240 mots')
    // L'ouverture seule (acquisition du verrou, normalisation TipTap) n'enregistre rien.
    await page.waitForTimeout(1500)
    expect(putCalls).toHaveLength(0)
    expect(draftPutCalls).toHaveLength(0)

    await page.click('.ProseMirror p')
    await page.keyboard.press('Control+Home')
    await page.keyboard.type('ajout ')
    await expect(page.getByTestId('edit-save-status')).toHaveText('Enregistrement…')
    await expect.poll(() => draftPutCalls.length, { timeout: 5000 }).toBe(1)
    await expect(page.getByTestId('edit-save-status')).toContainText('Brouillon enregistré à')
    expect(JSON.stringify(draftPutCalls[0].body)).toContain('ajout')
    expect(draftPutCalls[0].baseVersionNo).toBe(12)
    // L'autosave n'appelle jamais updateDocument : aucune version créée.
    expect(putCalls).toHaveLength(0)
    await expect(page.getByTestId('edit-word-count')).toContainText('1 241 mots')

    // Ctrl+S : version explicite (un seul PUT du document).
    await page.keyboard.press('Control+s')
    await expect.poll(() => putCalls.length, { timeout: 5000 }).toBe(1)
    expect(putCalls[0].expectedVersionNo).toBe(12)
    expect(JSON.stringify(putCalls[0].body)).toContain('ajout')
    await expect(page.getByTestId('edit-version-msg')).toBeVisible()
  })

  test('verrou détenu par un autre : bandeau, avatar du détenteur, lecture seule', async ({ page }) => {
    await prep(page, { lockHolder: 'Camille Durand' })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${EDIT_DOC_ID}/edit`)
    const banner = page.getByTestId('edit-lock-banner')
    await expect(banner).toContainText('Camille Durand')
    await expect(banner).toContainText('9 min')
    await expect(page.getByTestId('edit-avatar-holder')).toHaveText('CD')
    await expect(page.getByTestId('edit-avatar-self')).toHaveCount(0)
    await expect(page.getByTestId('edit-title')).toHaveJSProperty('readOnly', true)
    await expect(page.locator('.ProseMirror')).toHaveAttribute('contenteditable', 'false')
    await expect(page.getByTestId('edit-send-review')).toBeDisabled()
  })

  test('fonctions manquantes de la barre d’outils : aria-disabled + « Bientôt disponible »', async ({ page }) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${EDIT_DOC_ID}/edit`)
    await page.waitForSelector('[data-mock-id="edit-toolbar"]')
    for (const label of ['Souligné', 'Insérer un diagramme draw.io', 'Mentionner une personne']) {
      const btn = page.getByRole('button', { name: label })
      await expect(btn).toHaveAttribute('aria-disabled', 'true')
      await expect(btn).toHaveAttribute('title', 'Bientôt disponible')
    }
    // Tableau + pièces jointes branchés : plus de « Bientôt disponible ».
    const table = page.getByRole('button', { name: 'Insérer un tableau' })
    await expect(table).not.toHaveAttribute('aria-disabled', 'true')
    await expect(table).not.toHaveAttribute('data-soon', 'true')
    const image = page.getByRole('button', { name: 'Insérer une image' })
    await expect(image).not.toHaveAttribute('aria-disabled', 'true')
    await expect(image).not.toHaveAttribute('data-soon', 'true')
    await expect(page.getByRole('button', { name: 'Gras' })).not.toHaveAttribute('aria-disabled', 'true')
  })

  test('pièces jointes : bloc image + bloc fichier joint rendus dans l’éditeur', async ({ page }) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/docs/${EDIT_DOC_ID}/edit`)
    await page.waitForSelector('[data-mock-id="edit-toolbar"]')

    const image = page.locator('.ProseMirror').getByTestId('attachment-image')
    await expect(image).toHaveCount(1)
    await expect(image).toHaveAttribute('data-attachment-id', ATTACHMENT_IMAGE_ID)

    const file = page.locator('.ProseMirror').getByTestId('attachment-file')
    await expect(file).toHaveCount(1)
    await expect(file).toHaveAttribute('data-attachment-id', ATTACHMENT_FILE_ID)
    await expect(file.locator('.doc-attachment-name')).toHaveText('matrice-habilitations-2026.pdf')
    await expect(file.locator('.doc-attachment-meta')).toContainText('482 Ko')
  })
})
