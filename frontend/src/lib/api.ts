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

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401) {
      const returnTo = `${window.location.pathname}${window.location.search}`
      window.location.assign(`/login?returnTo=${encodeURIComponent(returnTo)}`)
    }
    if (error.response?.status === 403) {
      const reason = (error.response.data as { reason?: string } | undefined)?.reason
      if (reason && !window.location.pathname.startsWith('/login')) {
        window.location.assign(`/login/erreur?reason=${encodeURIComponent(reason)}`)
      }
    }
    return Promise.reject(error)
  },
)
