// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'

export type FeedbackTotals = { yes: number; no: number }

export type DocumentFeedback = {
  /** null = pas de vote */
  myVote: boolean | null
  /** Présent uniquement pour les éditeurs du document (champ omis sinon). */
  totals?: FeedbackTotals | null
}

type Client = Pick<AxiosInstance, 'get' | 'put'>

export async function getFeedback(client: Client, documentId: string): Promise<DocumentFeedback> {
  const { data } = await client.get<DocumentFeedback>(`/api/v1/documents/${documentId}/feedback`)
  return data
}

export async function putFeedback(
  client: Client,
  documentId: string,
  helpful: boolean,
): Promise<DocumentFeedback> {
  const { data } = await client.put<DocumentFeedback>(`/api/v1/documents/${documentId}/feedback`, {
    helpful,
  })
  return data
}

export const feedbackKey = (documentId: string) => ['document-feedback', documentId] as const
