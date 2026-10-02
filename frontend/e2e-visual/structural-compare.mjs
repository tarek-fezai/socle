// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * OS-independent structural / typography comparison (data-mock-id).
 */

/** Explicit exceptions: id → justification (skipped checks listed). */
export const EXCEPTIONS = {
  'divider-ou': {
    skip: ['color'],
    reason: 'Texte informatif AA : app #75757C au lieu de maquette #B0B0B5 (contraste 4,5:1)',
  },
  footer: {
    skip: ['color'],
    reason: 'Texte informatif AA : app #75757C au lieu de maquette #B0B0B5 (contraste 4,5:1)',
  },
  'mobile-note': {
    skip: ['color'],
    reason: 'Texte informatif AA : app #75757C au lieu de maquette #B0B0B5 (contraste 4,5:1)',
  },
  'email-field': {
    skip: ['text', 'fontFamily', 'fontSize', 'fontWeight', 'fontStyle', 'lineHeight', 'letterSpacing', 'color', 'box'],
    reason: 'Champ e-mail : input réel vs spans décoratifs de la maquette',
  },
  subtitle: {
    // only on error page — handled via page-specific skip list
    skip: [],
    reason: '',
  },
  causes: {
    skip: ['text', 'fontFamily', 'fontSize', 'fontWeight', 'fontStyle', 'lineHeight', 'letterSpacing', 'color', 'box'],
    reason: 'Causes d’erreur dynamiques (une vraie reason) ≠ liste SCIM/Okta de la maquette',
  },
  'contact-support': {
    skip: ['text', 'fontFamily', 'fontSize', 'fontWeight', 'fontStyle', 'lineHeight', 'letterSpacing', 'color', 'box'],
    reason: 'Bouton admin maquette remplacé par mailto Identité & accès',
  },
}

export const LOGIN_DESKTOP_IDS = [
  'brand-name',
  'title',
  'subtitle',
  'label-email',
  'email-field',
  'cta-continue',
  'divider-ou',
  'sso-org',
  'sso-passkey',
  'footer',
  'quote',
  'svg-socle',
]

export const LOGIN_ERROR_IDS = [
  'brand-name',
  'title',
  'subtitle',
  'causes',
  'cta-retry',
  'contact-support',
  'footer',
  'quote',
  'svg-socle',
  'svg-not-provisioned',
]

export const LOGIN_MOBILE_IDS = [
  'mobile-brand-name',
  'mobile-org',
  'mobile-sso',
  'mobile-note',
  'mobile-help',
]

export const DASHBOARD_DESKTOP_IDS = [
  'shell-logo',
  'shell-search-chip',
  'shell-nav-favorites',
  'shell-nav-spaces',
  'shell-nav-team',
  'shell-space-section',
  'shell-user',
  'shell-header-new-doc',
  'home-greeting',
  'home-subtitle',
  'home-kpi-published',
  'home-kpi-pending',
  'home-kpi-views',
  'home-kpi-reliability',
  'home-section-resume',
  'home-section-approvals',
  'home-section-activity',
]

export const DASHBOARD_MOBILE_IDS = [
  'mobile-topbar',
  'mobile-brand',
  'mobile-tab-home',
  'home-greeting',
  'home-subtitle',
  'home-kpi-published',
  'home-kpi-pending',
  'home-section-resume',
  'home-section-approvals',
]

export const MOBILE_MENU_IDS = [
  'mobile-menu',
  'mobile-menu-space',
  'mobile-menu-user',
]

