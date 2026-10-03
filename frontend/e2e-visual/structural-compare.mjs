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

/** Document READ page — Main.dc.html (colonne principale + rail ; le shell est hors périmètre). */
export const PAGE_DESKTOP_IDS = [
  'doc-topbar',
  'doc-breadcrumb',
  'doc-breadcrumb-current',
  'doc-switch-page',
  'doc-switch-graph',
  'doc-switch-index',
  'doc-tabs',
  'doc-tab-read',
  'doc-status-badge',
  'doc-status-reliability',
  'doc-status-revised',
  'doc-attestation',
  'doc-attestation-title',
  'doc-attestation-text',
  'doc-attestation-cta',
  'doc-title',
  'doc-lead',
  'doc-section-first',
  'rail-toc-label',
  'rail-toc-link',
  'rail-owner-label',
  'rail-owner-name',
  'rail-author-label',
  'rail-author-name',
  'rail-author-date',
  'rail-modified-label',
  'rail-modified-name',
  'rail-modified-date',
  'rail-tags-label',
  'rail-tags-manage',
  'rail-reliability-label',
  'rail-reliability-value',
]

/** Sous la ligne de flottaison — comparés après défilement. */
export const PAGE_BELOW_FOLD_IDS = ['doc-related-title', 'doc-related-link', 'doc-feedback-label']

/** Document READ page — MobilePage.dc.html. */
export const PAGE_MOBILE_IDS = [
  'doc-mobile-title',
  'doc-mobile-space',
  'doc-status-badge',
  'doc-status-revised-mobile',
  'doc-title',
  'doc-lead',
  'doc-section-first',
  'doc-mobile-tab-read',
]

/** Document EDIT screen — Edit.dc.html (colonne principale ; le shell est hors périmètre). */
export const EDIT_DESKTOP_IDS = [
  'edit-topbar',
  'edit-breadcrumb-current',
  'edit-save-status',
  'edit-word-count',
  'edit-presence',
  'edit-preview',
  'edit-send-review',
  'edit-tabs',
  'edit-toolbar',
  'edit-title',
  'edit-assistant',
  'edit-assistant-label',
  'edit-card-long',
  'edit-card-long-text',
  'edit-card-link',
  'edit-card-link-text',
  'edit-meta',
  'edit-meta-label',
  'edit-meta-owner',
  'edit-meta-reliability',
  'edit-meta-review',
  'edit-meta-tags-label',
  'edit-meta-tag',
  'edit-meta-add-tag',
  'edit-meta-custom-label',
  'edit-meta-custom-manage',
  'edit-meta-custom-field-1',
  'edit-meta-custom-field-2',
  'edit-meta-add-field',
]

/** Historique — History.dc.html (colonne principale ; v12 courante, v11 / v10 / v9 restaurables). */
export const HISTORY_DESKTOP_IDS = [
  'hist-topbar',
  'hist-breadcrumb-current',
  'hist-back',
  'hist-tabs',
  'hist-tab-history',
  'hist-title',
  'hist-subtitle',
  ...[0, 1, 2, 3].flatMap((i) => [
    `hist-r${i}-dot`,
    `hist-r${i}-version`,
    `hist-r${i}-date`,
    ...(i === 0 ? ['hist-r0-badge'] : []),
    `hist-r${i}-summary`,
    `hist-r${i}-avatar`,
    `hist-r${i}-author`,
    `hist-r${i}-added`,
    `hist-r${i}-removed`,
    ...(i > 0 ? [`hist-r${i}-restore`] : []),
  ]),
  'hist-r1-compare',
]

/** Comparaison — Diff.dc.html (v11 → v12, côte à côte). */
export const DIFF_DESKTOP_IDS = [
  'diff-topbar',
  'diff-breadcrumb-current',
  'diff-back',
  'diff-restore',
  'diff-controls',
  'diff-sel-from',
  'diff-sel-to',
  'diff-added',
  'diff-removed',
  'diff-toggle',
  'diff-seg-side',
  'diff-seg-unified',
  'diff-box',
  'diff-hunk-0',
  'diff-hunk-1',
  'diff-hunk-2',
]

