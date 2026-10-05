// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { AxiosInstance } from 'axios'

export type LicenceStatus = 'valide' | 'expire_bientot' | 'expiree' | 'absente'

export type LicenceView = {
  status: LicenceStatus
  licenseId: string | null
  licensee: string | null
  edition: string | null
  issuedAt: string | null
  expiresAt: string | null
  maxUsers: number | null
  activeUsers: number
  effectiveMaxUsers: number
  evaluationMode: boolean
  bannerMessage: string | null
}

export function licenceAdminKey() {
  return ['admin', 'licence'] as const
}

export async function getLicence(api: Pick<AxiosInstance, 'get'>) {
  const { data } = await api.get<LicenceView>('/api/v1/admin/licence')
  return data
}

export async function importLicence(api: Pick<AxiosInstance, 'post'>, licenceJson: string) {
  const { data } = await api.post<LicenceView>('/api/v1/admin/licence/import', { licenceJson })
  return data
}