/** Annotate Dashboard.dc.html with data-mock-id for structural compare. */
export async function annotateDashboardMockup(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    if (!root) return
    const sidebar = root.children[0]
    const main = root.children[1]
    if (sidebar) {
      sidebar.setAttribute('data-mock-id', 'shell-sidebar')
      const logoName = Array.from(sidebar.querySelectorAll('div')).find(
        (d) => (d.textContent || '').trim() === 'Socle' && d.children.length === 0,
      )
      if (logoName?.parentElement) logoName.parentElement.setAttribute('data-mock-id', 'shell-logo')
      const search = Array.from(sidebar.querySelectorAll('a')).find((a) =>
        (a.textContent || '').includes('Rechercher'),
      )
      if (search) search.setAttribute('data-mock-id', 'shell-search-chip')
      const fav = Array.from(sidebar.querySelectorAll('a')).find((a) =>
        (a.textContent || '').includes('Favoris'),
      )
      if (fav) fav.setAttribute('data-mock-id', 'shell-nav-favorites')
      const spaces = Array.from(sidebar.querySelectorAll('a')).find((a) =>
        (a.textContent || '').includes('Tous les espaces'),
      )
      if (spaces) spaces.setAttribute('data-mock-id', 'shell-nav-spaces')
      const team = Array.from(sidebar.querySelectorAll('a')).find((a) =>
        (a.textContent || '').includes('Membres'),
      )
      if (team) team.setAttribute('data-mock-id', 'shell-nav-team')
      const section = Array.from(sidebar.querySelectorAll('div')).find((d) =>
        (d.textContent || '').includes('Identité'),
      )
      if (section && section.children.length === 0) section.setAttribute('data-mock-id', 'shell-space-section')
      const user = Array.from(sidebar.querySelectorAll('a')).find((a) =>
        (a.textContent || '').includes('Tarek'),
      )
      if (user) user.setAttribute('data-mock-id', 'shell-user')
    }
    if (main) {
      const header = main.children[0]
      const content = main.children[1]
      if (header) {
        header.setAttribute('data-mock-id', 'shell-header')
        const cta = Array.from(header.querySelectorAll('a')).find((a) =>
          (a.textContent || '').includes('Nouveau document'),
        )
        if (cta) cta.setAttribute('data-mock-id', 'shell-header-new-doc')
      }
      if (content) {
        content.setAttribute('data-mock-id', 'home-page')
        const h1 = content.querySelector('h1')
        if (h1) h1.setAttribute('data-mock-id', 'home-greeting')
        const sub = content.querySelector('p')
        if (sub) sub.setAttribute('data-mock-id', 'home-subtitle')
        const kpiRow = Array.from(content.querySelectorAll('div')).find((d) => {
          const t = d.textContent || ''
          return t.includes('Documents publiés') && t.includes('Fiabilité') && d.children.length === 4
        })
        if (kpiRow) {
          kpiRow.setAttribute('data-mock-id', 'home-kpis')
          const kids = [...kpiRow.children]
          if (kids[0]) kids[0].setAttribute('data-mock-id', 'home-kpi-published')
          if (kids[1]) kids[1].setAttribute('data-mock-id', 'home-kpi-pending')
          if (kids[2]) kids[2].setAttribute('data-mock-id', 'home-kpi-views')
          if (kids[3]) kids[3].setAttribute('data-mock-id', 'home-kpi-reliability')
        }
        const titles = Array.from(content.querySelectorAll('div')).filter(
          (d) => d.children.length === 0 && (d.getAttribute('style') || '').includes('font-weight: 700'),
        )
        for (const t of titles) {
          const text = (t.textContent || '').trim()
          if (text === 'Reprendre') t.setAttribute('data-mock-id', 'home-section-resume')
          if (text.startsWith('En attente')) t.setAttribute('data-mock-id', 'home-section-approvals')
          if (text.startsWith('Activité')) t.setAttribute('data-mock-id', 'home-section-activity')
        }
      }
    }
  })
}

export async function annotateMobileDashboardMockup(page) {
  await page.evaluate(() => {
    const root = Array.from(document.querySelectorAll('div')).find((d) =>
      (d.getAttribute('style') || '').includes('390px'),
    )
    if (!root) return
    const top = root.children[0]
    if (top) {
      top.setAttribute('data-mock-id', 'mobile-topbar')
      const brand = Array.from(top.querySelectorAll('div')).find(
        (d) => (d.textContent || '').trim() === 'Socle' && d.children.length === 0,
      )
      if (brand) brand.setAttribute('data-mock-id', 'mobile-brand')
    }
    const tabbar = root.children[root.children.length - 1]
    if (tabbar) {
      tabbar.setAttribute('data-mock-id', 'mobile-tabbar')
      const home = Array.from(tabbar.querySelectorAll('a')).find((a) =>
        (a.textContent || '').includes('Accueil'),
      )
      if (home) home.setAttribute('data-mock-id', 'mobile-tab-home')
    }
    const content = root.children[1]
    if (content) {
      const h1 = content.querySelector('h1')
      if (h1) h1.setAttribute('data-mock-id', 'home-greeting')
      const sub = content.querySelector('p')
      if (sub) sub.setAttribute('data-mock-id', 'home-subtitle')
      const kpiGrid = Array.from(content.querySelectorAll('div')).find((d) => {
        const st = d.getAttribute('style') || ''
        return st.includes('grid-template-columns') && (d.textContent || '').includes('Documents publiés')
      })
      if (kpiGrid) {
        const kids = [...kpiGrid.children]
        if (kids[0]) kids[0].setAttribute('data-mock-id', 'home-kpi-published')
        if (kids[1]) kids[1].setAttribute('data-mock-id', 'home-kpi-pending')
      }
      const titles = Array.from(content.querySelectorAll('div')).filter(
        (d) => d.children.length === 0 && (d.getAttribute('style') || '').includes('font-weight: 700'),
      )
      for (const t of titles) {
        const text = (t.textContent || '').trim()
        if (text === 'Reprendre') t.setAttribute('data-mock-id', 'home-section-resume')
        if (text.startsWith('En attente')) t.setAttribute('data-mock-id', 'home-section-approvals')
      }
    }
  })
}