/** Modale « Restaurer la v11 ? » — RestoreVersion.dc.html. */
export const RESTORE_IDS = [
  'restore-modal',
  'restore-title',
  'restore-text-1',
  'restore-text-2',
  'restore-warning',
  'restore-cancel',
  'restore-confirm',
]

/** Historique mobile — MobileHistory.dc.html (v12 actuelle, v11 comparable, v1 système). */
export const HISTORY_MOBILE_IDS = [
  'hist-mobile-title',
  ...[0, 1, 2].flatMap((i) => [`hist-m-avatar-${i}`, `hist-m-name-${i}`, `hist-m-date-${i}`]),
  'hist-m-compare-1',
  'hist-m-restore-2',
  'doc-mobile-tab-history',
]

/** Actions pinned à line-height 1 des deux côtés (maquette : <a>/<span> ; app : <a>/<button>). */
const ACTION_ID_RE =
  /^(hist-back|hist-r\d+-(compare|restore)|hist-m-(compare|restore)-\d+|diff-(back|restore|seg-\w+)|restore-(cancel|confirm))$/

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
      const section = Array.from(sidebar.querySelectorAll('div')).find((d) => {
        const t = (d.textContent || '').trim()
        return d.children.length === 0 && t.startsWith('Identité')
      })
      if (section) section.setAttribute('data-mock-id', 'shell-space-section')
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
      const homeLabel = Array.from(tabbar.querySelectorAll('span')).find(
        (s) => (s.textContent || '').trim() === 'Accueil',
      )
      if (homeLabel) homeLabel.setAttribute('data-mock-id', 'mobile-tab-home')
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

/** Annotate Main.dc.html (document READ page, desktop) with data-mock-id. */
export async function annotatePageMockup(page) {
  await page.evaluate(() => {
    const leaf = (scope, txt) =>
      Array.from(scope.querySelectorAll('*')).find(
        (e) => e.children.length === 0 && (e.textContent || '').trim() === txt,
      )
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    const main = root?.children[1]
    if (!main) return
    const [topbar, tabs, content] = [...main.children]

    if (topbar) {
      topbar.setAttribute('data-mock-id', 'doc-topbar')
      const crumbs = topbar.children[0]
      crumbs?.setAttribute('data-mock-id', 'doc-breadcrumb')
      crumbs?.lastElementChild?.setAttribute('data-mock-id', 'doc-breadcrumb-current')
      const sw = (label, id) => {
        const el = Array.from(topbar.querySelectorAll('a')).find((a) => (a.textContent || '').trim() === label)
        el?.setAttribute('data-mock-id', id)
      }
      sw('Page', 'doc-switch-page')
      sw('Graphe', 'doc-switch-graph')
      sw('Index', 'doc-switch-index')
    }
    if (tabs) {
      tabs.setAttribute('data-mock-id', 'doc-tabs')
      const read = Array.from(tabs.querySelectorAll('a')).find((a) => (a.textContent || '').trim() === 'Lire')
      read?.setAttribute('data-mock-id', 'doc-tab-read')
    }
    const article = content?.children[0]
    const rail = content?.children[1]
    if (article) {
      const status = article.children[0]
      if (status) {
        status.children[0]?.setAttribute('data-mock-id', 'doc-status-badge')
        status.children[2]?.setAttribute('data-mock-id', 'doc-status-reliability')
        status.children[4]?.setAttribute('data-mock-id', 'doc-status-revised')
      }
      const banner = article.children[1]
      if (banner) {
        banner.setAttribute('data-mock-id', 'doc-attestation')
        const textBox = banner.children[1]
        textBox?.children[0]?.setAttribute('data-mock-id', 'doc-attestation-title')
        textBox?.children[1]?.setAttribute('data-mock-id', 'doc-attestation-text')
        banner.querySelector('button')?.setAttribute('data-mock-id', 'doc-attestation-cta')
      }
      article.querySelector('h1')?.setAttribute('data-mock-id', 'doc-title')
      article.querySelector('p')?.setAttribute('data-mock-id', 'doc-lead')
      article.querySelector('h2')?.setAttribute('data-mock-id', 'doc-section-first')
      article.querySelector('h2#documents-lies')?.setAttribute('data-mock-id', 'doc-related-title')
      const related = Array.from(article.querySelectorAll('a')).find((a) =>
        (a.textContent || '').includes('Procédure de provisioning'),
      )
      related?.setAttribute('data-mock-id', 'doc-related-link')
      leaf(article, 'Cette page vous a-t-elle été utile ?')?.setAttribute('data-mock-id', 'doc-feedback-label')
    }
    if (rail) {
      rail.setAttribute('data-mock-id', 'doc-rail')
      const stick = rail.firstElementChild // conteneur sticky
      leaf(rail, 'Sur cette page')?.setAttribute('data-mock-id', 'rail-toc-label')
      rail.querySelector('a.rail-link')?.setAttribute('data-mock-id', 'rail-toc-link')
      const labelIds = {
        Propriétaire: 'rail-owner-label',
        Auteur: 'rail-author-label',
        'Dernière modification': 'rail-modified-label',
        Tags: 'rail-tags-label',
        Fiabilité: 'rail-reliability-label',
      }
      for (const [txt, id] of Object.entries(labelIds)) leaf(rail, txt)?.setAttribute('data-mock-id', id)
      const blockOf = (txt) =>
        stick && Array.from(stick.children).find((c) => (leaf(c, txt) ? true : false))
      leaf(blockOf('Propriétaire') ?? rail, 'Équipe Identité')?.setAttribute('data-mock-id', 'rail-owner-name')
      leaf(rail, 'Système (migration)')?.setAttribute('data-mock-id', 'rail-author-name')
      leaf(rail, 'Créé le 14 juillet 2026')?.setAttribute('data-mock-id', 'rail-author-date')
      leaf(rail, 'Tarek Fezai')?.setAttribute('data-mock-id', 'rail-modified-name')
      leaf(rail, '12 septembre 2026 à 14:22 · v12')?.setAttribute('data-mock-id', 'rail-modified-date')
      blockOf('Tags')?.querySelector('a[href="TagsAdmin.dc.html"]')?.setAttribute('data-mock-id', 'rail-tags-manage')
      leaf(rail, '91% · revue à jour')?.setAttribute('data-mock-id', 'rail-reliability-value')
    }
  })
}

