// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { AxiosInstance } from 'axios'

export type GraphNode = {
  id: string
  title: string
  spaceId: string
}

export type GraphEdge = {
  sourceId: string
  targetId: string
  kind: 'intra' | 'inter' | string
}

export type SpaceGraph = {
  spaceId: string
  nodes: GraphNode[]
  edges: GraphEdge[]
}

export async function fetchSpaceGraph(api: AxiosInstance, spaceId: string) {
  const { data } = await api.get<SpaceGraph>(`/api/v1/spaces/${spaceId}/graph`)
  return data
}
