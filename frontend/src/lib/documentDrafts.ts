// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { AxiosInstance } from 'axios'
import type { components } from './api-types'

type Api = Pick<AxiosInstance, 'get' | 'put' | 'delete'>

/** Brouillon d'édition de l'appelant (autosave) — jamais une version publiée. */
export type DocumentDraft = components['schemas']['DraftView'] & {
  title: string
  body: { [key: string]: unknown }
  baseVersionNo: number
  updatedAt: string
}
export type DocumentDraftInput = Pick<NonNullable<DocumentDraft>, 'title' | 'body' | 'baseVersionNo'>

export const documentDraftKey = (documentId: string) => ['document-draft', documentId] as const

const draftUrl = (documentId: string) => `/api/v1/documents/${documentId}/draft`

function isDraft(x: unknown): x is DocumentDraft {
  if (!x || typeof x !== 'object') return false
  const d = x as Record<string, unknown>
  return typeof d.body === 'object' && d.body !== null && typeof d.baseVersionNo === 'number'
}

/** GET …/draft : le brouillon de l'appelant, ou `null` (404, ou réponse inexploitable). */
export async function getDocumentDraft(api: Pick<Api, 'get'>, documentId: string): Promise<DocumentDraft | null> {
  try {
    const res = await api.get<DocumentDraft>(draftUrl(documentId), {
      validateStatus: (s: number) => s === 200 || s === 404,
    })
    if (res.status === 404) return null
    return isDraft(res.data) ? res.data : null
  } catch (e) {
    if ((e as { response?: { status?: number } })?.response?.status === 404) return null
    throw e
  }
}

/** PUT …/draft : enregistre le brouillon (verrou d'édition requis, 409 sinon). */
export async function putDocumentDraft(
  api: Pick<Api, 'put'>,
  documentId: string,
  draft: DocumentDraftInput,
): Promise<DocumentDraft> {
  const { data } = await api.put<DocumentDraft>(draftUrl(documentId), {
    title: draft.title,
    body: draft.body,
    baseVersionNo: draft.baseVersionNo,
  })
  return data
}

/** DELETE …/draft : abandonne le brouillon (204 ; un 404 « déjà absent » est ignoré). */
export async function deleteDocumentDraft(api: Pick<Api, 'delete'>, documentId: string): Promise<void> {
  try {
    await api.delete(draftUrl(documentId))
  } catch (e) {
    if ((e as { response?: { status?: number } })?.response?.status === 404) return
    throw e
  }
}

/** Le brouillon repose sur une version plus ancienne que la version publiée courante. */
export function isDraftStale(draft: Pick<DocumentDraft, 'baseVersionNo'> | null, currentVersionNo?: number | null) {
  return Boolean(draft && currentVersionNo != null && (draft.baseVersionNo ?? 0) < currentVersionNo)
}