export async function annotateMobileMenuMockup(page) {
  await page.evaluate(() => {
    const root = Array.from(document.querySelectorAll('div')).find((d) =>
      (d.getAttribute('style') || '').includes('390px'),
    )
    if (!root) return
    const drawer = Array.from(root.querySelectorAll('div')).find((d) => {
      const st = d.getAttribute('style') || ''
      return st.includes('320px') && st.includes('box-shadow')
    })
    if (drawer) {
      drawer.setAttribute('data-mock-id', 'mobile-menu')
      const space = Array.from(drawer.querySelectorAll('a')).find((a) =>
        (a.textContent || '').includes("Changer d'espace"),
      )
      if (space) space.setAttribute('data-mock-id', 'mobile-menu-space')
      const user = Array.from(drawer.querySelectorAll('a')).find((a) =>
        (a.textContent || '').includes('Tarek'),
      )
      if (user) user.setAttribute('data-mock-id', 'mobile-menu-user')
    }
  })
}

/** Annotate Login.dc.html elements with data-mock-id. */
export async function annotateLoginMockup(page) {
  await page.evaluate(() => {
    const formCol = document.querySelector('div[style*="560px"]')
    if (!formCol) return
    const brand = Array.from(formCol.querySelectorAll('div')).find(
      (d) => (d.textContent || '').trim() === 'Socle' && d.children.length === 0,
    )
    if (brand) brand.setAttribute('data-mock-id', 'brand-name')
    const h1 = formCol.querySelector('h1')
    if (h1) h1.setAttribute('data-mock-id', 'title')
    const sub = formCol.querySelector('p')
    if (sub) sub.setAttribute('data-mock-id', 'subtitle')
    const label = formCol.querySelector('label')
    if (label) label.setAttribute('data-mock-id', 'label-email')
    const emailBox = Array.from(formCol.querySelectorAll('div')).find(
      (d) =>
        (d.getAttribute('style') || '').includes('border: 1px solid #ECECEE') &&
        (d.getAttribute('style') || '').includes('border-radius: 9px'),
    )
    if (emailBox) emailBox.setAttribute('data-mock-id', 'email-field')
    const cta = formCol.querySelector('a.cta, a[href*="Dashboard"]')
    if (cta && (cta.textContent || '').includes('Continuer') && !(cta.textContent || '').includes('SSO')) {
      cta.setAttribute('data-mock-id', 'cta-continue')
    }
    const ou = Array.from(formCol.querySelectorAll('span')).find((s) => (s.textContent || '').trim() === 'ou')
    if (ou) ou.setAttribute('data-mock-id', 'divider-ou')
    const ssoSpans = Array.from(formCol.querySelectorAll('a.sso span, a span')).filter((s) =>
      (s.textContent || '').includes('Continuer avec'),
    )
    if (ssoSpans[0]) ssoSpans[0].setAttribute('data-mock-id', 'sso-org')
    if (ssoSpans[1]) ssoSpans[1].setAttribute('data-mock-id', 'sso-passkey')
    const footer = Array.from(formCol.querySelectorAll('p')).find((p) =>
      (p.textContent || '').includes('Réservé aux comptes'),
    )
    if (footer) footer.setAttribute('data-mock-id', 'footer')
    const quote = Array.from(document.querySelectorAll('p')).find((p) =>
      (p.textContent || '').includes('documentation qui suit'),
    )
    if (quote) quote.setAttribute('data-mock-id', 'quote')
    const svgSocle = Array.from(document.querySelectorAll('svg text')).find(
      (t) => (t.textContent || '').trim() === 'Socle',
    )
    if (svgSocle) svgSocle.setAttribute('data-mock-id', 'svg-socle')
  })
}

