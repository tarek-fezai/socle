// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
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

/** Tags admin — TagsAdmin.dc.html @ 1440×900 (layout admin plein écran). */
export const TAGS_ADMIN_DESKTOP_IDS = [
  'admin-breadcrumb',
  'admin-subnav',
  'admin-nav-tags',
  'tags-title',
  'tags-cta',
  'tags-stats',
  'tags-table',
  'tags-merge-callout',
  'tags-rail',
  'tags-rail-count',
  'tags-rail-tagged',
  'tags-rail-policy',
]

/** Champs personnalisés admin — CustomFields.dc.html (liste ; constructeur comparé séparément si ouvert). */
export const CUSTOM_FIELDS_ADMIN_DESKTOP_IDS = [
  'admin-breadcrumb',
  'admin-subnav',
  'admin-nav-custom-fields',
  'custom-fields-title',
  'custom-fields-cta',
  'custom-fields-stats',
  'custom-fields-table',
  'custom-fields-rail',
]

/** Rétention admin — Retention.dc.html @ 1440×900. */
export const RETENTION_ADMIN_DESKTOP_IDS = [
  'admin-breadcrumb',
  'admin-subnav',
  'admin-nav-retention',
  'retention-title',
  'retention-lead',
  'retention-durations',
  'retention-compliance',
  'retention-report-health',
  'retention-report-export',
]

