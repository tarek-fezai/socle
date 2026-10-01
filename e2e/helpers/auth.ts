const keycloakBase = process.env.KEYCLOAK_BASE_URL ?? 'http://127.0.0.1:8081'
const realm = process.env.KEYCLOAK_REALM ?? 'socle'
const clientId = process.env.OIDC_FRONTEND_CLIENT_ID ?? 'socle-frontend'

export async function fetchPasswordToken(username: string, password: string): Promise<string> {
  const url = `${keycloakBase}/realms/${realm}/protocol/openid-connect/token`
  const body = new URLSearchParams({
    grant_type: 'password',
    client_id: clientId,
    username,
    password,
  })
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  if (!res.ok) {
    throw new Error(`Keycloak token failed ${res.status}: ${await res.text()}`)
  }
  const json = (await res.json()) as { access_token: string }
  return json.access_token
}

/** Browser login via Keycloak form (authorization code + PKCE handled by Socle SPA). */
export async function loginViaUi(
  page: import('@playwright/test').Page,
  username: string,
  password: string,
): Promise<void> {
  await page.goto('/')
  const loginBtn = page.getByRole('button', { name: /connexion|login/i })
  if (await loginBtn.isVisible().catch(() => false)) {
    await loginBtn.click()
  }
  await page.waitForURL(/realms\/socle|\/protocol\/openid-connect/)
  await page.fill('#username', username)
  await page.fill('#password', password)
  await page.click('input[type="submit"], button[type="submit"]')
  await page.waitForURL(/127\.0\.0\.1|localhost/)
}
