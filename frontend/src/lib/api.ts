// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import axios from 'axios'
import { getAccessToken } from './auth'
import { apiBaseUrl } from './urls'

export const api = axios.create({
  baseURL: apiBaseUrl(),
  timeout: 15_000,
})

api.interceptors.request.use(async (config) => {
  const token = await getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * Page « Connexion refusée », ou null si cet écran ne doit pas remplacer la page courante.
 * `/callback` décide lui-même (retour vers l’acceptation si l’invitation est en cours).
 * `/invitations/accept` doit pouvoir finir le POST d’acceptation.
 */
export function accessDeniedRedirect(pathname: string, reason: string | undefined): string | null {
  if (!reason) return null
  if (pathname.startsWith('/login')) return null
  if (pathname === '/callback' || pathname.startsWith('/callback/')) return null
  if (pathname === '/invitations/accept' || pathname.startsWith('/invitations/accept/')) return null
  return `/login/erreur?reason=${encodeURIComponent(reason)}`
}

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401) {
      const returnTo = `${window.location.pathname}${window.location.search}`
      window.location.assign(`/login?returnTo=${encodeURIComponent(returnTo)}`)
    }
    if (error.response?.status === 403) {
      const reason = (error.response.data as { reason?: string } | undefined)?.reason
      const target = accessDeniedRedirect(window.location.pathname, reason)
      if (target) window.location.assign(target)
    }
    return Promise.reject(error)
  },
)
