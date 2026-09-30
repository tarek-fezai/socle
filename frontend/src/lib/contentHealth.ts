import type { AxiosInstance } from 'axios'

export type StaleDocumentItem = {
  id: string
  title: string
  status: string
  contentModifiedAt: string
  ageDays: number
}

export type ContentHealth = {
  spaceId: string
  thresholdDays: number
  viewableDocumentCount: number
  staleCount: number
  staleDocuments: StaleDocumentItem[]
}

export async function fetchContentHealth(api: AxiosInstance, spaceId: string) {
  const { data } = await api.get<ContentHealth>(`/api/v1/spaces/${spaceId}/content-health`)
  return data
}
