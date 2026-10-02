// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import axios from 'axios'
import {
  handleCallback,
  resolveReturnTo,
} from '../lib/auth'
import { useAuth } from '../auth/AuthProvider'
import { LoginLoadingLayout } from './login/LoginPage'

export function CallbackPage() {
  const navigate = useNavigate()
  const { refreshMe } = useAuth()
  const [error, setError] = useState<string | null>(null)
  const started = useRef(false)

  useEffect(() => {
    // OIDC authorization code is single-use — React StrictMode must not consume it twice.
    if (started.current) return
    started.current = true

    void (async () => {
      try {
        const user = await handleCallback()
        const returnTo = resolveReturnTo(user)
        try {
          await refreshMe()
          navigate(returnTo, { replace: true })
        } catch (e) {
          if (axios.isAxiosError(e) && e.response?.status === 403) {
            const reason =
              (e.response.data as { reason?: string } | undefined)?.reason ?? 'not_provisioned'
            navigate(`/login/erreur?reason=${encodeURIComponent(reason)}`, { replace: true })
            return
          }
          throw e
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Connexion échouée')
      }
    })()
  }, [navigate, refreshMe])

  if (error) {
    return <LoginLoadingLayout message={error} />
  }

  return <LoginLoadingLayout message="Finalisation de la connexion…" />
}
