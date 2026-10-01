// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { handleCallback } from '../lib/auth'
import { useAuth } from '../auth/AuthProvider'

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
        await handleCallback()
        await refreshMe()
        navigate('/docs', { replace: true })
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Connexion échouée')
      }
    })()
  }, [navigate, refreshMe])

  if (error) {
    return (
      <main className="mx-auto max-w-lg px-6 py-20 text-center">
        <p className="text-amber-700">{error}</p>
      </main>
    )
  }

  return (
    <main className="mx-auto max-w-lg px-6 py-20 text-center text-socle-slate">
      Connexion OIDC…
    </main>
  )
}
