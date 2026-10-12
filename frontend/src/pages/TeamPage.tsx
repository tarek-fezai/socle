// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthProvider'
import { isPlatformAdmin } from '../lib/platformRoles'

/** /team : les admins vont à l’admin ; les autres n’ont plus cet écran. */
export function TeamPage() {
  const { me, loading } = useAuth()
  if (loading) {
    return (
      <main className="page-shell">
        <p className="text-socle-muted">Chargement…</p>
      </main>
    )
  }
  if (isPlatformAdmin(me?.roles)) {
    return <Navigate to="/admin/team" replace />
  }
  return <Navigate to="/" replace />
}
