import { execFileSync } from 'node:child_process'
import { test, expect } from '@playwright/test'
import { fetchPasswordToken } from '../helpers/auth'
import { apiJson, type MeResponse } from '../helpers/api'

/**
 * Pas de retry Playwright : un second passage sur un compte déjà créé
 * doit échouer clairement dans beforeEach, pas en 409 avalé.
 */
test.describe.configure({ retries: 0, mode: 'serial' })

const ADMIN = process.env.E2E_USER_A ?? 'contributeur'
const ADMIN_PASS = process.env.E2E_PASS_A ?? 'contributeur'
const INVITEE = 'invitee'
const INVITEE_PASS = 'invitee'
const INVITEE_EMAIL = 'invitee@example.com'
const apiBase = process.env.API_BASE_URL ?? process.env.BASE_URL ?? 'http://127.0.0.1'
const mailpit = process.env.MAILPIT_URL ?? 'http://127.0.0.1:8025'

type Group = { id: string; name: string }
type Invitation = { id: string; email: string }
type MailAddress = { Address?: string }
type MailSummary = { ID: string; Created?: string; To?: MailAddress[] }

let removeInviteeAfterPass = false

function soclePostgresContainer(): string {
  const out = execFileSync('docker', ['ps', '--format', '{{.Names}}'], { encoding: 'utf8' })
  const names = out
    .split(/\r?\n/)
    .map((name) => name.trim())
    .filter(Boolean)
  const found = names.find((name) => name.includes('postgres') && name.includes('socle'))
  if (!found) {
    throw new Error(`conteneur postgres Socle introuvable parmi : ${names.join(', ') || '(aucun)'}`)
  }
  return found
}

function postgresUser(container: string): string {
  const fromEnv = process.env.POSTGRES_USER?.trim()
  if (fromEnv) return fromEnv
  return execFileSync('docker', ['exec', container, 'printenv', 'POSTGRES_USER'], {
    encoding: 'utf8',
  }).trim()
}

/** Retire le compte créé par un passage réussi, pour que le suivant reparte sans compte. */
function removeInviteeAccount(): void {
  const container = soclePostgresContainer()
  const user = postgresUser(container)
  const sql = `
DO $$
DECLARE uid uuid;
BEGIN
  SELECT id INTO uid FROM users WHERE lower(email) = 'invitee@example.com';
  IF uid IS NULL THEN
    RETURN;
  END IF;
  UPDATE audit_log_events SET actor_id = NULL WHERE actor_id = uid;
  UPDATE invitations SET accepted_user_id = NULL WHERE accepted_user_id = uid;
  UPDATE user_platform_roles SET granted_by = NULL WHERE granted_by = uid;
  DELETE FROM activity_events WHERE actor_user_id = uid;
  DELETE FROM users WHERE id = uid;
END $$;
`
  execFileSync(
    'docker',
    ['exec', container, 'psql', '-U', user, '-d', 'socle_core', '-v', 'ON_ERROR_STOP=1', '-c', sql],
    { encoding: 'utf8' },
  )
}

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

async function assertInviteeAbsent(adminJwt: string): Promise<void> {
  const listed = await apiJson<{ users: { email: string }[] }>(
    `/api/v1/admin/users?q=${encodeURIComponent(INVITEE_EMAIL)}`,
    adminJwt,
  )
  const exists = listed.users.some((user) => user.email.toLowerCase() === INVITEE_EMAIL)
  if (exists) {
    throw new Error(
      'Le compte Socle invitee@example.com existe déjà depuis un passage précédent. Le test ne contourne pas cet état.',
    )
  }
}

async function revokePendingForInvitee(adminJwt: string): Promise<void> {
  const pending = await apiJson<Invitation[]>('/api/v1/admin/invitations', adminJwt)
  for (const invitation of pending) {
    if (invitation.email.toLowerCase() !== INVITEE_EMAIL) continue
    await apiJson<void>(`/api/v1/admin/invitations/${invitation.id}/revoke`, adminJwt, {
      method: 'POST',
    })
  }
}

async function inviteLinkAfter(sentAfter: Date): Promise<string> {
  const target = INVITEE_EMAIL.toLowerCase()
  // Marge d’horloge entre le runner et Mailpit ; la boîte a été vidée avant l’envoi.
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
  throw new Error(`aucun e-mail d’invitation pour ${INVITEE_EMAIL} après ${sentAfter.toISOString()}`)
}

test.beforeEach(async () => {
  removeInviteeAfterPass = false
  await clearMailpit()
  const adminJwt = await fetchPasswordToken(ADMIN, ADMIN_PASS)
  await assertInviteeAbsent(adminJwt)
  await revokePendingForInvitee(adminJwt)
})

test.afterEach(() => {
  if (!removeInviteeAfterPass) return
  removeInviteeAfterPass = false
  removeInviteeAccount()
})

test('invitation en attente : GET /me reste 403 not_provisioned', async () => {
  const jwt = await fetchPasswordToken(ADMIN, ADMIN_PASS)
  const team = await apiJson<Group>('/api/v1/groups', jwt, {
    method: 'POST',
    body: JSON.stringify({ name: `E2E Invite pending ${Date.now()}` }),
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

  const inviteeJwt = await fetchPasswordToken(INVITEE, INVITEE_PASS)
  const res = await fetch(`${apiBase}/api/v1/me`, {
    headers: { Authorization: `Bearer ${inviteeJwt}` },
  })
  expect(res.status).toBe(403)
  const body = (await res.json()) as { reason?: string }
  expect(body.reason).toBe('not_provisioned')
})

test('invitation envoyée → Mailpit → login Keycloak → compte + équipe', async ({ page }) => {
  const jwt = await fetchPasswordToken(ADMIN, ADMIN_PASS)
  const team = await apiJson<Group>('/api/v1/groups', jwt, {
    method: 'POST',
    body: JSON.stringify({ name: `E2E Invite ${Date.now()}` }),
  })
  const sentAfter = new Date()
  await apiJson<Invitation>('/api/v1/admin/invitations', jwt, {
    method: 'POST',
    body: JSON.stringify({
      email: INVITEE_EMAIL,
      teamId: team.id,
      teamRole: 'lecteur',
      globalRole: 'CONTRIBUTEUR',
    }),
  })

  const link = await inviteLinkAfter(sentAfter)
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

  await expect(page.getByTestId('invitation-accepted')).toBeVisible({ timeout: 90_000 })
  await page.waitForURL(
    (url) =>
      (url.hostname === '127.0.0.1' || url.hostname === 'localhost') &&
      url.port !== '8081' &&
      url.pathname === '/',
    { timeout: 30_000 },
  )

  const inviteeJwt = await fetchPasswordToken(INVITEE, INVITEE_PASS)
  const me = await apiJson<MeResponse>('/api/v1/me', inviteeJwt)
  expect(me.email.toLowerCase()).toBe(INVITEE_EMAIL)

  const groups = await apiJson<Group[]>('/api/v1/groups', inviteeJwt)
  const joined = groups.find((group) => group.id === team.id)
  expect(joined).toBeTruthy()

  const health = await fetch(`${apiBase}/actuator/health`)
  expect(health.ok).toBeTruthy()
  removeInviteeAfterPass = true
})
