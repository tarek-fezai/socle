// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Vue de comparaison vs Diff.dc.html @ 1440x900.
 * Exceptions D1-D10 (voir commentaire historique / PR).
 * D10: hauteur controles fige a 58px des deux cotes.
 */
import { test, expect } from '@playwright/test'
import { DIFF_DESKTOP_IDS, annotateDiffMockup, collectMetrics, compareMetrics } from './structural-compare.mjs'
import { HIST_DOC_ID } from './history-fixtures.mjs'
import { MOCK, diffRatio, maskGlyphs, prep, settleFonts, writeStructural } from './history-helpers.mjs'

test.use({ timezoneId: 'Europe/Paris' })

const COMPARE_URL = `/docs/${HIST_DOC_ID}/history/compare?from=11&to=12`
const CLIP = { x: 268, y: 0, width: 1172, height: 900 }

async function normalizeMockup(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]')
    if (!root) return
    root.style.width = '1172px'
    root.style.marginLeft = '268px'
  })
  await page.addStyleTag({
    content: '.side.new.row-add .ln { background: #B7E4C7; color: #14532D; } .side.new.row-add .cell { background: #DFF3E6; }',
  })
}

async function pinControlsHeight(page) {
  await page.addStyleTag({
    content: `[data-mock-id="diff-controls"], body div[style*="1172px"] > div:nth-child(2), body div[style*="1440px"] > div:nth-child(2) {
      height: 58px !important;
      box-sizing: border-box !important;
      padding-top: 0 !important;
      padding-bottom: 0 !important;
    }`,
  })
}

async function openCompare(page, opts) {
  await prep(page, opts)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(COMPARE_URL)
  await page.waitForSelector('[data-mock-id="diff-hunk-2"]')
  await settleFonts(page)
}

async function expandCollapsed(page) {
  for (const btn of await page.getByTestId('diff-collapsed').all()) await btn.click()
}

test.describe('Comparaison - parite visuelle', () => {
  test('desktop Comparaison vs Diff.dc.html @ 1440x900', async ({ page }, testInfo) => {
    await openCompare(page)
    await expandCollapsed(page)
    await pinControlsHeight(page)
    await page.evaluate(() => {
      document.body.style.margin = '0'
      document.documentElement.style.overflow = 'hidden'
    })
    await maskGlyphs(page)
    await page.waitForTimeout(150)
    const appShot = await page.screenshot({ fullPage: false, clip: CLIP })
    await page.goto(`${MOCK}/Diff.dc.html`)
    await normalizeMockup(page)
    await pinControlsHeight(page)
    await settleFonts(page)
    await maskGlyphs(page)
    await page.waitForTimeout(150)
    const mockShot = await page.screenshot({ fullPage: false, clip: CLIP })
    await testInfo.attach('maquette-diff', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-diff', { body: appShot, contentType: 'image/png' })
    const ratio = diffRatio(mockShot, appShot, 'diff-desktop')
    expect(ratio, `diff desktop diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })
})

test.describe('Comparaison - structure', () => {
  const exceptions = {
    'diff-topbar': { skip: ['text'], reason: 'Conteneur' },
    'diff-controls': { skip: ['text'], reason: 'Conteneur' },
    'diff-box': { skip: ['text'], reason: 'Conteneur' },
    'diff-toggle': { skip: ['text'], reason: 'Conteneur' },
    'diff-sel-from': { skip: ['text'], reason: 'D2 select natif' },
    'diff-sel-to': { skip: ['text'], reason: 'D2 select natif' },
  }
  test('desktop Comparaison structural match', async ({ page }, testInfo) => {
    await prep(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`${MOCK}/Diff.dc.html`)
    await normalizeMockup(page)
    await settleFonts(page)
    await annotateDiffMockup(page)
    const mockMap = await collectMetrics(page, DIFF_DESKTOP_IDS)
    await page.goto(COMPARE_URL)
    await page.waitForSelector('[data-mock-id="diff-hunk-2"]')
    await expandCollapsed(page)
    await settleFonts(page)
    const appMap = await collectMetrics(page, DIFF_DESKTOP_IDS)
    const results = compareMetrics(mockMap, appMap, DIFF_DESKTOP_IDS, { pageExceptions: exceptions })
    writeStructural('structural-diff-desktop.json', results)
    await testInfo.attach('structural-diff-desktop.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })
})

test.describe('Comparaison - comportement', () => {
  test('selecteurs Depuis / Vers, compteurs et CTA Restaurer v11', async ({ page }) => {
    await openCompare(page)
    await expect(page.getByTestId('diff-select-from')).toHaveValue('11')
    await expect(page.getByTestId('diff-select-to')).toHaveValue('12')
    await expect(page.getByTestId('diff-added')).toHaveText('+18')
    await expect(page.getByTestId('diff-removed')).toHaveText('−4')
    await expect(page.getByTestId('compare-restore')).toHaveText('Restaurer v11')
  })

  test('N lignes inchangees est repliee puis depliable', async ({ page }) => {
    await openCompare(page)
    const collapsed = page.getByTestId('diff-collapsed')
    await expect(collapsed).toHaveCount(1)
    await expect(collapsed).toContainText(/2 lignes inchang/)
    await expect(page.getByText(/de revue formalis/)).toHaveCount(0)
    await collapsed.click()
    await expect(page.getByTestId('diff-collapsed')).toHaveCount(0)
    await expect(page.getByText(/de revue formalis/)).toHaveCount(2)
  })

  test('bascule Cote a cote / Unifie en etat React', async ({ page }) => {
    await openCompare(page)
    await expect(page.getByTestId('diff-view')).toHaveAttribute('data-mode', 'side')
    await page.getByTestId('diff-mode-unified').click()
    await expect(page.getByTestId('diff-view')).toHaveAttribute('data-mode', 'unified')
    await page.getByTestId('diff-mode-side').click()
    await expect(page.getByTestId('diff-view')).toHaveAttribute('data-mode', 'side')
    const stored = await page.evaluate(() =>
      Object.keys(localStorage).filter((k) => /diff|compare|mode/i.test(k)),
    )
    expect(stored).toEqual([])
  })

  test('lecteur sans droit edition : pas de CTA Restaurer', async ({ page }) => {
    await openCompare(page, { editor: false })
    await expect(page.getByTestId('diff-view')).toBeVisible()
    await expect(page.getByTestId('compare-restore')).toHaveCount(0)
  })
})