/** Annotate MobilePage.dc.html with data-mock-id. */
export async function annotateMobilePageMockup(page) {
  await page.evaluate(() => {
    const root = Array.from(document.querySelectorAll('div')).find((d) =>
      (d.getAttribute('style') || '').includes('390px'),
    )
    if (!root) return
    const top = root.children[0]
    const heading = top?.children[1]
    heading?.children[0]?.setAttribute('data-mock-id', 'doc-mobile-title')
    heading?.children[1]?.setAttribute('data-mock-id', 'doc-mobile-space')
    const content = root.children[1]
    if (content) {
      const status = content.children[0]
      status?.children[0]?.setAttribute('data-mock-id', 'doc-status-badge')
      status?.children[2]?.setAttribute('data-mock-id', 'doc-status-revised-mobile')
      content.querySelector('h1')?.setAttribute('data-mock-id', 'doc-title')
      content.querySelector('p')?.setAttribute('data-mock-id', 'doc-lead')
      content.querySelector('h2')?.setAttribute('data-mock-id', 'doc-section-first')
    }
    const bar = root.children[root.children.length - 1]
    const read = Array.from(bar?.querySelectorAll('span') ?? []).find((s) => (s.textContent || '').trim() === 'Lire')
    read?.setAttribute('data-mock-id', 'doc-mobile-tab-read')
  })
}

/**
 * Annotate Edit.dc.html (document EDIT screen, desktop) with data-mock-id.
 * À appeler AVANT la normalisation de la maquette (structure d'origine, accès par index).
 */
