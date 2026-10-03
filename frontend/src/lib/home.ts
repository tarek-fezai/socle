// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'
import type { components } from './api-types'

/** Types générés depuis `openapi/openapi.json` (HomeDtos). */
export type HomeKpis = components['schemas']['Kpis']
export type HomeResumeItem = components['schemas']['ResumeItem']
export type HomePublishedItem = components['schemas']['RecentlyPublishedItem']
export type HomeApprovalItem = components['schemas']['PendingApprovalItem']
export type HomeActivityItem = components['schemas']['TeamActivityItem']
export type HomeDashboard = components['schemas']['HomeResponse']

export async function getHomeDashboard(api: AxiosInstance) {
  const { data } = await api.get<HomeDashboard>('/api/v1/home')
  return data
}

export const homeQueryKey = ['home'] as const
