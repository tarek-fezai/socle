// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'

const getUser = vi.fn()
const signinSilent = vi.fn()
const signinRedirect = vi.fn()
const signoutRedirect = vi.fn()
const signinRedirectCallback = vi.fn()
const signinSilentCallback = vi.fn()

vi.mock('oidc-client-ts', () => {
  class MockUserManager {
    getUser = getUser
    signinSilent = signinSilent
    signinRedirect = signinRedirect
    signoutRedirect = signoutRedirect
    signinRedirectCallback = signinRedirectCallback
    signinSilentCallback = signinSilentCallback
  }
  class MockWebStorageStateStore {
    constructor(_opts?: unknown) {}
  }
  return {
    UserManager: MockUserManager,
    WebStorageStateStore: MockWebStorageStateStore,
  }
})

import {
  getAccessToken,
  initAuth,
  login,
  logout,
  organizationDisplayName,
  resetAuthClient,
  SocleRole,
} from './auth'

const AUTH_CONFIG = {
  authority: 'http://localhost:8081/realms/socle',
  clientId: 'socle-frontend',
  scopes: ['openid', 'profile', 'email'],
}

function mockAuthConfigFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: true,
      json: async () => AUTH_CONFIG,
    }),
  )
}

describe('auth (oidc-client-ts)', () => {
  beforeEach(() => {
    resetAuthClient()
    getUser.mockReset()
    signinSilent.mockReset()
    signinRedirect.mockReset()
    signoutRedirect.mockReset()
    signinRedirectCallback.mockReset()
    signinSilentCallback.mockReset()
    mockAuthConfigFetch()
    window.history.replaceState({}, '', '/')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    resetAuthClient()
  })

  it('login déclenche signinRedirect après chargement de auth-config', async () => {
    signinRedirect.mockResolvedValue(undefined)
    await login()
    expect(fetch).toHaveBeenCalledWith(
      expect.stringMatching(/\/api\/v1\/public\/auth-config$/),
    )
    expect(signinRedirect).toHaveBeenCalledTimes(1)
  })

  it('getAccessToken renvoie le token et renouvelle silencieusement si expiré', async () => {
    getUser.mockResolvedValue({ access_token: 'tok-old', expired: true })
    signinSilent.mockResolvedValue({ access_token: 'tok-fresh', expired: false })

    const token = await getAccessToken()
    expect(signinSilent).toHaveBeenCalled()
    expect(token).toBe('tok-fresh')
  })

  it('getAccessToken renvoie null si le renew silencieux échoue (sans rediriger vers IdP)', async () => {
    getUser.mockResolvedValue({ access_token: 'x', expired: true })
    signinSilent.mockRejectedValue(new Error('silent failed'))

    const token = await getAccessToken()
    expect(token).toBeNull()
    expect(signinRedirect).not.toHaveBeenCalled()
  })

  it('logout déclenche signoutRedirect', async () => {
    signoutRedirect.mockResolvedValue(undefined)
    await logout()
    expect(signoutRedirect).toHaveBeenCalledTimes(1)
  })

  it('initAuth avec session valide ne redirige pas', async () => {
    getUser.mockResolvedValue({ access_token: 'ok', expired: false })
    const ok = await initAuth(true)
    expect(ok).toBe(true)
    expect(signinRedirect).not.toHaveBeenCalled()
  })

  it('initAuth sans session ne redirige plus vers IdP (page /login)', async () => {
    getUser.mockResolvedValue(null)
    signinSilent.mockRejectedValue(new Error('no session'))

    const ok = await initAuth(true)
    expect(ok).toBe(false)
    expect(signinRedirect).not.toHaveBeenCalled()
  })

  it('sanitizeReturnTo bloque les redirections ouvertes', async () => {
    const { sanitizeReturnTo, loginWithHint } = await import('./auth')
    expect(sanitizeReturnTo('//evil.example')).toBe('/')
    expect(sanitizeReturnTo('https://evil.example')).toBe('/')
    expect(sanitizeReturnTo('/docs/abc')).toBe('/docs/abc')

    signinRedirect.mockResolvedValue(undefined)
    await loginWithHint('tarek.fezai@example.com', '/spaces/1')
    expect(signinRedirect).toHaveBeenCalledWith(
      expect.objectContaining({
        state: '/spaces/1',
        extraQueryParams: expect.objectContaining({ login_hint: 'tarek.fezai@example.com' }),
      }),
    )
  })

  it('loginWithAcr transmet acr_values', async () => {
    const { loginWithAcr } = await import('./auth')
    signinRedirect.mockResolvedValue(undefined)
    await loginWithAcr('phr', '/docs')
    expect(signinRedirect).toHaveBeenCalledWith(
      expect.objectContaining({
        extraQueryParams: expect.objectContaining({ acr_values: 'phr' }),
      }),
    )
  })

  it('expose les noms de rôles Socle (pas les claims Keycloak)', () => {
    expect(SocleRole.AUDITEUR).toBe('AUDITEUR')
    expect(SocleRole.INTEGRATEUR).toBe('INTEGRATEUR')
    expect(SocleRole.CONTRIBUTEUR).toBe('CONTRIBUTEUR')
    expect(SocleRole.ADMINISTRATEUR_SYSTEME).toBe('ADMINISTRATEUR_SYSTEME')
  })

  it('organizationDisplayName lit auth-config ou retombe sur Socle', async () => {
    expect(organizationDisplayName(null)).toBe('Socle')
    expect(organizationDisplayName({ ...AUTH_CONFIG, organizationName: 'Acme' })).toBe('Acme')
    expect(organizationDisplayName({ ...AUTH_CONFIG, displayName: 'Beta Org' })).toBe('Beta Org')

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ ...AUTH_CONFIG, organizationName: 'From API' }),
      }),
    )
    resetAuthClient()
    getUser.mockResolvedValue({ access_token: 'ok', expired: false })
    await initAuth(false)
    expect(organizationDisplayName()).toBe('From API')
  })
})