export async function annotateEditMockup(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    const main = root?.children[1]
    if (!main) return
    const [topbar, , tabs, row] = [...main.children]
    const set = (el, id) => el?.setAttribute('data-mock-id', id)

    set(topbar, 'edit-topbar')
    const meta = topbar?.children[0]
    set(meta?.children[2], 'edit-breadcrumb-current')
    set(meta?.children[4], 'edit-save-status')
    set(meta?.children[6], 'edit-word-count')
    const actions = topbar?.children[1]
    set(actions?.children[0], 'edit-presence')
    set(actions?.children[1], 'edit-preview')
    set(actions?.children[2], 'edit-send-review')
    set(tabs, 'edit-tabs')

    const col = row?.children[0]?.children[0]
    set(col?.children[0], 'edit-toolbar')
    set(col?.querySelector('h1'), 'edit-title')

    const panel = row?.children[1]
    set(panel, 'edit-assistant')
    set(panel?.children[0], 'edit-assistant-label')
    set(panel?.children[1], 'edit-card-long')
    set(panel?.children[1]?.querySelector('p'), 'edit-card-long-text')
    set(panel?.children[2], 'edit-card-link')
    set(panel?.children[2]?.querySelector('p'), 'edit-card-link-text')
    const metaBlock = panel?.children[4]
    set(metaBlock, 'edit-meta')
    set(metaBlock?.children[0], 'edit-meta-label')
    set(metaBlock?.children[1], 'edit-meta-owner')
    set(metaBlock?.children[2], 'edit-meta-reliability')
    set(metaBlock?.children[3], 'edit-meta-review')
    set(metaBlock?.children[4], 'edit-meta-tags-label')
    const tags = metaBlock?.children[5]
    set(tags?.children[0], 'edit-meta-tag')
    set(tags?.lastElementChild, 'edit-meta-add-tag')
    const customHead = metaBlock?.children[6]
    set(customHead?.children[0], 'edit-meta-custom-label')
    set(customHead?.children[1], 'edit-meta-custom-manage')
    const fields = metaBlock?.children[7]
    set(fields?.children[0]?.querySelector('input'), 'edit-meta-custom-field-1')
    set(fields?.children[1]?.querySelector('input'), 'edit-meta-custom-field-2')
    set(fields?.children[2], 'edit-meta-add-field')
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

/**
 * Annotate History.dc.html (desktop) with data-mock-id. Structure d'origine, accès par index :
 * root > [sidebar, main > [topbar, tabs, content > column > [h1, p, timeline > (ligne, item…)]]].
 */
export async function annotateHistoryMockup(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    const main = root?.children[1]
    if (!main) return
    const set = (el, id) => el?.setAttribute('data-mock-id', id)
    const [topbar, tabs, content] = [...main.children]
    set(topbar, 'hist-topbar')
    set(topbar?.children[0]?.lastElementChild, 'hist-breadcrumb-current')
    set(topbar?.children[1], 'hist-back')
    set(tabs, 'hist-tabs')
    set(
      Array.from(tabs?.querySelectorAll('a') ?? []).find((a) => (a.textContent || '').trim() === 'Historique'),
      'hist-tab-history',
    )
    const col = content?.children[0]
    set(col?.querySelector('h1'), 'hist-title')
    set(col?.querySelector('p'), 'hist-subtitle')
    const timeline = col?.children[2]
    const items = [...(timeline?.children ?? [])].slice(1) // [0] = filet vertical
    items.forEach((item, i) => {
      const p = `hist-r${i}-`
      set(item.children[0], `${p}dot`)
      const head = item.children[1]
      set(head?.children[0], `${p}version`)
      set(head?.children[1], `${p}date`)
      if (i === 0) set(head?.children[2], `${p}badge`)
      set(item.children[2], `${p}summary`)
      const foot = item.children[3]
      // v12 : [auteur, +n, −n] ; autres : [[auteur, +n, −n], (Comparer), Restaurer]
      const meta = i === 0 ? foot : foot?.children[0]
      const author = meta?.children[0]
      set(author?.children[0], `${p}avatar`)
      set(author?.children[1], `${p}author`)
      set(meta?.children[1], `${p}added`)
      set(meta?.children[2], `${p}removed`)
      if (i > 0) {
        const links = [...(foot?.children ?? [])].slice(1)
        const byLabel = (label) => links.find((a) => (a.textContent || '').trim() === label)
        set(byLabel('Comparer'), `${p}compare`)
        set(byLabel('Restaurer'), `${p}restore`)
      }
    })
  })
}

