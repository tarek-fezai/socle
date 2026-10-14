import { test, expect, type Page } from '@playwright/test'
import { fetchPasswordToken } from '../helpers/auth'
import { apiJson, type MeResponse } from '../helpers/api'
import { createGuest, deleteGuest, type Guest } from '../helpers/keycloak-admin'

/** Pas de retry : un compte Socle déjà créé pour cet e-mail doit échouer dans le test. */
test.describe.configure({ retries: 0, mode: 'serial' })

const ADMIN = process.env.E2E_USER_A ?? 'contributeur'
const ADMIN_PASS = process.env.E2E_PASS_A ?? 'contributeur'
const apiBase = process.env.API_BASE_URL ?? process.env.BASE_URL ?? 'http://127.0.0.1'
const mailpit = process.env.MAILPIT_URL ?? 'http://127.0.0.1:8025'

type Group = { id: string; name: string }
type Invitation = { id: string; email: string }
type MailAddress = { Address?: string }
type MailSummary = { ID: string; Created?: string; To?: MailAddress[] }

async function clearMailpit(): Promise<void> {
  const res = await fetch(`${mailpit}/api/v1/messages`, { method: 'DELETE' })
  if (!res.ok) {
    throw new Error(`DELETE Mailpit /api/v1/messages → ${res.status}: ${await res.text()}`)
  }
  for (let i = 0; i < 20; i++) {
    const listed = await fetch(`${mailpit}/api/v1/messages`)
    if (listed.ok) {
      const body = (await listed.json()) as { total?: number; messages?: unknown[] }
      if ((body.total ?? body.messages?.length ?? 0) === 0) return
    }
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  throw new Error('Mailpit n’est pas vide après DELETE /api/v1/messages')
}

async function assertInviteeAbsent(adminJwt: string, email: string): Promise<void> {
  const listed = await apiJson<{ users: { email: string }[] }>(
    `/api/v1/admin/users?q=${encodeURIComponent(email)}`,
    adminJwt,
  )
  const exists = listed.users.some((user) => user.email.toLowerCase() === email.toLowerCase())
  if (exists) {
    throw new Error(
      `Le compte Socle ${email} existe déjà depuis un passage précédent. Le test ne contourne pas cet état.`,
    )
  }
}

async function revokePendingForInvitee(adminJwt: string, email: string): Promise<void> {
  const pending = await apiJson<Invitation[]>('/api/v1/admin/invitations', adminJwt)
  for (const invitation of pending) {
    if (invitation.email.toLowerCase() !== email.toLowerCase()) continue
    await apiJson<void>(`/api/v1/admin/invitations/${invitation.id}/revoke`, adminJwt, {
      method: 'POST',
    })
  }
}

async function inviteLinkAfter(email: string, sentAfter: Date): Promise<string> {
  const target = email.toLowerCase()
  const earliest = sentAfter.getTime() - 2_000
  for (let i = 0; i < 80; i++) {
    const res = await fetch(`${mailpit}/api/v1/messages`)
    if (res.ok) {
      const body = (await res.json()) as { messages?: MailSummary[] }
      const matches = (body.messages ?? []).filter((msg) => {
        const created = Date.parse(msg.Created ?? '')
        if (!Number.isFinite(created) || created < earliest) return false
        return (msg.To ?? []).some((to) => (to.Address ?? '').toLowerCase() === target)
      })
      matches.sort((a, b) => Date.parse(b.Created ?? '') - Date.parse(a.Created ?? ''))
      for (const summary of matches) {
        const msg = await fetch(`${mailpit}/api/v1/message/${summary.ID}`)
        const full = (await msg.json()) as { Text?: string; HTML?: string }
        const blob = `${full.Text ?? ''}\n${full.HTML ?? ''}`
        const found = blob.match(/https?:\/\/[^\s"'<>]+\/invitations\/accept\?token=[A-Za-z0-9_-]+/)
        if (found) return found[0]
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  throw new Error(`aucun e-mail d’invitation pour ${email} après ${sentAfter.toISOString()}`)
}

async function loginAtIdp(page: Page, guest: Guest): Promise<void> {
  const onIdp = /realms\/socle|\/protocol\/openid-connect/
  await page.waitForURL((url) => onIdp.test(url.href) || url.pathname === '/login', { timeout: 60_000 })
  if (!onIdp.test(page.url())) {
    await page.getByRole('button', { name: /continuer avec le sso/i }).click()
    await page.waitForURL(onIdp, { timeout: 60_000 })
  }
  await page.locator('#username, input[name="username"]').fill(guest.username)
  await page.locator('#password, input[name="password"]').fill(guest.password)
  await page.locator('#kc-login, button[type="submit"], input[type="submit"]').first().click()
}

test.beforeEach(async () => {
  await clearMailpit()
})

test('invitation en attente : GET /me reste 403 not_provisioned', async () => {
  const guest = await createGuest()
  try {
    const jwt = await fetchPasswordToken(ADMIN, ADMIN_PASS)
    await assertInviteeAbsent(jwt, guest.email)
    await revokePendingForInvitee(jwt, guest.email)
    const team = await apiJson<Group>('/api/v1/groups', jwt, {
      method: 'POST',
      body: JSON.stringify({ name: `E2E Invite pending ${Date.now()}` }),
    })
    await apiJson<Invitation>('/api/v1/admin/invitations', jwt, {
      method: 'POST',
      body: JSON.stringify({
        email: guest.email,
        teamId: team.id,
        teamRole: 'lecteur',
        globalRole: 'CONTRIBUTEUR',
      }),
    })

    const inviteeJwt = await fetchPasswordToken(guest.username, guest.password)
    const res = await fetch(`${apiBase}/api/v1/me`, {
      headers: { Authorization: `Bearer ${inviteeJwt}` },
    })
    expect(res.status).toBe(403)
    const body = (await res.json()) as { reason?: string }
    expect(body.reason).toBe('not_provisioned')
  } finally {
    await deleteGuest(guest)
  }
})

test('invitation envoyée → Mailpit → login Keycloak → compte + équipe', async ({ page }) => {
  const guest = await createGuest()
  try {
    const jwt = await fetchPasswordToken(ADMIN, ADMIN_PASS)
    await assertInviteeAbsent(jwt, guest.email)
    await revokePendingForInvitee(jwt, guest.email)
    const team = await apiJson<Group>('/api/v1/groups', jwt, {
      method: 'POST',
      body: JSON.stringify({ name: `E2E Invite ${Date.now()}` }),
    })
    const sentAfter = new Date()
    await apiJson<Invitation>('/api/v1/admin/invitations', jwt, {
      method: 'POST',
      body: JSON.stringify({
        email: guest.email,
        teamId: team.id,
        teamRole: 'lecteur',
        globalRole: 'CONTRIBUTEUR',
      }),
    })

    let acceptStarted = 0
    page.on('request', (req) => {
      try {
        if (req.method() === 'POST' && new URL(req.url()).pathname === '/api/v1/invitations/accept') {
          acceptStarted = Date.now()
        }
      } catch {
        // URL non parseable : ignorée
      }
    })
    const acceptResponse = page.waitForResponse(
      (res) => {
        if (res.request().method() !== 'POST') return false
        try {
          return new URL(res.url()).pathname === '/api/v1/invitations/accept'
        } catch {
          return false
        }
      },
      { timeout: 90_000 },
    )

    const link = await inviteLinkAfter(guest.email, sentAfter)
    await page.goto(link)
    await expect(page.getByRole('heading', { name: /Rejoindre|Invitation/i })).toBeVisible()
    await page.getByRole('button', { name: /Se connecter/i }).click()
    await loginAtIdp(page, guest)

    const response = await acceptResponse
    const wall = acceptStarted > 0 ? Date.now() - acceptStarted : -1
    const timing = response.request().timing()
    const ms = timing.responseEnd >= 0 ? Math.round(timing.responseEnd) : wall
    console.log(`POST /api/v1/invitations/accept ${ms} ms status ${response.status()}`)
    test.info().annotations.push({ type: 'accept-duration-ms', description: String(ms) })
    expect(response.ok()).toBeTruthy()

    await expect(page.getByTestId('invitation-accepted')).toBeVisible({ timeout: 90_000 })
    await page.waitForURL(
      (url) =>
        (url.hostname === '127.0.0.1' || url.hostname === 'localhost') &&
        url.port !== '8081' &&
        url.pathname === '/',
      { timeout: 30_000 },
    )

    const inviteeJwt = await fetchPasswordToken(guest.username, guest.password)
    const me = await apiJson<MeResponse>('/api/v1/me', inviteeJwt)
    expect(me.email.toLowerCase()).toBe(guest.email.toLowerCase())

    const groups = await apiJson<Group[]>('/api/v1/groups', inviteeJwt)
    const joined = groups.find((group) => group.id === team.id)
    expect(joined).toBeTruthy()

    const health = await fetch(`${apiBase}/actuator/health`)
    expect(health.ok).toBeTruthy()
  } finally {
    await deleteGuest(guest)
  }
})

test('utilisateur non invité : Connexion refusée not_provisioned', async ({ page }) => {
  const guest = await createGuest()
  try {
    const jwt = await fetchPasswordToken(ADMIN, ADMIN_PASS)
    await assertInviteeAbsent(jwt, guest.email)
    // Sans invitation en attente, le JIT ouvre l’accueil. Le 403 not_provisioned
    // du callback (hors lien d’acceptation) vient de cette invitation.
    const team = await apiJson<Group>('/api/v1/groups', jwt, {
      method: 'POST',
      body: JSON.stringify({ name: `E2E Invite denied ${Date.now()}` }),
    })
    await apiJson<Invitation>('/api/v1/admin/invitations', jwt, {
      method: 'POST',
      body: JSON.stringify({
        email: guest.email,
        teamId: team.id,
        teamRole: 'lecteur',
        globalRole: 'CONTRIBUTEUR',
      }),
    })
    await page.goto('/login')
    await loginAtIdp(page, guest)
    await page.waitForURL(
      (url) =>
        (url.hostname === '127.0.0.1' || url.hostname === 'localhost') &&
        url.port !== '8081' &&
        url.pathname === '/login/erreur' &&
        url.searchParams.get('reason') === 'not_provisioned',
      { timeout: 90_000 },
    )
    expect(new URL(page.url()).pathname).not.toBe('/callback')
    await expect(page.getByRole('heading', { name: 'Connexion refusée' })).toBeVisible()
    await expect(page.getByText(/aucun compte Socle n'est associé/i)).toBeVisible()
  } finally {
    await deleteGuest(guest)
  }
})
