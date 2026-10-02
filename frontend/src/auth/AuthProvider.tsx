// SPDX-License-Identifier: AGPL-3.0-or-later
import {
  createContext,
  ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react'
import { api } from '../lib/api'
import {
  initAuth,
  isAuthenticated as checkAuthenticated,
  login as oidcLogin,
  logout as oidcLogout,
  organizationDisplayName,
  getCachedAuthConfig,
  type SocleRoleName,
} from '../lib/auth'

type Me = {
  id: string
  email: string
  displayName: string
  avatarInitials: string
  /** Prénom OIDC / SCIM si fourni par /me. */
  givenName?: string | null
  /** SocleRole names from GET /api/v1/me — never from the access token. */
  roles?: SocleRoleName[] | string[]
}

type AuthState = {
  loading: boolean
  authenticated: boolean
  me: Me | null
  /** Org brand from auth-config (`organizationName` / `displayName`), fallback "Socle". */
  organizationName: string
  login: () => Promise<void>
  logout: () => Promise<void>
  refreshMe: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({
  children,
  requireLogin = false,
}: {
  children: ReactNode
  /** When true, OIDC init redirects to the IdP if no session (éditeur / docs). */
  requireLogin?: boolean
}) {
  const [loading, setLoading] = useState(true)
  const [authenticated, setAuthenticated] = useState(false)
  const [me, setMe] = useState<Me | null>(null)
  const [organizationName, setOrganizationName] = useState(() => organizationDisplayName())

  const refreshMe = useCallback(async () => {
    if (!(await checkAuthenticated())) {
      setAuthenticated(false)
      setMe(null)
      return
    }
    setAuthenticated(true)
    const { data } = await api.get<Me>('/api/v1/me')
    setMe(data)
  }, [])

  // refreshMe may throw 403 (access_denied) — callers (CallbackPage) handle it.

  useEffect(() => {
    void (async () => {
      try {
        const ok = await initAuth(requireLogin)
        setOrganizationName(organizationDisplayName(getCachedAuthConfig()))
        setAuthenticated(ok)
        if (ok) {
          await refreshMe()
        }
      } catch {
        setAuthenticated(false)
        setMe(null)
        setOrganizationName(organizationDisplayName(getCachedAuthConfig()))
      } finally {
        setLoading(false)
      }
    })()
  }, [requireLogin, refreshMe])

  const value = useMemo<AuthState>(
    () => ({
      loading,
      authenticated,
      me,
      organizationName,
      login: oidcLogin,
      logout: oidcLogout,
      refreshMe,
    }),
    [loading, authenticated, me, organizationName, refreshMe],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth must be used within AuthProvider')
  }
  return ctx
}
