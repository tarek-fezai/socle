import { randomBytes } from 'node:crypto'

const keycloakBase = process.env.KEYCLOAK_BASE_URL ?? 'http://127.0.0.1:8081'
const realm = process.env.KEYCLOAK_REALM ?? 'socle'

export type Guest = { id: string; username: string; email: string; password: string }

function adminEnv(): { user: string; password: string } {
  const user = process.env.KEYCLOAK_ADMIN?.trim()
  const password = process.env.KEYCLOAK_ADMIN_PASSWORD
  if (!user || !password) {
    throw new Error(
      'KEYCLOAK_ADMIN et KEYCLOAK_ADMIN_PASSWORD sont requis (bootstrap du Keycloak e2e).',
    )
  }
  return { user, password }
}

function redact(text: string, secrets: string[]): string {
  let out = text
  for (const secret of secrets) {
    if (secret) out = out.split(secret).join('[redacted]')
  }
  return out.slice(0, 240)
}

async function adminToken(secrets: string[]): Promise<string> {
  const { user, password } = adminEnv()
  secrets.push(password)
  const res = await fetch(`${keycloakBase}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: 'admin-cli',
      username: user,
      password,
    }),
  })
  const text = await res.text()
  if (!res.ok) {
    throw new Error(`Keycloak admin token ${res.status}: ${redact(text, secrets)}`)
  }
  const json = JSON.parse(text) as { access_token?: string }
  if (!json.access_token) throw new Error('Keycloak admin token absent')
  secrets.push(json.access_token)
  return json.access_token
}

/** Utilisateur du realm e2e, e-mail unique, mot de passe aléatoire non journalisé. */
export async function createGuest(): Promise<Guest> {
  const stamp = Date.now().toString(36)
  const rand = randomBytes(4).toString('hex')
  const username = `invitee-${stamp}-${rand}`
  const email = `${username}@example.com`
  const password = randomBytes(18).toString('base64url')
  const secrets = [password]
  const token = await adminToken(secrets)
  const res = await fetch(`${keycloakBase}/admin/realms/${realm}/users`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username,
      email,
      emailVerified: true,
      enabled: true,
      firstName: 'Invite',
      lastName: stamp,
      credentials: [{ type: 'password', value: password, temporary: false }],
    }),
  })
  if (res.status !== 201) {
    throw new Error(`création invité Keycloak ${res.status}: ${redact(await res.text(), secrets)}`)
  }
  const id = (res.headers.get('location') ?? '').split('/').filter(Boolean).pop()
  if (!id) throw new Error('création invité Keycloak sans identifiant')
  return { id, username, email, password }
}

export async function deleteGuest(guest: Guest | null): Promise<void> {
  if (!guest) return
  const secrets = [guest.password]
  const token = await adminToken(secrets)
  const res = await fetch(`${keycloakBase}/admin/realms/${realm}/users/${encodeURIComponent(guest.id)}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${token}` },
  })
  if (res.status !== 204 && res.status !== 404) {
    throw new Error(`suppression invité Keycloak ${res.status}: ${redact(await res.text(), secrets)}`)
  }
}
