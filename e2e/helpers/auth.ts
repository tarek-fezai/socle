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

/**
 * Browser login via Keycloak form.
 * The SPA uses AuthProvider requireLogin, so / already redirects to the IdP.
 */
export async function loginViaUi(
  page: import('@playwright/test').Page,
  username: string,
  password: string,
): Promise<void> {
  const consoleErrors: string[] = []
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text())
  })
  page.on('pageerror', (err) => consoleErrors.push(String(err)))

  await page.goto('/')

  try {
    await page.waitForURL(/realms\/socle|\/protocol\/openid-connect/, { timeout: 60_000 })
  } catch (err) {
    const loginBtn = page.getByRole('button', { name: /se connecter|connexion|login/i })
    if (await loginBtn.isVisible().catch(() => false)) {
      await loginBtn.click()
      await page.waitForURL(/realms\/socle|\/protocol\/openid-connect/, { timeout: 60_000 })
    } else {
      throw new Error(
        `OIDC redirect failed (url=${page.url()}). Console: ${consoleErrors.join(' | ') || '(none)'}`,
        { cause: err },
      )
    }
  }

  await page.locator('#username, input[name="username"]').fill(username)
  await page.locator('#password, input[name="password"]').fill(password)
  await page.locator('#kc-login, button[type="submit"], input[type="submit"]').first().click()

  // Leave Keycloak; may land briefly on /callback then /docs.
  await page.waitForURL(
    (url) =>
      (url.hostname === '127.0.0.1' || url.hostname === 'localhost') &&
      url.port !== '8081' &&
      !url.pathname.includes('/protocol/openid-connect'),
    { timeout: 60_000 },
  )

  const logout = page.getByRole('button', { name: /déconnexion|logout/i })
  const oidcError = page.getByText(/connexion échouée|connexion oidc/i)
  await Promise.race([
    logout.waitFor({ state: 'visible', timeout: 90_000 }),
    oidcError
      .waitFor({ state: 'visible', timeout: 90_000 })
      .then(async () => {
        const text = await page.locator('main').innerText().catch(() => '')
        if (/échouée|échoue/i.test(text)) {
          throw new Error(`OIDC callback failed: ${text}. Console: ${consoleErrors.join(' | ')}`)
        }
        // Still showing "Connexion OIDC…" — keep waiting for logout.
        await logout.waitFor({ state: 'visible', timeout: 90_000 })
      }),
  ])
}