/** Annotate Diff.dc.html : root > [topbar, controls, body > box > (.hunk, .diffline…)]. */
export async function annotateDiffMockup(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    if (!root) return
    const set = (el, id) => el?.setAttribute('data-mock-id', id)
    const [topbar, controls, body] = [...root.children]
    set(topbar, 'diff-topbar')
    set(topbar?.children[0]?.lastElementChild, 'diff-breadcrumb-current')
    set(topbar?.children[1]?.children[0], 'diff-back')
    set(topbar?.children[1]?.children[1], 'diff-restore')
    set(controls, 'diff-controls')
    set(controls?.children[0], 'diff-sel-from')
    set(controls?.children[2], 'diff-sel-to')
    set(controls?.children[3], 'diff-added')
    set(controls?.children[4], 'diff-removed')
    const toggle = controls?.children[5]
    set(toggle, 'diff-toggle')
    set(toggle?.children[0], 'diff-seg-side')
    set(toggle?.children[1], 'diff-seg-unified')
    const box = body?.children[0]
    set(box, 'diff-box')
    box?.querySelectorAll('.hunk > .cell').forEach((cell, i) => set(cell, `diff-hunk-${i}`))
  })
}

/** Annotate RestoreVersion.dc.html : la carte (480 px) > [en-tête, p, p, avertissement, actions]. */
export async function annotateRestoreMockup(page) {
  await page.evaluate(() => {
    const card = Array.from(document.querySelectorAll('div')).find((d) =>
      (d.getAttribute('style') || '').includes('width: 480px'),
    )
    if (!card) return
    const set = (el, id) => el?.setAttribute('data-mock-id', id)
    set(card, 'restore-modal')
    const [head, p1, p2, warning, actions] = [...card.children]
    set(head?.querySelector('h1'), 'restore-title')
    set(p1, 'restore-text-1')
    set(p2, 'restore-text-2')
    set(warning, 'restore-warning')
    set(actions?.children[0], 'restore-cancel')
    set(actions?.children[1], 'restore-confirm')
  })
}

/** Annotate MobileHistory.dc.html : root > [topbar, liste > lignes, onglets]. */
export async function annotateMobileHistoryMockup(page) {
  await page.evaluate(() => {
    const root = Array.from(document.querySelectorAll('div')).find((d) =>
      (d.getAttribute('style') || '').includes('390px'),
    )
    if (!root) return
    const set = (el, id) => el?.setAttribute('data-mock-id', id)
    set(root.children[0]?.children[1], 'hist-mobile-title')
    const list = root.children[1]
    ;[...(list?.children ?? [])].forEach((row, i) => {
      set(row.children[0], `hist-m-avatar-${i}`)
      const body = row.children[1]
      set(body?.children[0], `hist-m-name-${i}`)
      set(body?.children[1], `hist-m-date-${i}`)
      const link = body?.children[2]
      if (link && i === 1) set(link, 'hist-m-compare-1')
      if (link && i === 2) set(link, 'hist-m-restore-2')
    })
    const bar = root.children[root.children.length - 1]
    set(
      Array.from(bar?.querySelectorAll('span') ?? []).find((s) => (s.textContent || '').trim() === 'Historique'),
      'doc-mobile-tab-history',
    )
  })
}

export async function collectMetrics(page, ids) {
  // Pin line-height so glyph box heights are comparable across UA defaults
  await page.evaluate(
    ({ idList, actionRe }) => {
      const re = new RegExp(actionRe)
      for (const id of idList) {
        const el = document.querySelector(`[data-mock-id="${id}"]`)
        if (!el) continue
        if (el.tagName.toLowerCase() === 'text') continue
        const isAction =
          el.matches('button, a.cta, a.ghost, .login-cta, .login-cta--error') ||
          id.startsWith('cta-') ||
          id.startsWith('sso-') ||
          id.startsWith('edit-meta-add-') ||
          id === 'mobile-sso' ||
          re.test(id)
        el.style.lineHeight = isAction ? '1' : '1.2'
      }
    },
    { idList: ids, actionRe: ACTION_ID_RE.source },
  )
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
          if (d > 3) {
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