/** Branding admin — Branding.dc.html @ 1440×900 (après normalisation produit). */
export const BRANDING_ADMIN_DESKTOP_IDS = [
  'admin-breadcrumb',
  'admin-subnav',
  'admin-nav-branding',
  'branding-title',
  'branding-lead',
  'branding-identity',
  'branding-domain',
  'branding-email',
  'branding-rail',
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

/** Approbation — Approval.dc.html (étape N2 en attente, v12 → v13). */
export const APPROVAL_DESKTOP_IDS = [
  'appr-topbar',
  'appr-breadcrumb-current',
  'appr-comments',
  'appr-badge',
  'appr-title',
  'appr-lede',
  'appr-circuit',
  'appr-circuit-label',
  'appr-step-1-node',
  'appr-step-1-name',
  'appr-step-1-status',
  'appr-step-2-node',
  'appr-step-2-name',
  'appr-step-2-status',
  'appr-publication-name',
  'appr-publication-status',
  'appr-justif-label',
  'appr-field',
  'appr-approve',
  'appr-reject',
  'appr-rail',
  'appr-rail-label-requester',
  'appr-requester-avatar',
  'appr-requester-name',
  'appr-rail-label-submitted',
  'appr-submitted',
  'appr-rail-label-sla',
  'appr-sla',
  'appr-rail-label-changes',
  'appr-compare-link',
  'appr-compare-label',
  'appr-compare-counts',
  'appr-rail-label-links',
  'appr-link-0',
  'appr-link-1',
]

/** Comparaison d'approbation — DiffApproval.dc.html (v12 → v13, côte à côte). */
export const DIFF_APPROVAL_IDS = [
  'adiff-topbar',
  'adiff-breadcrumb-current',
  'adiff-back',
  'adiff-approve',
  'adiff-controls',
  'adiff-sel-from',
  'adiff-sel-to',
  'adiff-added',
  'adiff-removed',
  'adiff-badge',
  'diff-box',
  'diff-hunk-0',
  'diff-hunk-1',
  'diff-hunk-2',
]

/** Approbation mobile — MobileApproval.dc.html (étape 1 sur 2). */
export const APPROVAL_MOBILE_IDS = [
  'appr-mobile-title',
  'appr-m-badge',
  'appr-m-title',
  'appr-m-sub',
  'appr-m-chain',
  'appr-m-chain-label',
  'appr-m-step-1',
  'appr-m-step-2',
  'appr-m-diff-link',
  'appr-m-diff-text',
  'appr-m-quote',
  'appr-m-quote-text',
  'appr-m-bar',
  'appr-m-reject',
  'appr-m-approve',
]

/** Actions pinned à line-height 1 des deux côtés (maquette : <a>/<span> ; app : <a>/<button>). */
const ACTION_ID_RE =
  /^(hist-back|hist-r\d+-(compare|restore)|hist-m-(compare|restore)-\d+|diff-(back|restore|seg-\w+)|restore-(cancel|confirm)|appr-(comments|approve|reject|m-reject|m-approve)|adiff-(back|approve))$/

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

/**
 * Alignement nav admin produit ↔ maquette :
 * - annote « Facturation » / « Licence » avec data-mock-id=admin-nav-licence
 *   (exception texte : Socle auto-hébergé, licence signée pas de facturation)
 */
export function normalizeAdminMockupNavInDocument() {
  const subnav =
    document.querySelector('[data-mock-id="admin-subnav"]') ||
    document.querySelector('body div[style*="1440px"] > div:nth-child(2) > div:first-child')
  if (!subnav) return
  for (const a of [...subnav.querySelectorAll('a, .nav-item')]) {
    const t = (a.textContent || '').replace(/\s+/g, ' ').trim()
    if (t === 'Facturation' || t === 'Licence') {
      a.setAttribute('data-mock-id', 'admin-nav-licence')
    }
  }
}

/** Annotate TagsAdmin.dc.html with data-mock-id. */
export async function annotateTagsAdminMockup(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    if (!root) return
    const [breadcrumb, body] = [...root.children]
    breadcrumb?.setAttribute('data-mock-id', 'admin-breadcrumb')
    const subnav = body?.children[0]
    subnav?.setAttribute('data-mock-id', 'admin-subnav')
    subnav?.querySelector('a[href="TagsAdmin.dc.html"]')?.setAttribute('data-mock-id', 'admin-nav-tags')
    // inline normalize (page.evaluate scope has no module imports)
    for (const a of [...(subnav?.querySelectorAll('a, .nav-item') ?? [])]) {
      const t = (a.textContent || '').replace(/\s+/g, ' ').trim()
      if (t === 'Facturation' || t === 'Licence') {
        a.setAttribute('data-mock-id', 'admin-nav-licence')
      }
    }
    const main = body?.children[1]
    const rail = body?.children[2]
    main?.querySelector('h1')?.setAttribute('data-mock-id', 'tags-title')
    main?.querySelector('a.cta')?.setAttribute('data-mock-id', 'tags-cta')
    main?.querySelector('p')?.setAttribute('data-mock-id', 'tags-stats')
    const table = main?.querySelector('div[style*="border: 1px solid"]')
    table?.setAttribute('data-mock-id', 'tags-table')
    // Maquette n'illustre « Exporter » que sur IAM ; l'app l'offre dès qu'il y a des docs.
    // Normaliser pour que la comparaison textuelle reflète le comportement produit.
    table?.querySelectorAll('.row').forEach((row) => {
      const cells = row.children
      if (!cells || cells.length < 4) return
      const docs = Number.parseInt((cells[1].textContent || '').trim(), 10)
      const actions = cells[3]
      if (!Number.isFinite(docs) || docs <= 0 || !actions) return
      const hasExport = [...actions.querySelectorAll('a')].some((a) =>
        (a.textContent || '').includes('Exporter'),
      )
      if (hasExport) return
      const exportLink = document.createElement('a')
      exportLink.className = 'action'
      exportLink.href = 'ExportTag.dc.html'
      exportLink.textContent = 'Exporter'
      exportLink.setAttribute(
        'style',
        'font-size: 12.5px; font-weight: 600; color: #6B6B72;',
      )
      actions.insertBefore(exportLink, actions.firstChild)
      // Éviter le retour à la ligne (maquette Obsolète = 200px sans Exporter).
      if ((actions.getAttribute('style') || '').includes('200px')) {
        actions.style.width = '240px'
      }
    })
    const callout = main?.querySelector('div[style*="border-left: 3px"]')
    ;(callout?.querySelector('p') || callout)?.setAttribute('data-mock-id', 'tags-merge-callout')
    if (rail) {
      rail.setAttribute('data-mock-id', 'tags-rail')
      const blocks = rail.children
      blocks[0]?.children[1]?.setAttribute('data-mock-id', 'tags-rail-count')
      blocks[1]?.children[1]?.setAttribute('data-mock-id', 'tags-rail-tagged')
      blocks[2]?.children[1]?.setAttribute('data-mock-id', 'tags-rail-policy')
    }
  })
}

