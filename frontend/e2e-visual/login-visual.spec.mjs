// SPDX-License-Identifier: AGPL-3.0-or-later
import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { PNG } from 'pngjs'
import pixelmatch from 'pixelmatch'
import { fileURLToPath } from 'node:url'
import {
  LOGIN_DESKTOP_IDS,
  LOGIN_ERROR_IDS,
  LOGIN_MOBILE_IDS,
  annotateLoginMockup,
  annotateLoginErrorMockup,
  annotateMobileMockup,
  assertFontsLoaded,
  collectMetrics,
  compareMetrics,
} from './structural-compare.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const outDir = path.join(__dirname, 'test-results')

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

async function mockAuthConfig(page, overrides = {}) {
  await page.route('**/api/v1/public/auth-config', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...AUTH_CONFIG, ...overrides }),
    })
  })
}

function diffRatio(a, b, label) {
  const imgA = PNG.sync.read(a)
  const imgB = PNG.sync.read(b)
  if (imgA.width !== imgB.width || imgA.height !== imgB.height) {
    throw new Error(
      `${label}: size mismatch ${imgA.width}x${imgA.height} vs ${imgB.width}x${imgB.height}`,
    )
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
  const total = imgA.width * imgA.height
  return mismatched / total
}

/** Wait for local OFL fonts before screenshots (avoids FOUT on cold CI). */
async function settleFonts(page) {
  await page.evaluate(() => document.fonts.ready)
}

/** Hide glyph rasters that FreeType hinting shifts across OS; keep chrome/layout. */
async function maskGlyphSensitive(page, mode) {
  await page.evaluate((m) => {
    const hide = (el) => {
      if (!el) return
      el.style.color = 'transparent'
      el.style.webkitTextFillColor = 'transparent'
      el.style.textShadow = 'none'
    }
    if (m === 'app-desktop') {
      hide(document.querySelector('.login-brand-name'))
      hide(document.querySelector('.login-title'))
      hide(document.querySelector('.login-subtitle'))
      hide(document.querySelector('.login-label'))
      document.querySelectorAll('.login-cta, .login-sso-label').forEach(hide)
      document.querySelectorAll('.login-quote').forEach(hide)
      document.querySelectorAll('.login-visual svg text, .login-panel svg text, svg text').forEach(hide)
    }
    if (m === 'mock-desktop') {
      const formCol = document.querySelector('body div[style*="560px"]') || document.querySelector('body div[style*="1440px"] > div')
      if (formCol) {
        formCol.querySelectorAll('h1, p, label, span, a').forEach((el) => {
          // keep SVG icons inside SSO links visible: only hide text nodes' color on text tags
          if (el.tagName === 'A') {
            el.querySelectorAll('span').forEach(hide)
            el.style.color = 'transparent'
          } else {
            hide(el)
          }
        })
        // brand name is a div
        const brand = Array.from(formCol.querySelectorAll('div')).find(
          (d) => (d.textContent || '').trim() === 'Socle' && d.children.length === 0,
        )
        hide(brand)
      }
      const quote = Array.from(document.querySelectorAll('p')).find((p) =>
        (p.textContent || '').includes('documentation qui suit'),
      )
      hide(quote)
      document.querySelectorAll('svg text').forEach(hide)
    }
    if (m === 'app-mobile') {
      hide(document.querySelector('.login-mobile-name'))
      hide(document.querySelector('.login-mobile-org'))
      hide(document.querySelector('.login-mobile-sso-label'))
    }
    if (m === 'mock-mobile') {
      const root = Array.from(document.querySelectorAll('div')).find((d) =>
        (d.getAttribute('style') || '').includes('390px'),
      )
      if (root) {
        root.querySelectorAll('.serif, span').forEach(hide)
        const org = Array.from(root.querySelectorAll('div')).find(
          (d) => (d.textContent || '').trim() === 'Organisation Démo',
        )
        hide(org)
      }
    }
  }, mode)
}

test.describe('login visual parity', () => {
  test('no Google Fonts network on /login', async ({ page }) => {
    const blocked = []
    page.on('request', (req) => {
      const u = req.url()
      if (u.includes('fonts.googleapis.com') || u.includes('fonts.gstatic.com')) {
        blocked.push(u)
      }
    })
    await mockAuthConfig(page)
    await page.goto('/login')
    await page.waitForSelector('text=Se connecter')
    expect(blocked, `Google Fonts requests: ${blocked.join(', ')}`).toEqual([])
  })

  test('desktop Login vs maquette @ 1440×900', async ({ page }, testInfo) => {
    await mockAuthConfig(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('/login')
    await page.waitForSelector('#login-email')
    await page.fill('#login-email', 'tarek.fezai@example.com')
    await page.evaluate(() => {
      document.documentElement.style.overflow = 'hidden'
      document.body.style.margin = '0'
      document.body.style.overflow = 'hidden'
      document.querySelector('.login-mobile')?.setAttribute('style', 'display:none !important')
      const shell = document.querySelector('.login-shell')
      if (shell) {
        shell.style.width = '1440px'
        shell.style.height = '900px'
        shell.style.minHeight = '900px'
      }
      const desk = document.querySelector('.login-desktop')
      if (desk) desk.style.display = 'flex'
      const email = document.querySelector('.login-email-wrap')
      if (email) email.style.visibility = 'hidden'
      // Texte informatif AA (#75757C) ≠ maquette #B0B0B5 — exclus de la comparaison
      document.querySelectorAll('.login-footer, .login-divider').forEach((el) => {
        el.style.visibility = 'hidden'
      })
    })
    await settleFonts(page)
    await maskGlyphSensitive(page, 'app-desktop')
    await page.waitForTimeout(100)
    const appShot = await page.screenshot({ fullPage: false, clip: { x: 0, y: 0, width: 1440, height: 900 } })

    await page.goto('http://127.0.0.1:4174/Login.dc.html')
    await page.evaluate(() => {
      const root = document.querySelector('body div[style*="1440px"]') || document.querySelector('body > div, x-dc > div')
      if (root) {
        root.style.width = '1440px'
        root.style.height = '900px'
      }
      const boxes = Array.from(document.querySelectorAll('div')).filter(
        (d) =>
          (d.getAttribute('style') || '').includes('border: 1px solid #ECECEE') &&
          (d.getAttribute('style') || '').includes('border-radius: 9px'),
      )
      const emailBox = boxes[0]
      if (emailBox) emailBox.style.visibility = 'hidden'
      // Masquer le pied et le séparateur « ou » (couleur informative)
      const footer = Array.from(document.querySelectorAll('p')).find((p) =>
        (p.textContent || '').includes('Réservé aux comptes'),
      )
      if (footer) footer.style.visibility = 'hidden'
      const ouRow = Array.from(document.querySelectorAll('div')).find((d) => {
        const t = d.textContent || ''
        return t.trim() === 'ou' || (t.includes('ou') && d.children.length === 3)
      })
      if (ouRow && (ouRow.textContent || '').trim().length < 10) ouRow.style.visibility = 'hidden'
    })
    await settleFonts(page)
    await maskGlyphSensitive(page, 'mock-desktop')
    await page.waitForTimeout(100)
    const mockShot = await page.screenshot({ fullPage: false, clip: { x: 0, y: 0, width: 1440, height: 900 } })

    await testInfo.attach('maquette-login', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-login', { body: appShot, contentType: 'image/png' })

    const ratio = diffRatio(mockShot, appShot, 'login-desktop')
    await testInfo.attach('diff-ratio', {
      body: Buffer.from(`diffPixelRatio=${ratio}`),
      contentType: 'text/plain',
    })
    expect(ratio, `desktop diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })

  test('LoginError vs maquette (causes masked) @ 1440×900', async ({ page }, testInfo) => {
    await mockAuthConfig(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    await page.goto('/login/erreur?reason=not_provisioned')
    await page.waitForSelector('text=Connexion refusée')
    await page.evaluate(() => {
      document.documentElement.style.overflow = 'hidden'
      document.body.style.margin = '0'
      document.querySelector('.login-mobile')?.setAttribute('style', 'display:none !important')
      const shell = document.querySelector('.login-shell')
      if (shell) {
        shell.style.width = '1440px'
        shell.style.height = '900px'
        shell.style.minHeight = '900px'
      }
      const desk = document.querySelector('.login-desktop')
      if (desk) desk.style.display = 'flex'
      const strong = document.querySelector('.login-subtitle--error strong')
      if (strong) strong.textContent = 'tarek.fezai@example.com'
      const sub = document.querySelector('.login-subtitle--error')
      if (sub) {
        sub.style.height = '72px'
        sub.style.visibility = 'hidden'
      }
      const causes = document.querySelector('[data-testid="login-error-causes"]')
      if (causes) {
        causes.style.height = '140px'
        causes.style.visibility = 'hidden'
      }
      const ghost = document.querySelector('.login-ghost')
      if (ghost) {
        ghost.style.height = '44px'
        ghost.style.visibility = 'hidden'
      }
      document.querySelectorAll('.login-footer').forEach((el) => {
        el.style.visibility = 'hidden'
      })
      const hide = (el) => {
        if (!el) return
        el.style.color = 'transparent'
        el.style.webkitTextFillColor = 'transparent'
        el.style.textShadow = 'none'
      }
      hide(document.querySelector('.login-brand-name'))
      hide(document.querySelector('.login-title'))
      hide(document.querySelector('.login-cta'))
      hide(document.querySelector('.login-quote'))
      document.querySelectorAll('svg text').forEach((el) => {
        el.style.fill = 'transparent'
      })
    })
    await settleFonts(page)
    await page.waitForTimeout(100)
    const appShot = await page.screenshot({ fullPage: false, clip: { x: 0, y: 0, width: 1440, height: 900 } })

    await page.goto('http://127.0.0.1:4174/LoginError.dc.html')
    await page.evaluate(() => {
      const paragraphs = Array.from(document.querySelectorAll('p'))
      const sub = paragraphs.find((p) => (p.textContent || '').includes('authentification SSO'))
      if (sub) {
        sub.style.height = '72px'
        sub.style.visibility = 'hidden'
      }
      const box = document.querySelector('div[style*="border: 1px solid #ECECEE"]')
      if (box) {
        box.style.height = '140px'
        box.style.visibility = 'hidden'
      }
      const ghost = Array.from(document.querySelectorAll('a')).find((a) =>
        (a.textContent || '').includes('provisioning'),
      )
      if (ghost) {
        ghost.style.height = '44px'
        ghost.style.display = 'block'
        ghost.style.visibility = 'hidden'
      }
      const footer = paragraphs.find((p) => (p.textContent || '').includes('Un problème persiste'))
      if (footer) footer.style.visibility = 'hidden'
      // Mask glyph-sensitive title/quote for OS-stable chrome comparison
      const title = document.querySelector('h1')
      if (title) {
        title.style.color = 'transparent'
        title.style.webkitTextFillColor = 'transparent'
      }
      const brand = Array.from(document.querySelectorAll('div')).find(
        (d) => (d.textContent || '').trim() === 'Socle' && d.children.length === 0,
      )
      if (brand) {
        brand.style.color = 'transparent'
        brand.style.webkitTextFillColor = 'transparent'
      }
      const quote = paragraphs.find((p) => (p.textContent || '').includes("L'accès suit"))
      if (quote) {
        quote.style.color = 'transparent'
        quote.style.webkitTextFillColor = 'transparent'
      }
      document.querySelectorAll('svg text').forEach((el) => {
        el.style.fill = 'transparent'
      })
      const cta = Array.from(document.querySelectorAll('a')).find((a) =>
        (a.textContent || '').includes('Réessayer'),
      )
      if (cta) {
        cta.style.color = 'transparent'
        cta.style.webkitTextFillColor = 'transparent'
      }
    })
    await settleFonts(page)
    await page.waitForTimeout(100)
    const mockShot = await page.screenshot({ fullPage: false, clip: { x: 0, y: 0, width: 1440, height: 900 } })

    await testInfo.attach('maquette-login-error', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-login-error', { body: appShot, contentType: 'image/png' })

    const ratio = diffRatio(mockShot, appShot, 'login-error')
    expect(ratio, `error diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })

  test('MobileLogin vs /login @ 390×844', async ({ page }, testInfo) => {
    await mockAuthConfig(page)
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto('http://127.0.0.1:4174/MobileLogin.dc.html')
    await page.waitForSelector('text=Socle')
    await page.evaluate(() => {
      const root = Array.from(document.querySelectorAll('div')).find((d) =>
        (d.getAttribute('style') || '').includes('390px'),
      )
      if (root) {
        root.style.position = 'fixed'
        root.style.left = '0'
        root.style.top = '0'
        root.style.width = '390px'
        root.style.height = '844px'
        root.style.boxSizing = 'border-box'
      }
      const note = Array.from(document.querySelectorAll('p')).find((p) =>
        (p.textContent || '').includes('Réservé aux comptes'),
      )
      if (note) note.style.visibility = 'hidden'
      const help = Array.from(document.querySelectorAll('div')).find(
        (d) => (d.textContent || '').includes("Besoin d'aide") && d.children.length <= 1,
      )
      if (help) help.style.visibility = 'hidden'
    })
    await settleFonts(page)
    await maskGlyphSensitive(page, 'mock-mobile')
    await page.waitForTimeout(100)
    const mockShot = await page.locator('div[style*="390px"]').first().screenshot()

    await page.goto('/login')
    await page.waitForSelector('.login-mobile')
    await page.evaluate(() => {
      document.body.style.margin = '0'
      document.querySelector('.login-desktop')?.setAttribute('style', 'display:none !important')
      const mob = document.querySelector('.login-mobile')
      if (mob) {
        mob.style.display = 'flex'
        mob.style.position = 'fixed'
        mob.style.left = '0'
        mob.style.top = '0'
        mob.style.width = '390px'
        mob.style.height = '844px'
        mob.style.minHeight = '844px'
        mob.style.boxSizing = 'border-box'
        mob.style.padding = '60px 28px 32px'
      }
      document.querySelectorAll('.login-mobile-note, .login-mobile-help').forEach((el) => {
        el.style.visibility = 'hidden'
      })
    })
    await settleFonts(page)
    await maskGlyphSensitive(page, 'app-mobile')
    await page.waitForTimeout(100)
    const appShot = await page.locator('.login-mobile').screenshot()

    await testInfo.attach('maquette-mobile-login', { body: mockShot, contentType: 'image/png' })
    await testInfo.attach('app-mobile-login', { body: appShot, contentType: 'image/png' })

    const ratio = diffRatio(mockShot, appShot, 'login-mobile')
    expect(ratio, `mobile diff ${ratio}`).toBeLessThanOrEqual(0.01)
  })
})

test.describe('login structural typography', () => {
  test('embedded OFL fonts loaded on /login', async ({ page }) => {
    await mockAuthConfig(page)
    await page.goto('/login')
    await page.waitForSelector('[data-mock-id="title"]')
    const fonts = await assertFontsLoaded(page)
    for (const f of fonts.filter((x) => x.family !== 'IBM Plex Mono')) {
      expect(f.loaded, `${f.family} not loaded: ${JSON.stringify(f)}`).toBe(true)
    }
  })

  test('desktop Login structural match', async ({ page }, testInfo) => {
    await mockAuthConfig(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    async function prepDesktopShell(isMock) {
      await page.evaluate((mock) => {
        document.body.style.margin = '0'
        if (mock) {
          const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
          if (root) {
            root.style.width = '1440px'
            root.style.height = '900px'
          }
          const formCol = document.querySelector('div[style*="560px"]')
          if (formCol) {
            formCol.style.justifyContent = 'flex-start'
            formCol.style.paddingTop = '96px'
          }
          const email = document.querySelector('[data-mock-id="email-field"]')
          if (email) {
            email.style.height = '44px'
            email.style.boxSizing = 'border-box'
            email.style.visibility = 'hidden'
          }
        } else {
          document.querySelector('.login-mobile')?.setAttribute('style', 'display:none !important')
          const shell = document.querySelector('.login-shell')
          if (shell) {
            shell.style.width = '1440px'
            shell.style.height = '900px'
            shell.style.minHeight = '900px'
          }
          const desk = document.querySelector('.login-desktop')
          if (desk) desk.style.display = 'flex'
          const formCol = document.querySelector('.login-form-col')
          if (formCol) {
            formCol.style.justifyContent = 'flex-start'
            formCol.style.paddingTop = '96px'
          }
          const email = document.querySelector('[data-mock-id="email-field"]')
          if (email) {
            email.style.height = '44px'
            email.style.boxSizing = 'border-box'
            email.style.visibility = 'hidden'
          }
        }
      }, isMock)
    }

    await page.goto('http://127.0.0.1:4174/Login.dc.html')
    await settleFonts(page)
    await annotateLoginMockup(page)
    await prepDesktopShell(true)
    const mockMap = await collectMetrics(page, LOGIN_DESKTOP_IDS)

    await page.goto('/login')
    await page.waitForSelector('[data-mock-id="title"]')
    await settleFonts(page)
    await prepDesktopShell(false)
    const appMap = await collectMetrics(page, LOGIN_DESKTOP_IDS)
    const fonts = await assertFontsLoaded(page)
    expect(fonts.find((f) => f.family === 'Instrument Serif')?.loaded).toBe(true)
    expect(fonts.find((f) => f.family === 'IBM Plex Sans')?.loaded).toBe(true)

    const results = compareMetrics(mockMap, appMap, LOGIN_DESKTOP_IDS)
    await testInfo.attach('structural-desktop.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    fs.mkdirSync(outDir, { recursive: true })
    fs.writeFileSync(path.join(outDir, 'structural-desktop.json'), JSON.stringify(results, null, 2))
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })

  test('LoginError structural match', async ({ page }, testInfo) => {
    await mockAuthConfig(page)
    await page.setViewportSize({ width: 1440, height: 900 })

    async function prepErrorShell(isMock) {
      await page.evaluate((mock) => {
        document.body.style.margin = '0'
        const formCol = mock
          ? document.querySelector('div[style*="560px"]')
          : document.querySelector('.login-form-col')
        if (formCol) {
          formCol.style.justifyContent = 'flex-start'
          formCol.style.paddingTop = '72px'
        }
        if (!mock) {
          document.querySelector('.login-mobile')?.setAttribute('style', 'display:none !important')
          const shell = document.querySelector('.login-shell')
          if (shell) {
            shell.style.width = '1440px'
            shell.style.height = '900px'
            shell.style.minHeight = '900px'
          }
          const strong = document.querySelector('.login-subtitle--error strong')
          if (strong) strong.textContent = 'tarek.fezai@example.com'
        } else {
          const root = document.querySelector('body div[style*="1440px"]')
          if (root) {
            root.style.width = '1440px'
            root.style.height = '900px'
          }
        }
        // Normalize dynamic blocks so justify/flow matches (exceptions keep text unchecked)
        const causes = document.querySelector('[data-mock-id="causes"]')
        if (causes) {
          causes.style.height = '140px'
          causes.style.overflow = 'hidden'
          causes.style.boxSizing = 'border-box'
        }
        const contact = document.querySelector('[data-mock-id="contact-support"]')
        if (contact) {
          contact.style.height = '44px'
          contact.style.boxSizing = 'border-box'
          contact.style.visibility = 'hidden'
        }
        const sub = document.querySelector('[data-mock-id="subtitle"]')
        if (sub) {
          sub.style.height = '72px'
          sub.style.overflow = 'hidden'
          sub.style.boxSizing = 'border-box'
        }
      }, isMock)
    }

    await page.goto('http://127.0.0.1:4174/LoginError.dc.html')
    await settleFonts(page)
    await annotateLoginErrorMockup(page)
    await prepErrorShell(true)
    const mockMap = await collectMetrics(page, LOGIN_ERROR_IDS)

    await page.goto('/login/erreur?reason=not_provisioned')
    await page.waitForSelector('[data-mock-id="title"]')
    await settleFonts(page)
    await prepErrorShell(false)
    const appMap = await collectMetrics(page, LOGIN_ERROR_IDS)

    const pageExceptions = {
      subtitle: {
        skip: ['text', 'color', 'box', 'lineHeight'],
        reason:
          'Sous-titre dynamique (e-mail du jeton + formulation produit) ≠ texte SCIM de la maquette',
      },
      footer: {
        skip: ['color'],
        reason: 'Texte informatif AA : app #75757C au lieu de maquette #B0B0B5',
      },
    }
    const results = compareMetrics(mockMap, appMap, LOGIN_ERROR_IDS, { pageExceptions })
    await testInfo.attach('structural-error.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    fs.mkdirSync(outDir, { recursive: true })
    fs.writeFileSync(path.join(outDir, 'structural-error.json'), JSON.stringify(results, null, 2))
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })

  test('MobileLogin structural match @ 390×844', async ({ page }, testInfo) => {
    await mockAuthConfig(page)
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto('http://127.0.0.1:4174/MobileLogin.dc.html')
    await page.evaluate(() => {
      const root = Array.from(document.querySelectorAll('div')).find((d) =>
        (d.getAttribute('style') || '').includes('390px'),
      )
      if (root) {
        root.style.position = 'fixed'
        root.style.left = '0'
        root.style.top = '0'
        root.style.width = '390px'
        root.style.height = '844px'
        root.style.boxSizing = 'border-box'
      }
    })
    await settleFonts(page)
    await annotateMobileMockup(page)
    const mockMap = await collectMetrics(page, LOGIN_MOBILE_IDS)

    await page.goto('/login')
    await page.waitForSelector('[data-mock-id="mobile-brand-name"]')
    await page.evaluate(() => {
      document.body.style.margin = '0'
      document.querySelector('.login-desktop')?.setAttribute('style', 'display:none !important')
      const mob = document.querySelector('.login-mobile')
      if (mob) {
        mob.style.display = 'flex'
        mob.style.position = 'fixed'
        mob.style.left = '0'
        mob.style.top = '0'
        mob.style.width = '390px'
        mob.style.height = '844px'
        mob.style.minHeight = '844px'
        mob.style.boxSizing = 'border-box'
        mob.style.padding = '60px 28px 32px'
      }
    })
    await settleFonts(page)
    const appMap = await collectMetrics(page, LOGIN_MOBILE_IDS)

    const results = compareMetrics(mockMap, appMap, LOGIN_MOBILE_IDS)
    await testInfo.attach('structural-mobile.json', {
      body: Buffer.from(JSON.stringify(results, null, 2)),
      contentType: 'application/json',
    })
    fs.mkdirSync(outDir, { recursive: true })
    fs.writeFileSync(path.join(outDir, 'structural-mobile.json'), JSON.stringify(results, null, 2))
    const failures = results.filter((r) => r.diffs.length)
    expect(failures, JSON.stringify(failures, null, 2)).toEqual([])
  })
})

test.describe('login auth behaviour', () => {
  test('passkey button hidden when passkeyAcrValues empty', async ({ page }) => {
    await mockAuthConfig(page, { passkeyAcrValues: '' })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/login')
    await expect(page.getByRole('button', { name: /clé de sécurité/i })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /SSO de l'organisation/i })).toBeVisible()
  })

  test('passkey button present when configured', async ({ page }) => {
    await mockAuthConfig(page, { passkeyAcrValues: 'phr' })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/login')
    await expect(page.getByRole('button', { name: /clé de sécurité/i })).toBeVisible()
  })
})
