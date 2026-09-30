import type { AxiosInstance } from 'axios'

export type SearchHit = {
  id: string
  title: string
  excerpt: string
  spaceId: string
  spaceName: string
  docType: string | null
  status: string
  updatedAt: string | null
  rank: number
}

export type SearchResponse = {
  query: string
  results: SearchHit[]
  total: number
  /** Toujours true : total = présélection SQL avant Check OpenFGA. */
  totalIsEstimate?: boolean
  /** Présent si refill authz tronqué après 3 itérations. */
  warning?: string | null
}

export type SearchParams = {
  q: string
  spaceId?: string
  tag?: string
  docType?: string
  limit?: number
}

export async function searchDocuments(api: AxiosInstance, params: SearchParams) {
  const { data } = await api.get<SearchResponse>('/api/v1/search', {
    params: {
      q: params.q,
      spaceId: params.spaceId || undefined,
      tag: params.tag || undefined,
      docType: params.docType || undefined,
      limit: params.limit,
    },
  })
  return data
}
