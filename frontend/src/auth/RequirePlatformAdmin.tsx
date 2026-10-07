// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Outlet } from 'react-router-dom'
import { useAuth } from './AuthProvider'
import { isPlatformAdmin } from '../lib/platformRoles'
import { AdminForbiddenPage } from '../pages/admin/AdminForbiddenPage'

/** Garde UI : l’API /api/v1/admin/** reste la source d’autorité. */
export function RequirePlatformAdmin() {
  const { loading, me } = useAuth()
  if (loading) {
    return (
      <div className="admin-page" data-testid="admin-guard-loading">
        <p className="admin-lead">Chargement…</p>
      </div>
    )
  }
  if (!isPlatformAdmin(me?.roles)) {
    return <AdminForbiddenPage />
  }
  return <Outlet />
}
