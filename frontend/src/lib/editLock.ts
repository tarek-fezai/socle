// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'

export type EditLockStatus = {
  active: boolean
  holderUserId: string | null
  holderDisplayName: string | null
  acquiredAt: string | null
  heartbeatAt: string | null
  heldByCurrentUser: boolean
  ttlSeconds: number
  heartbeatSeconds: number
}

export async function fetchEditLock(api: AxiosInstance, documentId: string) {
  const { data } = await api.get<EditLockStatus>(`/api/v1/documents/${documentId}/edit-lock`)
  return data
}

export async function acquireEditLock(api: AxiosInstance, documentId: string) {
  const { data } = await api.post<EditLockStatus>(`/api/v1/documents/${documentId}/edit-lock`)
  return data
}

export async function heartbeatEditLock(api: AxiosInstance, documentId: string) {
  const { data } = await api.post<EditLockStatus>(
    `/api/v1/documents/${documentId}/edit-lock/heartbeat`,
  )
  return data
}

export async function releaseEditLock(api: AxiosInstance, documentId: string) {
  await api.delete(`/api/v1/documents/${documentId}/edit-lock`)
}

export function editLockBannerText(lock: EditLockStatus, nowMs = Date.now()): string | null {
  if (!lock.active || lock.heldByCurrentUser || !lock.holderDisplayName || !lock.acquiredAt) {
    return null
  }
  const mins = Math.max(1, Math.round((nowMs - new Date(lock.acquiredAt).getTime()) / 60_000))
  return `${lock.holderDisplayName} édite ce document depuis ${mins} min`
}
