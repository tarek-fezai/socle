// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import {
  UserManager,
  WebStorageStateStore,
  type User,
  type UserManagerSettings,
} from 'oidc-client-ts'
import { apiBaseUrl } from './urls'

/** Platform roles from GET /api/v1/me — never read from access-token claims. */
export const SocleRole = {
  CONTRIBUTEUR: 'CONTRIBUTEUR',
  AUDITEUR: 'AUDITEUR',
  INTEGRATEUR: 'INTEGRATEUR',
  ADMINISTRATEUR_SYSTEME: 'ADMINISTRATEUR_SYSTEME',
} as const

export type SocleRoleName = (typeof SocleRole)[keyof typeof SocleRole]

export type PublicAuthConfig = {
  authority: string
  clientId: string
  scopes: string[]
  authorizationEndpoint?: string
  tokenEndpoint?: string
  endSessionEndpoint?: string
  jwksUri?: string
  displayName?: string
  organizationName?: string
  passkeyAcrValues?: string
  idpDisplayName?: string
  supportContact?: string
}

let userManager: UserManager | null = null
let userManagerPromise: Promise<UserManager> | null = null
let initPromise: Promise<boolean> | null = null
let cachedAuthConfig: PublicAuthConfig | null = null

const RETURN_TO_STATE_KEY = 'socle.returnTo'

/** Org / product label from auth-config when present, else "Socle". */
export function organizationDisplayName(config?: PublicAuthConfig | null): string {
  const c = config ?? cachedAuthConfig
  const name = c?.organizationName?.trim() || c?.displayName?.trim()
  return name || 'Socle'
}

export function getCachedAuthConfig(): PublicAuthConfig | null {
  return cachedAuthConfig
}

function containsBackslash(value: string): boolean {
  if (value.includes('\\')) return true
  try {
    return decodeURIComponent(value).includes('\\')
  } catch {
    return true
  }
}

function hasControlChars(value: string): boolean {
  return /[\u0000-\u001F\u007F]/.test(value)
}

/**
 * Open-redirect protection: relative same-origin path only.
 * Rejects `//…`, `\`, control chars, leading whitespace, `/\…`, and off-origin URLs.
 * Returns `pathname + search + hash`, else `/`.
 */
export function sanitizeReturnTo(raw: string | null | undefined): string {
  if (raw == null || raw === '') return '/'
  // Leading whitespace is rejected (do not trim).
  if (/^\s/.test(raw)) return '/'
  if (containsBackslash(raw) || hasControlChars(raw)) return '/'
  if (!raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) return '/'

  try {
    const origin =
      typeof window !== 'undefined' && window.location?.origin
        ? window.location.origin
        : 'http://127.0.0.1'
    const resolved = new URL(raw, origin)
    if (resolved.origin !== origin) return '/'
    const out = `${resolved.pathname}${resolved.search}${resolved.hash}`
    if (containsBackslash(out) || hasControlChars(out)) return '/'
    if (!out.startsWith('/') || out.startsWith('//')) return '/'
    return out
  } catch {
    return '/'
  }
}

export function storeReturnTo(path: string): void {
  try {
    sessionStorage.setItem(RETURN_TO_STATE_KEY, sanitizeReturnTo(path))
  } catch {
    // ignore
  }
}

export function consumeReturnTo(): string {
  try {
    const v = sessionStorage.getItem(RETURN_TO_STATE_KEY)
    sessionStorage.removeItem(RETURN_TO_STATE_KEY)
    return sanitizeReturnTo(v)
  } catch {
    return '/'
  }
}

function isOidcCallbackPath(pathname = window.location.pathname): boolean {
  return pathname === '/callback' || pathname === '/silent-renew'
}

function isLoginPath(pathname = window.location.pathname): boolean {
  return pathname === '/login' || pathname.startsWith('/login/')
}

export async function fetchAuthConfig(): Promise<PublicAuthConfig> {
  const res = await fetch(`${apiBaseUrl()}/api/v1/public/auth-config`)
  if (!res.ok) {
    throw new Error(`auth-config indisponible (${res.status})`)
  }
  cachedAuthConfig = (await res.json()) as PublicAuthConfig
  return cachedAuthConfig
}

