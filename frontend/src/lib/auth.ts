import {
  UserManager,
  WebStorageStateStore,
  type User,
  type UserManagerSettings,
} from 'oidc-client-ts'

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
  /** Optional org brand when backend exposes it. */
  displayName?: string
  organizationName?: string
}

let userManager: UserManager | null = null
let userManagerPromise: Promise<UserManager> | null = null
let initPromise: Promise<boolean> | null = null
let cachedAuthConfig: PublicAuthConfig | null = null

/** Org / product label from auth-config when present, else "Socle". */
export function organizationDisplayName(config?: PublicAuthConfig | null): string {
  const c = config ?? cachedAuthConfig
  const name = c?.organizationName?.trim() || c?.displayName?.trim()
  return name || 'Socle'
}

export function getCachedAuthConfig(): PublicAuthConfig | null {
  return cachedAuthConfig
}

function apiBaseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')
}

function isOidcCallbackPath(pathname = window.location.pathname): boolean {
  return pathname === '/callback' || pathname === '/silent-renew'
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
    post_logout_redirect_uri: `${origin}/`,
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

export async function initAuth(requireLogin = false): Promise<boolean> {
  if (!initPromise) {
    initPromise = (async () => {
      const um = await getUserManager()

      if (isOidcCallbackPath()) {
        // CallbackPage / SilentRenewPage own the OIDC callback handlers
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

      const authenticated = Boolean(user && !user.expired)
      if (!authenticated && requireLogin) {
        await um.signinRedirect()
        return false
      }
      return authenticated
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
      await um.signinRedirect()
      return null
    }
  }
  return user?.access_token ?? null
}

export async function login(): Promise<void> {
  const um = await getUserManager()
  await um.signinRedirect()
}

export async function logout(): Promise<void> {
  const um = await getUserManager()
  await um.signoutRedirect()
}

export async function isAuthenticated(): Promise<boolean> {
  const user = await currentUser()
  return Boolean(user && !user.expired)
}
