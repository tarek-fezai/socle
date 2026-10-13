// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Annotations maquette (data-* uniquement) + routes API des specs Access / Settings / Trash / Export / CreateSpace.
 */
import { ME_TAREK, NOTIFICATIONS_SEED, SPACE_IDENTITE, TREE_IDENTITE } from './dashboard-fixtures.mjs'
import { SPACES_SEED } from './five-screens-fixtures.mjs'
import { json, mockBaseApis } from './visual-parity-kit.mjs'
import { PAGE_COMMENTS } from './page-fixtures.mjs'
import {
  ACCESS_DOC,
  ACCESS_DOC_SEED,
  EXPORT_FOLDER_ID,
  EXPORT_TAG_ID,
  FOLDER_PROCEDURES,
  PAGE_DOC_ID,
  SPACE_IDENTITE_SETTINGS,
  SPACE_OWNERS_SEED,
  TAG_IAM,
  TRASH_SEED,
} from './parity-screens-fixtures.mjs'

export async function mockParityApis(page) {
  await mockBaseApis(page, { me: ME_TAREK, notifications: NOTIFICATIONS_SEED, spaces: SPACES_SEED })
  await page.route('**/api/v1/trash**', (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    return json(route, TRASH_SEED)
  })
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/tree**`, (route) => json(route, TREE_IDENTITE))
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}/owners`, (route) => json(route, SPACE_OWNERS_SEED))
  await page.route(`**/api/v1/spaces/${SPACE_IDENTITE.id}`, (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    return json(route, SPACE_IDENTITE_SETTINGS)
  })
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}`, (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    return json(route, ACCESS_DOC)
  })
  await page.route(`**/api/v1/access/document/${PAGE_DOC_ID}`, (route) => json(route, ACCESS_DOC_SEED))
  await page.route(`**/api/v1/documents/${PAGE_DOC_ID}/comments**`, (route) => json(route, PAGE_COMMENTS))
  await page.route(`**/api/v1/folders/${EXPORT_FOLDER_ID}`, (route) => json(route, FOLDER_PROCEDURES))
  await page.route(`**/api/v1/tags/${EXPORT_TAG_ID}`, (route) => json(route, TAG_IAM))
  await page.route('**/api/v1/groups**', (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    return json(route, [])
  })
}

export async function annotateTeamMockup(page) {
  await page.evaluate(() => {
    const h1 = document.querySelector('h1')
    h1?.setAttribute('data-mock-id', 'team-title')
    const lead = h1?.nextElementSibling
    lead?.setAttribute('data-mock-id', 'team-lead')
    const invite = [...document.querySelectorAll('a')].find((a) =>
      (a.textContent ?? '').includes('Inviter un membre'),
    )
    invite?.setAttribute('data-mock-id', 'team-invite-cta')
    const segs = [...document.querySelectorAll('a.seg')]
    const exact = (label) =>
      [...document.querySelectorAll('a')].find((a) => {
        const t = (a.textContent ?? '').replace(/\s+/g, ' ').trim()
        return t === label || t.startsWith(`${label} `)
      })
    const membres = segs[0] || exact('Membres')
    const equipes = segs[1] || exact('Équipes')
    const invitations = segs[2] || exact('Invitations')
    membres?.setAttribute('data-mock-id', 'team-tab-membres')
    equipes?.setAttribute('data-mock-id', 'team-tab-equipes')
    invitations?.setAttribute('data-mock-id', 'team-tab-invitations')
    const table = [...document.querySelectorAll('div')].find(
      (d) =>
        Boolean(d.style?.textTransform === 'uppercase') &&
        (d.textContent ?? '').includes('Rôle global') &&
        (d.textContent ?? '').includes('Dernière activité'),
    )
    table?.setAttribute('data-mock-id', 'team-members-head')
  })
}

export async function annotateTrashMockup(page) {
  await page.evaluate(() => {
    const h1 = document.querySelector('h1')
    h1?.setAttribute('data-mock-id', 'trash-title')
    const lead = h1?.nextElementSibling
    lead?.setAttribute('data-mock-id', 'trash-lead')
    const list = lead?.nextElementSibling
    list?.setAttribute('data-mock-id', 'trash-list')
    ;[...(list?.children ?? [])].forEach((row, i) => {
      row.setAttribute('data-mock-id', `trash-row-${i}`)
      const body = row.children[0]
      body?.children[0]?.setAttribute('data-mock-id', `trash-row-${i}-title`)
      body?.children[1]?.setAttribute('data-mock-id', `trash-row-${i}-meta`)
      row.children[1]?.setAttribute('data-mock-id', `trash-row-${i}-purge`)
      const actions = row.children[2]
      actions?.children[0]?.setAttribute('data-mock-id', `trash-row-${i}-restore`)
      actions?.children[1]?.setAttribute('data-mock-id', `trash-row-${i}-delete`)
    })
    const footer = list?.nextElementSibling
    footer?.setAttribute('data-mock-id', 'trash-footer')
    const topbar = document.querySelector('div[style*="height: 60px"]')
    topbar?.setAttribute('data-visual-mask', 'trash-space-chrome')
  })
}

export async function annotateAccessMockup(page) {
  await page.evaluate(() => {
    const h1 = document.querySelector('h1')
    h1?.setAttribute('data-mock-id', 'access-title')
    const visHead = h1?.nextElementSibling
    visHead?.children[0]?.setAttribute('data-mock-id', 'access-vis-label')
    const visList = visHead?.nextElementSibling
    ;['organisation', 'space', 'restricted'].forEach((id, i) => {
      visList?.children[i]?.setAttribute('data-mock-id', `access-vis-${id}`)
    })
    const peopleLabel = visList?.nextElementSibling
    peopleLabel?.setAttribute('data-mock-id', 'access-people-label')
    const people = peopleLabel?.nextElementSibling
    people?.setAttribute('data-mock-id', 'access-people-rows')
    const trashBox = people?.nextElementSibling
    trashBox?.setAttribute('data-mock-id', 'access-delete-box')
    const tabBar = document.querySelector('div')?.querySelector?.('a.tab')?.parentElement
    if (tabBar) tabBar.setAttribute('data-mock-id', 'access-doc-tabs')
    const tabs = [...document.querySelectorAll('a.tab')]
    tabs.forEach((t) => t.setAttribute('data-mock-id', t.getAttribute('data-mock-id') || 'access-doc-tab'))
    const invite = [...document.querySelectorAll('a')].find((a) => (a.textContent ?? '').includes('Inviter'))
    invite?.setAttribute('data-mock-id', 'access-invite')
  })
}

export async function annotateCreateSpaceMockup(page) {
  await page.evaluate(() => {
    const modal = [...document.querySelectorAll('div')].find(
      (d) => d.style && d.style.width === '480px' && d.querySelector('h1'),
    )
    modal?.setAttribute('data-mock-id', 'create-modal')
    modal?.querySelector('h1')?.setAttribute('data-mock-id', 'create-title')
    const close = modal?.querySelector('a[aria-label="Fermer"]')
    close?.setAttribute('data-mock-id', 'create-close')
    const labels = [...(modal?.querySelectorAll('label') ?? [])]
    labels[0]?.setAttribute('data-mock-id', 'create-name-label')
    labels[1]?.setAttribute('data-mock-id', 'create-desc-label')
    labels[2]?.setAttribute('data-mock-id', 'create-color-label')
    labels[3]?.setAttribute('data-mock-id', 'create-vis-label')
    const btns = [...(modal?.querySelectorAll('a.ghost, a.cta') ?? [])]
    const cancel = btns.find((b) => (b.textContent ?? '').trim() === 'Annuler')
    const submit = btns.find((b) => (b.textContent ?? '').includes("Créer"))
    cancel?.setAttribute('data-mock-id', 'create-cancel')
    submit?.setAttribute('data-mock-id', 'create-submit')
    labels[2]?.nextElementSibling?.setAttribute('data-mock-id', 'create-swatches')
    labels[3]?.nextElementSibling?.setAttribute('data-mock-id', 'create-vis-field')
  })
}

export async function annotateSettingsMockup(page) {
  await page.evaluate(() => {
    const col = [...document.querySelectorAll('div')].find((d) => d.style && d.style.width === '620px')
    col?.setAttribute('data-settings-col', '')
    const h1 = document.querySelector('h1')
    h1?.setAttribute('data-mock-id', 'settings-title')
    const nameBlock = h1?.nextElementSibling
    nameBlock?.querySelector('div[style*="border"]')?.setAttribute('data-mock-id', 'settings-name')
    const colorBlock = nameBlock?.nextElementSibling
    colorBlock?.setAttribute('data-mock-id', 'settings-color')
    const visBlock = colorBlock?.nextElementSibling
    visBlock?.setAttribute('data-mock-id', 'settings-visibility')
    const refsBlock = visBlock?.nextElementSibling
    refsBlock?.setAttribute('data-mock-id', 'settings-refs')
    const membersBlock = refsBlock?.nextElementSibling
    membersBlock?.setAttribute('data-visual-mask', 'settings-members')
    const audit = membersBlock?.nextElementSibling
    audit?.setAttribute('data-mock-id', 'settings-audit')
    const trash = audit?.nextElementSibling
    trash?.setAttribute('data-mock-id', 'settings-trash')
    const archive = trash?.nextElementSibling
    archive?.setAttribute('data-visual-mask', 'settings-archive')
  })
}

export async function annotateExportMockup(page, { crumbCurrent, maskZipFormats = false } = {}) {
  await page.evaluate(({ crumbCurrent, maskZipFormats }) => {
    const topbar = document.querySelector('div[style*="height: 60px"]')
    topbar?.setAttribute('data-mock-id', 'export-topbar')
    const crumb = topbar?.children[0]
    crumb?.setAttribute('data-mock-id', 'export-crumb')
    const last = crumb?.querySelector('span[style*="font-weight: 500"]')
    if (crumbCurrent && last) last.textContent = last.textContent
    topbar?.children[1]?.setAttribute('data-mock-id', 'export-close')
    const preview = topbar?.nextElementSibling?.children[0]
    preview?.setAttribute('data-visual-mask', 'export-preview')
    const opts = topbar?.nextElementSibling?.children[1]
    opts?.setAttribute('data-mock-id', 'export-options')
    const download = [...(opts?.querySelectorAll('a, button') ?? [])].find((el) =>
      (el.textContent ?? '').includes('Télécharger'),
    )
    download?.setAttribute('data-mock-id', 'export-download')
    const formatHeading = [...(opts?.querySelectorAll('div') ?? [])].find(
      (el) => (el.textContent ?? '').trim() === 'Format',
    )
    const formatList = formatHeading?.nextElementSibling
    const pdf =
      formatList?.querySelector('.fmt.sel') ||
      formatList?.children[0] ||
      [...(opts?.querySelectorAll('div') ?? [])].find(
        (el) => (el.textContent ?? '').trim().startsWith('PDF') && el.children.length === 2,
      )
    pdf?.setAttribute('data-mock-id', 'export-format-pdf')
    if (maskZipFormats) {
      download?.setAttribute('data-visual-mask', 'export-download')
      pdf?.setAttribute('data-visual-mask', 'export-format-pdf')
    }
  }, { crumbCurrent, maskZipFormats })
}

export async function annotateTeamInvitationsMockup(page) {
  await page.evaluate(() => {
    const invite = [...document.querySelectorAll('a')].find((a) =>
      (a.textContent ?? '').includes('Inviter un membre'),
    )
    invite?.setAttribute('data-mock-id', 'team-invite-cta')
    const segs = [...document.querySelectorAll('a.seg')]
    segs[0]?.setAttribute('data-mock-id', 'team-tab-membres')
    segs[1]?.setAttribute('data-mock-id', 'team-tab-equipes')
    segs[2]?.setAttribute('data-mock-id', 'team-tab-invitations')
    const table = [...document.querySelectorAll('div')].find(
      (d) =>
        Boolean(d.style?.textTransform === 'uppercase') &&
        (d.textContent ?? '').includes('Invité') &&
        (d.textContent ?? '').includes('Envoyée par'),
    )
    table?.setAttribute('data-mock-id', 'invitations-head')
    let row = table?.nextElementSibling
    let i = 0
    while (row && row.classList.contains('row')) {
      row.setAttribute('data-mock-id', `invitations-row-${i}`)
      row.querySelector('.mono')?.style.setProperty('font-family', "'IBM Plex Mono', monospace")
      row = row.nextElementSibling
      i += 1
    }
  })
}

export async function annotateInviteOrgMemberMockup(page) {
  await page.evaluate(() => {
    const modal = [...document.querySelectorAll('div')].find(
      (d) => (d.querySelector('h1')?.textContent ?? '').trim() === 'Inviter un membre',
    )
    modal?.setAttribute('data-mock-id', 'invite-modal')
    const h1 = modal?.querySelector('h1')
    h1?.setAttribute('data-mock-id', 'invite-title')
    const close = modal?.querySelector('a[aria-label="Fermer"]')
    close?.setAttribute('data-mock-id', 'invite-close')
    const lead = h1?.parentElement?.nextElementSibling
    lead?.setAttribute('data-mock-id', 'invite-lead')
    const labels = [...(modal?.querySelectorAll('div') ?? [])].filter(
      (d) => d.style?.textTransform === 'uppercase',
    )
    labels[0]?.setAttribute('data-mock-id', 'invite-email-label')
    const emailField = labels[0]?.nextElementSibling
    emailField?.setAttribute('data-mock-id', 'invite-email-field')
    labels[1]?.setAttribute('data-mock-id', 'invite-team-label')
    labels[1]?.nextElementSibling?.setAttribute('data-mock-id', 'invite-teams')
    labels[2]?.setAttribute('data-mock-id', 'invite-team-role-label')
    labels[2]?.nextElementSibling?.setAttribute('data-mock-id', 'invite-team-roles')
    labels[3]?.setAttribute('data-mock-id', 'invite-global-label')
    labels[3]?.nextElementSibling?.setAttribute('data-mock-id', 'invite-global-roles')
    const cancel = [...(modal?.querySelectorAll('a') ?? [])].find((a) =>
      (a.textContent ?? '').trim() === 'Annuler',
    )
    cancel?.setAttribute('data-mock-id', 'invite-cancel')
    const submit = [...(modal?.querySelectorAll('a') ?? [])].find((a) =>
      (a.textContent ?? '').includes("Envoyer l'invitation"),
    )
    submit?.setAttribute('data-mock-id', 'invite-submit')
    const ready = [...(modal?.querySelectorAll('span') ?? [])].find((s) =>
      (s.textContent ?? '').includes('invitation prête'),
    )
    ready?.setAttribute('data-mock-id', 'invite-ready')
  })
}
