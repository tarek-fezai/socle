// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'

/** Aligné sur `eu.socle.home.HomeDtos` (GET /api/v1/home). */
export type HomeKpis = {
  publishedDocuments: number
  pendingApprovals: number
  viewsThisMonth: number
  /** 0–100, ou null s’il n’y a pas assez de données. */
  averageReliabilityPercent: number | null
}

export type HomeResumeItem = {
  documentId: string
  title: string
  updatedAt: string
  relativeLabel: string
}

export type HomePublishedItem = {
  documentId: string
  title: string
  spaceName: string
  publishedAt: string
}

export type HomeApprovalItem = {
  documentId: string
  requestId: string
  title: string
  requesterName: string
  slaRemainingLabel: string | null
}

export type HomeActivityItem = {
  eventType: string
  actorDisplayName: string
  you: boolean
  documentTitle?: string | null
  documentId?: string | null
  createdAt: string
  relativeLabel: string
  actionLabel: string
}

export type HomeDashboard = {
  greetingFirstName: string
  kpis: HomeKpis
  resume: HomeResumeItem[]
  recentlyPublished: HomePublishedItem[]
  pendingApprovals: HomeApprovalItem[]
  teamActivity: HomeActivityItem[]
}

export async function getHomeDashboard(api: AxiosInstance) {
  const { data } = await api.get<HomeDashboard>('/api/v1/home')
  return data
}

export const homeQueryKey = ['home'] as const