function buildSettings(config: PublicAuthConfig): UserManagerSettings {
  const origin = window.location.origin
  const scope = Array.isArray(config.scopes) ? config.scopes.join(' ') : String(config.scopes ?? '')

  const settings: UserManagerSettings = {
    authority: config.authority,
    client_id: config.clientId,
    redirect_uri: `${origin}/callback`,
    silent_redirect_uri: `${origin}/silent-renew`,
    post_logout_redirect_uri: `${origin}/login`,
    response_type: 'code',
    scope,
    automaticSilentRenew: true,
    includeIdTokenInSilentRenew: true,
    loadUserInfo: false,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  }

  const seed: NonNullable<UserManagerSettings['metadataSeed']> = {}
  if (config.authorizationEndpoint) seed.authorization_endpoint = config.authorizationEndpoint
  if (config.tokenEndpoint) seed.token_endpoint = config.tokenEndpoint
  if (config.endSessionEndpoint) seed.end_session_endpoint = config.endSessionEndpoint
  if (config.jwksUri) seed.jwks_uri = config.jwksUri
  if (Object.keys(seed).length > 0) {
    settings.metadataSeed = seed
  }

  return settings
}

export async function getUserManager(): Promise<UserManager> {
  if (userManager) return userManager
  if (!userManagerPromise) {
    userManagerPromise = (async () => {
      const config = await fetchAuthConfig()
      userManager = new UserManager(buildSettings(config))
      return userManager
    })()
  }
  return userManagerPromise
}

/** Test/helper: reset cached manager (e.g. between Vitest cases). */
export function resetAuthClient(): void {
  userManager = null
  userManagerPromise = null
  initPromise = null
  cachedAuthConfig = null
}

async function currentUser(): Promise<User | null> {
  const um = await getUserManager()
  return um.getUser()
}

/**
 * Initialise la session OIDC. Ne redirige plus vers l'IdP :
 * les routes protégées envoient vers `/login`.
 */
export async function initAuth(_requireLogin = false): Promise<boolean> {
  if (!initPromise) {
    initPromise = (async () => {
      const um = await getUserManager()

      if (isOidcCallbackPath() || isLoginPath()) {
        return false
      }

      let user = await um.getUser()
      if (!user || user.expired) {
        try {
          user = await um.signinSilent()
        } catch {
          user = null
        }
      }

      return Boolean(user && !user.expired)
    })()
  }
  return initPromise
}

export async function handleCallback(): Promise<User> {
  const um = await getUserManager()
  return um.signinRedirectCallback()
}

export async function handleSilentRenew(): Promise<void> {
  const um = await getUserManager()
  await um.signinSilentCallback()
}

export async function getAccessToken(): Promise<string | null> {
  const um = await getUserManager()
  let user = await um.getUser()
  if (!user || user.expired) {
    try {
      user = await um.signinSilent()
    } catch {
      return null
    }
  }
  return user?.access_token ?? null
}

type SigninExtra = {
  login_hint?: string
  acr_values?: string
  state?: string
}

async function signinRedirect(extra: SigninExtra = {}, returnTo = '/'): Promise<void> {
  const safe = sanitizeReturnTo(returnTo)
  storeReturnTo(safe)
  const um = await getUserManager()
  await um.signinRedirect({
    state: safe,
    extraQueryParams: {
      ...(extra.login_hint ? { login_hint: extra.login_hint } : {}),
      ...(extra.acr_values ? { acr_values: extra.acr_values } : {}),
    },
  })
}

export async function login(returnTo = '/'): Promise<void> {
  await signinRedirect({}, returnTo)
}

export async function loginWithHint(email: string, returnTo = '/'): Promise<void> {
  await signinRedirect({ login_hint: email }, returnTo)
}

export async function loginWithSso(returnTo = '/'): Promise<void> {
  await signinRedirect({}, returnTo)
}

export async function loginWithAcr(acrValues: string, returnTo = '/'): Promise<void> {
  await signinRedirect({ acr_values: acrValues }, returnTo)
}

export async function logout(): Promise<void> {
  const um = await getUserManager()
  await um.signoutRedirect()
}

/** Drop local OIDC session without IdP round-trip (retry after access denied). */
export async function clearLocalSession(): Promise<void> {
  const um = await getUserManager()
  await um.removeUser()
  initPromise = null
}

export async function isAuthenticated(): Promise<boolean> {
  const user = await currentUser()
  return Boolean(user && !user.expired)
}

/** Resolve returnTo from OIDC state or sessionStorage fallback. */
export function resolveReturnTo(user: User | null): string {
  const fromState = typeof user?.state === 'string' ? user.state : null
  if (fromState) return sanitizeReturnTo(fromState)
  return consumeReturnTo()
}
