// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'

export type LinkedDocument = { id: string; title: string }

/** Documents liés, déjà filtrés côté serveur par droit de lecture de l'appelant. */
export type DocumentLinks = {
  outgoing: LinkedDocument[]
  incoming: LinkedDocument[]
}

export async function getDocumentLinks(
  client: Pick<AxiosInstance, 'get'>,
  documentId: string,
): Promise<DocumentLinks> {
  const { data } = await client.get<DocumentLinks>(`/api/v1/documents/${documentId}/links`)
  return {
    outgoing: Array.isArray(data?.outgoing) ? data.outgoing : [],
    incoming: Array.isArray(data?.incoming) ? data.incoming : [],
  }
}

export const documentLinksKey = (documentId: string) => ['document-links', documentId] as const

/** Sortants puis entrants, dédoublonnés par id. */
export function mergeRelatedLinks(links: DocumentLinks | null | undefined): LinkedDocument[] {
  if (!links) return []
  const seen = new Set<string>()
  const out: LinkedDocument[] = []
  for (const d of [...links.outgoing, ...links.incoming]) {
    if (!d?.id || seen.has(d.id)) continue
    seen.add(d.id)
    out.push(d)
  }
  return out
}