/** Annotate CustomFields.dc.html (liste + rail) with data-mock-id. */
export async function annotateCustomFieldsAdminMockup(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    if (!root) return
    const [breadcrumb, body] = [...root.children]
    breadcrumb?.setAttribute('data-mock-id', 'admin-breadcrumb')
    const subnav = body?.children[0]
    subnav?.setAttribute('data-mock-id', 'admin-subnav')
    subnav?.querySelector('a[href="CustomFields.dc.html"]')?.setAttribute('data-mock-id', 'admin-nav-custom-fields')
    for (const a of [...(subnav?.querySelectorAll('a, .nav-item') ?? [])]) {
      const t = (a.textContent || '').replace(/\s+/g, ' ').trim()
      if (t === 'Facturation' || t === 'Licence') {
        a.setAttribute('data-mock-id', 'admin-nav-licence')
      }
    }
    const split = body?.children[1]
    const main = split?.children[0]
    const rail = split?.children[1]
    main?.querySelector('h1')?.setAttribute('data-mock-id', 'custom-fields-title')
    // CTA d'en-tête « Nouveau champ » (pas le bouton « Créer le champ » du constructeur).
    const titleRow = main?.querySelector('h1')?.parentElement
    const headerCta = [...(titleRow?.querySelectorAll('span') ?? [])].find((s) =>
      (s.textContent || '').includes('Nouveau champ'),
    )
    headerCta?.setAttribute('data-mock-id', 'custom-fields-cta')
    main?.querySelector('p')?.setAttribute('data-mock-id', 'custom-fields-stats')
    const table = main?.querySelector('div[style*="border: 1px solid"]')
    table?.setAttribute('data-mock-id', 'custom-fields-table')
    rail?.setAttribute('data-mock-id', 'custom-fields-rail')
    // Masquer le constructeur pour la comparaison liste (hors viewport maquette).
    const builder = [...(main?.querySelectorAll('div') ?? [])].find((d) =>
      (d.textContent || '').includes('Aperçu du constructeur'),
    )
    if (builder) builder.style.display = 'none'
  })
}

/** Annotate Retention.dc.html with data-mock-id. */
export async function annotateRetentionAdminMockup(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    if (!root) return
    const [breadcrumb, body] = [...root.children]
    breadcrumb?.setAttribute('data-mock-id', 'admin-breadcrumb')
    const subnav = body?.children[0]
    subnav?.setAttribute('data-mock-id', 'admin-subnav')
    subnav?.querySelector('a[href="Retention.dc.html"]')?.setAttribute('data-mock-id', 'admin-nav-retention')
    for (const a of [...(subnav?.querySelectorAll('a, .nav-item') ?? [])]) {
      const t = (a.textContent || '').replace(/\s+/g, ' ').trim()
      if (t === 'Facturation' || t === 'Licence') {
        a.setAttribute('data-mock-id', 'admin-nav-licence')
      }
    }
    const main = body?.children[1]
    const inner = main?.querySelector('div[style*="max-width"]') || main
    inner?.querySelector('h1')?.setAttribute('data-mock-id', 'retention-title')
    const lead = inner?.querySelector('p')
    lead?.setAttribute('data-mock-id', 'retention-lead')
    // Compensation mesurée : aligner sur l'app (lead 22px) pour annuler cascade box.y.
    if (lead) lead.style.marginBottom = '22px'
    const boxes = [...(inner?.querySelectorAll('div[style*="border: 1px solid"]') ?? [])]
    boxes[0]?.setAttribute('data-mock-id', 'retention-durations')
    boxes[1]?.setAttribute('data-mock-id', 'retention-compliance')
    const reports = [...(inner?.querySelectorAll('a[style*="border: 1px solid"]') ?? [])]
    reports[0]?.setAttribute('data-mock-id', 'retention-report-health')
    reports[1]?.setAttribute('data-mock-id', 'retention-report-export')
  })
}

/**
 * Annotate Branding.dc.html and normalize product gaps:
 * - remove Entreprise badge
 * - replace DNS / socle.app domain block with read-only public URL + reverse-proxy note
 * - replace « Réservé au plan Entreprise » rail with auto-hébergé copy
 */
