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
 * Unauthenticated routes land on /login; "Se connecter" triggers the IdP redirect.
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

  const onIdp = /realms\/socle|\/protocol\/openid-connect/
  try {
    await page.waitForURL((url) => onIdp.test(url.href) || url.pathname === '/login', {
      timeout: 60_000,
    })
  } catch (err) {
    throw new Error(
      `Neither /login nor IdP reached (url=${page.url()}). Console: ${consoleErrors.join(' | ') || '(none)'}`,
      { cause: err },
    )
  }

  if (!onIdp.test(page.url())) {
    const loginBtn = page.getByRole('button', { name: /se connecter/i })
    await loginBtn.waitFor({ state: 'visible', timeout: 15_000 })
    await loginBtn.click()
    try {
      await page.waitForURL(onIdp, { timeout: 60_000 })
    } catch (err) {
      throw new Error(
        `OIDC redirect failed after /login (url=${page.url()}). Console: ${consoleErrors.join(' | ') || '(none)'}`,
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

  // AppShell exposes the signed-in user control; legacy AppNav used a "Déconnexion" button.
  const signedIn = page.locator(
    '[data-mock-id="shell-user"], button:has-text("Déconnexion"), button:has-text("Logout")',
  )
  const oidcError = page.getByText(/connexion échouée|connexion oidc/i)
  await Promise.race([
    signedIn.waitFor({ state: 'visible', timeout: 90_000 }),
    oidcError
      .waitFor({ state: 'visible', timeout: 90_000 })
      .then(async () => {
        const text = await page.locator('main').innerText().catch(() => '')
        if (/échouée|échoue/i.test(text)) {
          throw new Error(`OIDC callback failed: ${text}. Console: ${consoleErrors.join(' | ')}`)
        }
        // Still showing "Connexion OIDC…" — keep waiting for the shell.
        await signedIn.waitFor({ state: 'visible', timeout: 90_000 })
      }),
  ])
}
