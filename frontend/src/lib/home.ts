// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'

export type HomeKpis = {
  publishedDocuments: number
  pendingApprovals: number
  viewsThisMonth: number
  /** 0–100 (ex. 91) */
  averageReliabilityPercent: number
}

export type HomeResumeItem = {
  documentId: string
  title: string
  status: string
  modifiedAt: string
}

export type HomePublishedItem = {
  documentId: string
  title: string
  spaceName: string
  publishedAt: string
}

export type HomeApprovalItem = {
  approvalId: string
  documentId: string
  title: string
  requesterName: string
  /** Texte secondaire desktop (SLA, etc.). */
  detail?: string | null
  /** Variante courte mobile. */
  detailShort?: string | null
  slaRemainingHours?: number | null
  kind?: 'approval' | 'review' | string
}

export type HomeActivityItem = {
  id: string
  actorName: string
  actorInitials: string
  /** Couleur de fond avatar (ex. #EEEDFD). */
  actorBg?: string | null
  /** Couleur texte avatar (ex. #3730E0). */
  actorFg?: string | null
  /** true = « Vous avez … » sans nom en gras. */
  isSelf?: boolean
  action: string
  documentTitle?: string | null
  documentId?: string | null
  occurredAt: string
}

export type HomeDashboard = {
  greetingFirstName: string
  kpis: HomeKpis
  resume: HomeResumeItem[]
  recentlyPublished: HomePublishedItem[]
  pendingYourApproval: HomeApprovalItem[]
  teamActivity: HomeActivityItem[]
}

export async function getHomeDashboard(api: AxiosInstance) {
  const { data } = await api.get<HomeDashboard>('/api/v1/home')
  return data
}

export const homeQueryKey = ['home'] as const
