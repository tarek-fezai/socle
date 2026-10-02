// SPDX-License-Identifier: AGPL-3.0-or-later
import { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './AuthProvider'
import { LoginLoadingLayout } from '../pages/login/LoginPage'

/** Garde UI : sans session → `/login?returnTo=…` (jamais l'IdP directement). */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { loading, authenticated } = useAuth()
  const location = useLocation()

  if (loading) {
    return <LoginLoadingLayout message="Vérification de la session…" />
  }

  if (!authenticated) {
    const returnTo = `${location.pathname}${location.search}${location.hash}`
    return <Navigate to={`/login?returnTo=${encodeURIComponent(returnTo)}`} replace />
  }

  return <>{children}</>
}
