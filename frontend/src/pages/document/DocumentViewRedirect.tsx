// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Navigate, useLocation, useParams } from 'react-router-dom'

/**
 * Ancienne « vue composite » `/docs/:id/view` → page de lecture `/docs/:id`.
 * Conserve `?comments=open` (notifications, approbations) et le hash.
 */
export function DocumentViewRedirect() {
  const { id = '' } = useParams()
  const { search, hash } = useLocation()
  return <Navigate to={{ pathname: `/docs/${id}`, search, hash }} replace />
}
