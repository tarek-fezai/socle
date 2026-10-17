// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { AxiosInstance } from 'axios'
import type { DocumentDetail, TagRef } from './documents'
import type { FolderDocumentCard, OwnerGroupRef, StatusCounts } from './folders'

export type Space = {
  id: string
  name: string
  color: string | null
  createdAt: string | null
  /** open (défaut) | restricted — transclusion entrante inter-workspace */
  externalReference?: 'open' | 'restricted' | string
  /** organisation | space | restricted — défaut des nouveaux documents */
  defaultVisibility?: 'organisation' | 'space' | 'restricted' | string
  /** members (défaut) | all_readers — qui peut commenter */
  commentPolicy?: 'members' | 'all_readers' | string
  canManage: boolean
  isOwner: boolean
  isResponsible: boolean
  /** member = relations espace ; public-only = docs organisation lisibles */
  membership?: 'member' | 'public-only' | string
  description?: string | null
  documentCount?: number
  memberPreview?: Array<{ initials: string }>
  memberExtraCount?: number
}

/** Dossier à la racine, tel que renvoyé par GET /spaces/{id}. */
export type SpaceFolderCard = {
  id: string
  name: string
  documentCount: number
  inReviewCount: number
  reliabilityAverage?: number | string | null
}

/**
 * GET /spaces/{id} enrichi (E1) pour l'accueil.
 * Les champs au-delà de {@link Space} sont absents de la liste.
 */
export type SpaceDetail = Space & {
  pageDocumentId?: string | null
  canEdit?: boolean
  canDelete?: boolean
  language?: string | null
  /** `actif`. */
  status?: string | null
  owner?: OwnerGroupRef | null
  memberCount?: number
  teamCount?: number
  /** Quatre premières initiales. */
  members?: Array<{ initials: string }>
  lastActivity?: { who?: string | null; at?: string | null } | null
  reliabilityAverage?: number | string | null
  statusCounts?: StatusCounts | null
  folders?: SpaceFolderCard[]
  rootDocuments?: FolderDocumentCard[]
  toReview?: FolderDocumentCard[]
  tags?: TagRef[]
}

export type SpaceOwner = {
  userId: string
  email: string
  displayName: string
  responsible: boolean
}

export async function listSpaces(api: AxiosInstance) {
  const { data } = await api.get<Space[]>('/api/v1/spaces')
  return data
}

export async function getSpace(api: AxiosInstance, id: string) {
  const { data } = await api.get<SpaceDetail>(`/api/v1/spaces/${id}`)
  return data
}

/** `null` tant que la page d'accueil n'a pas été écrite (204). */
export async function getSpacePage(api: AxiosInstance, id: string): Promise<DocumentDetail | null> {
  const res = await api.get<DocumentDetail | ''>(`/api/v1/spaces/${id}/page`, {
    validateStatus: (status) => status === 200 || status === 204,
  })
  if (res.status === 204 || res.data == null || res.data === '') return null
  return res.data
}

export async function saveSpacePage(
  api: AxiosInstance,
  id: string,
  body: Record<string, unknown>,
  changeSummary?: string,
) {
  const { data } = await api.put<DocumentDetail>(`/api/v1/spaces/${id}/page`, {
    body,
    ...(changeSummary ? { changeSummary } : {}),
  })
  return data
}

export async function createSpace(
  api: AxiosInstance,
  body: { name: string; color?: string; description?: string | null },
) {
  const { data } = await api.post<Space>('/api/v1/spaces', body)
  return data
}

export async function updateSpace(
  api: AxiosInstance,
  id: string,
  body: {
    name: string
    color?: string | null
    externalReference?: 'open' | 'restricted' | null
    defaultVisibility?: 'organisation' | 'space' | 'restricted' | null
    commentPolicy?: 'members' | 'all_readers' | null
    description?: string | null
  },
) {
  const { data } = await api.put<Space>(`/api/v1/spaces/${id}`, body)
  return data
}

export async function listSpaceOwners(api: AxiosInstance, spaceId: string) {
  const { data } = await api.get<{ spaceId: string; owners: SpaceOwner[] }>(
    `/api/v1/spaces/${spaceId}/owners`,
  )
  return data
}

export async function addSpaceOwner(
  api: AxiosInstance,
  spaceId: string,
  body: { userId: string; responsible?: boolean },
) {
  const { data } = await api.post<{ spaceId: string; owners: SpaceOwner[] }>(
    `/api/v1/spaces/${spaceId}/owners`,
    { userId: body.userId, responsible: body.responsible ?? false },
  )
  return data
}

export async function removeSpaceOwner(api: AxiosInstance, spaceId: string, userId: string) {
  const { data } = await api.delete<{ spaceId: string; owners: SpaceOwner[] }>(
    `/api/v1/spaces/${spaceId}/owners/${userId}`,
  )
  return data
}

export async function setSpaceResponsible(
  api: AxiosInstance,
  spaceId: string,
  userId: string,
  responsible: boolean,
) {
  const { data } = await api.put<{ spaceId: string; owners: SpaceOwner[] }>(
    `/api/v1/spaces/${spaceId}/owners/${userId}/responsible`,
    { responsible },
  )
  return data
}