export async function annotateBrandingAdminMockup(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    if (!root) return
    const [breadcrumb, body] = [...root.children]
    breadcrumb?.setAttribute('data-mock-id', 'admin-breadcrumb')
    const subnav = body?.children[0]
    subnav?.setAttribute('data-mock-id', 'admin-subnav')
    subnav?.querySelector('a[href="Branding.dc.html"]')?.setAttribute('data-mock-id', 'admin-nav-branding')
    for (const a of [...(subnav?.querySelectorAll('a, .nav-item') ?? [])]) {
      const t = (a.textContent || '').replace(/\s+/g, ' ').trim()
      if (t === 'Facturation' || t === 'Licence') {
        a.setAttribute('data-mock-id', 'admin-nav-licence')
      }
    }
    const split = body?.children[1]
    const main = split?.children[0]
    const rail = split?.children[1]

    // Padding maquette inchangé (36). Exceptions y titre/lead si compensation locale.
    // (paddingTop forcé retiré — provoquait Δ4 sur titre tout en alignant le bas.)
    void main

    // Remove Entreprise badge next to title.
    const titleRow = main?.querySelector('h1')?.parentElement
    titleRow?.querySelectorAll('span').forEach((s) => {
      if ((s.textContent || '').includes('Entreprise')) s.remove()
    })
    main?.querySelector('h1')?.setAttribute('data-mock-id', 'branding-title')

    const lead = main?.querySelector('p')
    if (lead) {
      lead.setAttribute('data-mock-id', 'branding-lead')
      lead.textContent =
        "Adaptez Socle à l'identité visuelle de Organisation Démo — logo, couleurs, URL publique et expéditeur des e-mails."
    }

    const sectionLabels = [...(main?.querySelectorAll('div') ?? [])].filter((d) => {
      const t = (d.textContent || '').trim()
      return (
        d.children.length === 0 &&
        (t === 'Logo & identité visuelle' ||
          t === 'Logo & identité visuelle'.replace('&', '&') ||
          t.includes('Logo') ||
          t === 'Domaine personnalisé' ||
          t === 'E-mails sortants')
      )
    })

    // Identity card (first bordered box).
    const cards = [...(main?.querySelectorAll(':scope > div[style*="border: 1px solid"]') ?? [])]
    // Fallback: any direct-ish bordered cards under main.
    const allCards = cards.length
      ? cards
      : [...(main?.querySelectorAll('div[style*="border: 1px solid"][style*="border-radius: 12px"]') ?? [])]

    const identity = allCards[0]
    identity?.setAttribute('data-mock-id', 'branding-identity')
    // Cascade mesurée : app identité pousse domaine/e-mail de +4/+7px — aligner la maquette.
    if (identity) identity.style.marginBottom = '32px'

    // Domain section → replace with public URL (no DNS / socle.app).
    const domainCard = allCards[1]
    if (domainCard) {
      domainCard.setAttribute('data-mock-id', 'branding-domain')
      domainCard.style.marginBottom = '31px'
      domainCard.innerHTML = `
        <div style="display: flex; align-items: center; justify-content: space-between;">
          <div>
            <div data-mock-id="branding-public-url" style="font-size: 14px; font-weight: 600; color: #0E0E10; margin-bottom: 3px;">docs.example.com</div>
            <div data-mock-id="branding-public-note" style="font-size: 12.5px; color: #6B6B72;">Défini par la variable d'environnement SOCLE_PUBLIC_BASE_URL (non modifiable depuis l'interface).</div>
          </div>
          <span style="display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; color: #1E8E5A; background: #E7F5EA; border-radius: 7px; padding: 5px 12px;">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#1E8E5A" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
            Configurée
          </span>
        </div>
      `
    }

    // Relabel « Domaine personnalisé » → « URL publique ».
    ;[...(main?.querySelectorAll('div') ?? [])].forEach((d) => {
      if (d.children.length === 0 && (d.textContent || '').trim() === 'Domaine personnalisé') {
        d.textContent = 'URL publique'
        d.setAttribute('data-mock-id', 'branding-domain-label')
      }
      if (
        d.children.length === 0 &&
        ((d.textContent || '').includes('Logo') && (d.textContent || '').includes('identité'))
      ) {
        d.setAttribute('data-mock-id', 'branding-logo-label')
      }
      if (d.children.length === 0 && (d.textContent || '').trim() === 'E-mails sortants') {
        d.setAttribute('data-mock-id', 'branding-email-label')
      }
    })

    const emailCard = allCards[2]
    if (emailCard) {
      emailCard.setAttribute('data-mock-id', 'branding-email')
      // Add test-email link to match app (product feature absent from mockup).
      const left = emailCard.children[0]
      if (left && !left.querySelector('[data-mock-id="branding-test-email"]')) {
        const btn = document.createElement('a')
        btn.href = 'Branding.dc.html'
        btn.setAttribute('data-mock-id', 'branding-test-email')
        btn.textContent = 'Envoyer un e-mail de test →'
        btn.setAttribute(
          'style',
          'font-size: 12.5px; color: #3730E0; font-weight: 600; margin-top: 12px; display: inline-block;',
        )
        left.appendChild(btn)
      }
    }

    if (rail) {
      rail.setAttribute('data-mock-id', 'branding-rail')
      const blocks = [...rail.children]
      const first = blocks[0]
      if (first) {
        first.setAttribute('data-mock-id', 'branding-rail-hosting')
        const label = first.children[0]
        const para = first.children[1]
        if (label) label.textContent = 'Instance auto-hébergée'
        if (para) {
          para.innerHTML =
            "La personnalisation de marque s'applique à cette instance. L'URL publique est définie par reverse-proxy via <span style=\"font-family:'IBM Plex Mono',monospace\">SOCLE_PUBLIC_BASE_URL</span> (non modifiable ici)."
        }
      }
      blocks[1]?.setAttribute('data-mock-id', 'branding-rail-where')
      blocks[2]?.setAttribute('data-mock-id', 'branding-rail-seealso')
      // Align « Voir aussi » with app (Administration →, Identité muted).
      const see = blocks[2]
      if (see) {
        ;[...see.querySelectorAll('a')].forEach((a) => a.remove())
        const admin = document.createElement('a')
        admin.href = 'TagsAdmin.dc.html'
        admin.textContent = 'Administration →'
        admin.setAttribute('style', 'display: block; font-size: 13px; color: #3730E0; font-weight: 500; padding: 4px 0;')
        const sso = document.createElement('span')
        sso.textContent = 'Identité & SSO →'
        sso.setAttribute('style', 'display: block; font-size: 13px; color: #9B9BA1; font-weight: 500; padding: 4px 0;')
        see.appendChild(admin)
        see.appendChild(sso)
      }
    }

    void sectionLabels
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

/**
 * Annotate Approval.dc.html : root > [topbar, body > [main > colonne, rail]].
 * colonne > [badge, h1, p, circuit, libellé, champ, actions] ; rail > [demandeur, soumis, échéance, modifs, liens].
 */
export async function annotateApprovalMockup(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    if (!root) return
    const set = (el, id) => el?.setAttribute('data-mock-id', id)
    const [topbar, body] = [...root.children]
    set(topbar, 'appr-topbar')
    set(topbar?.children[0]?.lastElementChild, 'appr-breadcrumb-current')
    set(topbar?.children[1], 'appr-comments')
    const col = body?.children[0]?.children[0]
    const [badge, h1, lede, circuit, justLabel, field, actions] = [...(col?.children ?? [])]
    set(badge, 'appr-badge')
    set(h1, 'appr-title')
    set(lede, 'appr-lede')
    set(circuit, 'appr-circuit')
    set(circuit?.children[0], 'appr-circuit-label')
    const steps = [...(circuit?.children[1]?.children ?? [])].filter((_, i) => i % 2 === 0)
    steps.forEach((step, i) => {
      const p = i === 2 ? 'appr-publication' : `appr-step-${i + 1}`
      if (i < 2) set(step.children[0], `${p}-node`)
      set(step.children[1], `${p}-name`)
      set(step.children[2], `${p}-status`)
    })
    set(justLabel, 'appr-justif-label')
    set(field, 'appr-field')
    set(actions?.children[0], 'appr-approve')
    set(actions?.children[1], 'appr-reject')
    const rail = body?.children[1]
    set(rail, 'appr-rail')
    const [requester, submitted, sla, changes, links] = [...(rail?.children ?? [])]
    set(requester?.children[0], 'appr-rail-label-requester')
    set(requester?.children[1]?.children[0], 'appr-requester-avatar')
    set(requester?.children[1]?.children[1], 'appr-requester-name')
    set(submitted?.children[0], 'appr-rail-label-submitted')
    set(submitted?.children[1], 'appr-submitted')
    set(sla?.children[0], 'appr-rail-label-sla')
    set(sla?.children[1], 'appr-sla')
    set(changes?.children[0], 'appr-rail-label-changes')
    set(changes?.children[1], 'appr-compare-link')
    set(changes?.children[1]?.children[0], 'appr-compare-label')
    set(changes?.children[1]?.children[1], 'appr-compare-counts')
    set(links?.children[0], 'appr-rail-label-links')
    ;[...(links?.children[1]?.children ?? [])].forEach((a, i) => set(a, `appr-link-${i}`))
  })
}

