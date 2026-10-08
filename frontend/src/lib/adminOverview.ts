// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { AxiosInstance } from 'axios'
import type { components } from './api-types'

export type AdminOverviewView = components['schemas']['AdminOverviewView']

export const adminOverviewKey = () => ['admin', 'overview'] as const

export async function fetchAdminOverview(api: AxiosInstance) {
  const { data } = await api.get<AdminOverviewView>('/api/v1/admin/overview')
  return data
}
