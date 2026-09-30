import { ReactNode } from 'react'
import { useAuth } from './AuthProvider'

/** Garde UI tant que la session OIDC n'est pas confirmée (init requireLogin). */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { loading, authenticated } = useAuth()

  if (loading) {
    return (
      <main className="mx-auto max-w-lg px-6 py-20 text-center text-socle-slate">
        Connexion OIDC…
      </main>
    )
  }

  if (!authenticated) {
    return (
      <main className="mx-auto max-w-lg px-6 py-20 text-center text-socle-slate">
        Redirection vers l&apos;IdP…
      </main>
    )
  }

  return <>{children}</>
}
