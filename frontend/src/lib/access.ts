// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { AxiosInstance } from 'axios'

export type AccessSource = 'direct' | 'group' | 'inherited'

export type AccessEntry = {
  subject: string
  subjectType: string
  subjectId: string | null
  relation: string
  source: AccessSource
  inheritedFrom: string | null
  revocable: boolean
}

export type AccessListResponse = {
  objectType: string
  objectId: string
  canManage: boolean
  entries: AccessEntry[]
}

export type PermissionRequest = {
  relation: string
  subjectType: 'user' | 'group'
  subjectId: string
}

export async function listAccess(
  client: AxiosInstance,
  objectType: string,
  objectId: string,
): Promise<AccessListResponse> {
  const { data } = await client.get<AccessListResponse>(`/api/v1/access/${objectType}/${objectId}`)
  return data
}

export async function grantAccess(
  client: AxiosInstance,
  objectType: string,
  objectId: string,
  body: PermissionRequest,
): Promise<void> {
  await client.post(`/api/v1/access/${objectType}/${objectId}`, body)
}

export async function revokeAccess(
  client: AxiosInstance,
  objectType: string,
  objectId: string,
  body: PermissionRequest,
): Promise<void> {
  await client.delete(`/api/v1/access/${objectType}/${objectId}`, { data: body })
}

export function sourceLabel(source: AccessSource): string {
  switch (source) {
    case 'direct':
      return 'Direct'
    case 'group':
      return 'Via groupe'
    case 'inherited':
      return 'Hérité'
    default:
      return source
  }
}

export function accessErrorMessage(error: unknown): string {
  const status = (error as { response?: { status?: number; data?: { message?: string } } })?.response
    ?.status
  const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message

  if (status === 503) {
    return (
      message ??
      "L'opération a échoué, aucun changement n'a été appliqué (journal d'audit indisponible)."
    )
  }
  if (status === 403) {
    return "Vous n'avez pas le droit de gérer les accès sur cette ressource."
  }
  if (status === 502) {
    return 'Service d’autorisation indisponible. Réessayez plus tard.'
  }
  return message ?? 'Une erreur est survenue.'
}