export async function annotateLoginErrorMockup(page) {
  await page.evaluate(() => {
    const formCol = document.querySelector('div[style*="560px"]')
    if (!formCol) return
    const brand = Array.from(formCol.querySelectorAll('div')).find(
      (d) => (d.textContent || '').trim() === 'Socle' && d.children.length === 0,
    )
    if (brand) brand.setAttribute('data-mock-id', 'brand-name')
    const h1 = formCol.querySelector('h1')
    if (h1) h1.setAttribute('data-mock-id', 'title')
    const sub = Array.from(formCol.querySelectorAll('p')).find((p) =>
      (p.textContent || '').includes('authentification SSO'),
    )
    if (sub) sub.setAttribute('data-mock-id', 'subtitle')
    const causes = Array.from(formCol.querySelectorAll('div')).find((d) =>
      (d.textContent || '').includes('Causes possibles'),
    )
    if (causes) causes.setAttribute('data-mock-id', 'causes')
    const retry = Array.from(formCol.querySelectorAll('a')).find((a) =>
      (a.textContent || '').includes('Réessayer'),
    )
    if (retry) retry.setAttribute('data-mock-id', 'cta-retry')
    const ghost = Array.from(formCol.querySelectorAll('a')).find((a) =>
      (a.textContent || '').includes('provisioning'),
    )
    if (ghost) ghost.setAttribute('data-mock-id', 'contact-support')
    const footer = Array.from(formCol.querySelectorAll('p')).find((p) =>
      (p.textContent || '').includes('Un problème persiste'),
    )
    if (footer) footer.setAttribute('data-mock-id', 'footer')
    const quote = Array.from(document.querySelectorAll('p')).find((p) =>
      (p.textContent || '').includes("L'accès suit"),
    )
    if (quote) quote.setAttribute('data-mock-id', 'quote')
    const svgSocle = Array.from(document.querySelectorAll('svg text')).find(
      (t) => (t.textContent || '').trim() === 'Socle',
    )
    if (svgSocle) svgSocle.setAttribute('data-mock-id', 'svg-socle')
    const svgNp = Array.from(document.querySelectorAll('svg text')).find((t) =>
      (t.textContent || '').includes('non provisionné'),
    )
    if (svgNp) svgNp.setAttribute('data-mock-id', 'svg-not-provisioned')
  })
}

export async function annotateMobileMockup(page) {
  await page.evaluate(() => {
    const root = Array.from(document.querySelectorAll('div')).find((d) =>
      (d.getAttribute('style') || '').includes('390px'),
    )
    if (!root) return
    const name = root.querySelector('.serif')
    if (name) name.setAttribute('data-mock-id', 'mobile-brand-name')
    const org = Array.from(root.querySelectorAll('div')).find(
      (d) => (d.textContent || '').trim() === 'Organisation Démo',
    )
    if (org) org.setAttribute('data-mock-id', 'mobile-org')
    const sso = root.querySelector('a.sso span, a span')
    if (sso) sso.setAttribute('data-mock-id', 'mobile-sso')
    const note = Array.from(root.querySelectorAll('p')).find((p) =>
      (p.textContent || '').includes('Réservé aux comptes'),
    )
    if (note) note.setAttribute('data-mock-id', 'mobile-note')
    const help = Array.from(root.querySelectorAll('div')).find(
      (d) => (d.textContent || '').includes("Besoin d'aide") && d.children.length <= 1,
    )
    if (help) help.setAttribute('data-mock-id', 'mobile-help')
  })
}

