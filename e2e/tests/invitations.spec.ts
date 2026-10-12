import { test, expect } from '@playwright/test'
import { fetchPasswordToken } from '../helpers/auth'
import { apiJson, type MeResponse } from '../helpers/api'

const ADMIN = process.env.E2E_USER_A ?? 'contributeur'
const ADMIN_PASS = process.env.E2E_PASS_A ?? 'contributeur'
const INVITEE = 'invitee'
const INVITEE_PASS = 'invitee'
const INVITEE_EMAIL = 'invitee@example.com'
const apiBase = process.env.API_BASE_URL ?? process.env.BASE_URL ?? 'http://127.0.0.1'
const mailpit = process.env.MAILPIT_URL ?? 'http://127.0.0.1:8025'

type Group = { id: string; name: string }
type Invitation = { id: string; email: string }

async function lastInviteLink(): Promise<string> {
  for (let i = 0; i < 40; i++) {
    const res = await fetch(`${mailpit}/api/v1/messages`)
    if (res.ok) {
      const body = (await res.json()) as { messages?: { ID: string }[] }
      const id = body.messages?.[0]?.ID
      if (id) {
        const msg = await fetch(`${mailpit}/api/v1/message/${id}`)
        const full = (await msg.json()) as { Text?: string; HTML?: string }
        const blob = `${full.Text ?? ''}\n${full.HTML ?? ''}`
        const m = blob.match(/https?:\/\/[^\s"'<>]+\/invitations\/accept\?token=[A-Za-z0-9_-]+/)
        if (m) return m[0]
      }
    }
    await new Promise((r) => setTimeout(r, 250))
  }
  throw new Error('aucun e-mail d’invitation dans Mailpit')
}

test('invitation envoyée → Mailpit → login Keycloak → compte + équipe', async ({ page }) => {
  const jwt = await fetchPasswordToken(ADMIN, ADMIN_PASS)
  const team = await apiJson<Group>('/api/v1/groups', jwt, {
    method: 'POST',
    body: JSON.stringify({ name: `E2E Invite ${Date.now()}` }),
  })
  await apiJson<Invitation>('/api/v1/admin/invitations', jwt, {
    method: 'POST',
    body: JSON.stringify({
      email: INVITEE_EMAIL,
      teamId: team.id,
      teamRole: 'lecteur',
      globalRole: 'CONTRIBUTEUR',
    }),
  })

  const link = await lastInviteLink()
  await page.goto(link)
  await expect(page.getByRole('heading', { name: /Rejoindre|Invitation/i })).toBeVisible()
  await page.getByRole('button', { name: /Se connecter/i }).click()

  const onIdp = /realms\/socle|\/protocol\/openid-connect/
  await page.waitForURL((url) => onIdp.test(url.href) || url.pathname === '/login', { timeout: 60_000 })
  if (!onIdp.test(page.url())) {
    await page.getByRole('button', { name: /continuer avec le sso/i }).click()
    await page.waitForURL(onIdp, { timeout: 60_000 })
  }
  await page.locator('#username, input[name="username"]').fill(INVITEE)
  await page.locator('#password, input[name="password"]').fill(INVITEE_PASS)
  await page.locator('#kc-login, button[type="submit"], input[type="submit"]').first().click()

  await page.waitForURL(
    (url) =>
      (url.hostname === '127.0.0.1' || url.hostname === 'localhost') &&
      !url.pathname.includes('/protocol/openid-connect') &&
      url.pathname !== '/callback',
    { timeout: 90_000 },
  )

  const inviteeJwt = await fetchPasswordToken(INVITEE, INVITEE_PASS)
  const me = await apiJson<MeResponse>('/api/v1/me', inviteeJwt)
  expect(me.email.toLowerCase()).toBe(INVITEE_EMAIL)

  const groups = await apiJson<Group[]>(`/api/v1/groups`, inviteeJwt)
  const joined = groups.find((g) => g.id === team.id)
  expect(joined).toBeTruthy()

  const health = await fetch(`${apiBase}/actuator/health`)
  expect(health.ok).toBeTruthy()
})