/** Annotate DiffApproval.dc.html : root > [topbar, controls, body > box > (.hunk, .diffline…)]. */
export async function annotateDiffApprovalMockup(page) {
  await page.evaluate(() => {
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    if (!root) return
    const set = (el, id) => el?.setAttribute('data-mock-id', id)
    const [topbar, controls, body] = [...root.children]
    set(topbar, 'adiff-topbar')
    set(topbar?.children[0]?.lastElementChild, 'adiff-breadcrumb-current')
    set(topbar?.children[1]?.children[0], 'adiff-back')
    set(topbar?.children[1]?.children[1], 'adiff-approve')
    set(controls, 'adiff-controls')
    set(controls?.children[0], 'adiff-sel-from')
    set(controls?.children[2], 'adiff-sel-to')
    set(controls?.children[3], 'adiff-added')
    set(controls?.children[4], 'adiff-removed')
    set(controls?.children[5], 'adiff-badge')
    const box = body?.children[0]
    set(box, 'diff-box')
    box?.querySelectorAll('.hunk > .cell').forEach((cell, i) => set(cell, `diff-hunk-${i}`))
  })
}

/** Annotate MobileApproval.dc.html : root > [topbar, défilement > (badge, h1, sous-titre, chaîne, lien, citation), barre]. */
export async function annotateMobileApprovalMockup(page) {
  await page.evaluate(() => {
    const root = Array.from(document.querySelectorAll('div')).find((d) =>
      (d.getAttribute('style') || '').includes('390px'),
    )
    if (!root) return
    const set = (el, id) => el?.setAttribute('data-mock-id', id)
    set(root.children[0]?.children[1], 'appr-mobile-title')
    const [badge, h1, sub, chain, diff, quote] = [...(root.children[1]?.children ?? [])]
    set(badge, 'appr-m-badge')
    set(h1, 'appr-m-title')
    set(sub, 'appr-m-sub')
    set(chain, 'appr-m-chain')
    set(chain?.children[0], 'appr-m-chain-label')
    ;[...(chain?.children[1]?.children ?? [])].forEach((row, i) => set(row.children[1], `appr-m-step-${i + 1}`))
    set(diff, 'appr-m-diff-link')
    set(diff?.children[0], 'appr-m-diff-text')
    set(quote, 'appr-m-quote')
    set(quote?.children[0], 'appr-m-quote-text')
    const bar = root.children[2]
    set(bar, 'appr-m-bar')
    set(bar?.children[0], 'appr-m-reject')
    set(bar?.children[1], 'appr-m-approve')
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
          id.endsWith('-cta') ||
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
    /** Texte avec espaces entre nœuds (React omet les whitespace text nodes des maquettes HTML). */
    function spacedText(el) {
      const parts = []
      const walk = (node) => {
        if (node.nodeType === Node.TEXT_NODE) {
          const t = normText(node.textContent)
          if (t) parts.push(t)
          return
        }
        if (node.nodeType !== Node.ELEMENT_NODE) return
        const tag = node.tagName
        if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'SVG') return
        // Badges « Bientôt » (et autres) : exclus du texte structural, pas des libellés/ordre.
        if (node.hasAttribute('data-visual-ignore')) return
        for (const c of node.childNodes) walk(c)
      }
      walk(el)
      return parts.join(' ')
    }
    function visibleText(el) {
      if (el.hasAttribute?.('data-visual-ignore')) return ''
      return spacedText(el)
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
      const useSpaced =
        id.startsWith('admin-') ||
        id.startsWith('tags-') ||
        id.startsWith('custom-fields-') ||
        id.startsWith('retention-') ||
        id.startsWith('branding-') ||
        id.startsWith('account-')
      out[id] = {
        text: useSpaced
          ? visibleText(el)
          : (() => {
              // Même exclusion data-visual-ignore pour le textContent agrégé.
              if (el.querySelector?.('[data-visual-ignore]')) return visibleText(el)
              return normText(el.textContent)
            })(),
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
      // skip 'box' = toute la boîte ; skip 'box.y' / 'box.height' = cascade / Δ hauteur déclarés
      for (const k of ['x', 'y', 'width', 'height']) {
        if (skip.has('box') || skip.has(`box.${k}`)) continue
        const d = Math.abs(mock.box[k] - app.box[k])
        if (d > 3) {
          diffs.push(`box.${k}: maquette=${mock.box[k].toFixed(1)} app=${app.box[k].toFixed(1)} Δ=${d.toFixed(1)}`)
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