export async function collectMetrics(page, ids) {
  // Pin line-height so glyph box heights are comparable across UA defaults
  await page.evaluate((idList) => {
    for (const id of idList) {
      const el = document.querySelector(`[data-mock-id="${id}"]`)
      if (!el) continue
      if (el.tagName.toLowerCase() === 'text') continue
      const isAction =
        el.matches('button, a.cta, a.ghost, .login-cta, .login-cta--error') ||
        id.startsWith('cta-') ||
        id.startsWith('sso-') ||
        id === 'mobile-sso'
      el.style.lineHeight = isAction ? '1' : '1.2'
    }
  }, ids)
  return page.evaluate((idList) => {
    function firstFamily(ff) {
      return (ff || '')
        .split(',')[0]
        .replace(/['"]/g, '')
        .trim()
        .toLowerCase()
    }
    function normColor(c) {
      if (!c) return ''
      const m = c.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/i)
      if (!m) return c.toLowerCase().replace(/\s/g, '')
      const hex = (n) => Number(n).toString(16).padStart(2, '0')
      return `#${hex(m[1])}${hex(m[2])}${hex(m[3])}`
    }
    function normText(t) {
      return (t || '').replace(/\s+/g, ' ').trim()
    }
    function normWeight(w) {
      const map = { normal: '400', bold: '700' }
      return map[w] || String(w)
    }
    function normLh(lh, fs) {
      if (!lh || lh === 'normal') return 'normal'
      if (lh.endsWith('px') && fs && fs.endsWith('px')) {
        const ratio = parseFloat(lh) / parseFloat(fs)
        if (Number.isFinite(ratio)) return `${ratio.toFixed(2)}em`
      }
      return lh
    }
    const out = {}
    for (const id of idList) {
      const el = document.querySelector(`[data-mock-id="${id}"]`)
      if (!el) {
        out[id] = null
        continue
      }
      const cs = getComputedStyle(el)
      const r = el.getBoundingClientRect()
      const isSvgText = el.tagName.toLowerCase() === 'text'
      out[id] = {
        text: normText(el.textContent),
        fontFamily: firstFamily(cs.fontFamily),
        fontSize: cs.fontSize,
        fontWeight: normWeight(cs.fontWeight),
        fontStyle: cs.fontStyle,
        lineHeight: normLh(cs.lineHeight, cs.fontSize),
        letterSpacing: cs.letterSpacing === 'normal' ? '0px' : cs.letterSpacing,
        color: normColor(isSvgText ? cs.fill || el.getAttribute('fill') || cs.color : cs.color),
        box: { x: r.x, y: r.y, width: r.width, height: r.height },
      }
    }
    return out
  }, ids)
}

export async function assertFontsLoaded(page) {
  const report = await page.evaluate(async () => {
    await document.fonts.ready
    const check = (family) => {
      const faces = [...document.fonts].filter(
        (f) => f.family.replace(/['"]/g, '') === family,
      )
      return {
        family,
        count: faces.length,
        // italic/unused faces may stay "unloaded" — require at least one loaded face
        loaded: faces.some((f) => f.status === 'loaded'),
        statuses: faces.map((f) => f.status),
      }
    }
    return [check('Instrument Serif'), check('IBM Plex Sans'), check('IBM Plex Mono')]
  })
  return report
}

/**
 * @returns {{ id: string, mock: object, app: object, diffs: string[] }[]}
 */
export function compareMetrics(mockMap, appMap, ids, { pageExceptions = {} } = {}) {
  const results = []
  for (const id of ids) {
    const skip = new Set([
      ...(EXCEPTIONS[id]?.skip || []),
      ...(pageExceptions[id]?.skip || []),
    ])
    const mock = mockMap[id]
    const app = appMap[id]
    const diffs = []
    if (!mock) diffs.push('maquette: élément absent')
    if (!app) diffs.push('app: élément absent')
    if (mock && app) {
      const fields = [
        'text',
        'fontFamily',
        'fontSize',
        'fontWeight',
        'fontStyle',
        'lineHeight',
        'letterSpacing',
        'color',
      ]
      for (const f of fields) {
        if (skip.has(f)) continue
        let mv = mock[f]
        let av = app[f]
        // UA "normal" vs explicit ratio that equals the default — treat as equal when either side is normal
        if (f === 'lineHeight' && (mv === 'normal' || av === 'normal')) {
          if (mv === 'normal' && av === 'normal') continue
          // 1.00em is an explicit "normal" used on reset buttons
          if ((mv === 'normal' && av === '1.00em') || (av === 'normal' && mv === '1.00em')) continue
          if (mv === 'normal' && typeof av === 'string' && av.endsWith('em')) {
            const r = parseFloat(av)
            if (r >= 1.0 && r <= 1.55) continue
          }
          if (av === 'normal' && typeof mv === 'string' && mv.endsWith('em')) {
            const r = parseFloat(mv)
            if (r >= 1.0 && r <= 1.55) continue
          }
        }
        if (String(mv) !== String(av)) {
          diffs.push(`${f}: maquette=${JSON.stringify(mv)} app=${JSON.stringify(av)}`)
        }
      }
      if (!skip.has('box')) {
        for (const k of ['x', 'y', 'width', 'height']) {
          const d = Math.abs(mock.box[k] - app.box[k])
          if (d > 2) {
            diffs.push(`box.${k}: maquette=${mock.box[k].toFixed(1)} app=${app.box[k].toFixed(1)} Δ=${d.toFixed(1)}`)
          }
        }
      }
    }
    results.push({
      id,
      mock,
      app,
      diffs,
      exception: EXCEPTIONS[id]?.reason || pageExceptions[id]?.reason || null,
    })
  }
  return results
}
