export type TrashItem = {
  id: string
  resourceType: string
  resourceId: string
  title: string
  snapshot: Record<string, unknown> | null
  deletedBy: string
  deletedByName: string | null
  deletedAt: string
  purgeAt: string
}

export type TrashPage = {
  items: TrashItem[]
  offset: number
  limit: number
  total: number
}

export type TrashResourceTypeFilter = '' | 'document' | 'folder' | 'space'

export async function listTrash(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  opts: { resourceType?: TrashResourceTypeFilter; offset?: number; limit?: number } = {},
): Promise<TrashPage> {
  const params = new URLSearchParams()
  if (opts.resourceType) params.set('resourceType', opts.resourceType)
  if (opts.offset != null) params.set('offset', String(opts.offset))
  if (opts.limit != null) params.set('limit', String(opts.limit))
  const qs = params.toString()
  const { data } = await api.get<TrashPage>(`/api/v1/trash${qs ? `?${qs}` : ''}`)
  return data
}

export async function restoreTrashItem(
  api: { post: <T>(url: string, body?: unknown) => Promise<{ data: T }> },
  trashItemId: string,
): Promise<{ documents: number; folders: number; spaces: number }> {
  const { data } = await api.post<{ documents: number; folders: number; spaces: number }>(
    `/api/v1/trash/${trashItemId}/restore`,
  )
  return data
}

export function resourceTypeLabel(type: string): string {
  switch (type) {
    case 'document':
      return 'document'
    case 'folder':
      return 'dossier'
    case 'space':
      return 'espace'
    default:
      return type
  }
}

/** Relatif : « il y a 4 jours », « il y a 2 heures », « à l'instant ». */
export function formatDeletedAgo(iso: string, now = new Date()): string {
  const then = new Date(iso).getTime()
  const diffMs = Math.max(0, now.getTime() - then)
  const minutes = Math.floor(diffMs / 60_000)
  if (minutes < 1) return "à l'instant"
  if (minutes < 60) return `il y a ${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `il y a ${hours} h`
  const days = Math.floor(hours / 24)
  if (days === 1) return 'il y a 1 jour'
  return `il y a ${days} jours`
}

/**
 * Temps restant avant purge — « purgé dans 12 jours ».
 * Urgent (&lt; 7 jours) : le consommateur peut souligner en danger.
 */
export function formatPurgeRemaining(
  purgeAtIso: string,
  now = new Date(),
): { label: string; urgent: boolean } {
  const purgeAt = new Date(purgeAtIso).getTime()
  const diffMs = purgeAt - now.getTime()
  if (diffMs <= 0) return { label: 'purge imminente', urgent: true }
  const days = Math.ceil(diffMs / 86_400_000)
  if (days <= 1) return { label: 'purgé dans moins d’un jour', urgent: true }
  return { label: `purgé dans ${days} jours`, urgent: days < 7 }
}
