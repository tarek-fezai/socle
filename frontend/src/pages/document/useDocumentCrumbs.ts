// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../../lib/api'
import { folderPath, getSpaceTree, spaceTreeKey } from '../../lib/folders'
import { getSpace } from '../../lib/spaces'
import type { DocumentDetail } from '../../lib/documents'
import type { Crumb } from './DocumentChrome'

/** Fil d'Ariane espace → dossiers → document (barre haute Historique / Comparer). */
export function useDocumentCrumbs(doc: Pick<DocumentDetail, 'id' | 'spaceId' | 'folderId' | 'title'>) {
  const spaceId = doc.spaceId
  const space = useQuery({
    queryKey: ['space', spaceId],
    queryFn: () => getSpace(api, spaceId),
    enabled: Boolean(spaceId),
  })
  const tree = useQuery({
    queryKey: spaceTreeKey(spaceId),
    queryFn: () => getSpaceTree(api, spaceId),
    enabled: Boolean(spaceId),
  })
  const spaceName = space.data?.name ?? tree.data?.spaceName ?? ''
  const folderId = doc.folderId ?? tree.data?.documents.find((d) => d.id === doc.id)?.folderId ?? null
  const crumbs = useMemo<Crumb[]>(() => {
    const items: Crumb[] = [{ label: spaceName || 'Espace', to: `/spaces/${spaceId}/tree` }]
    for (const f of folderPath(tree.data?.folders ?? [], folderId)) {
      items.push({ label: f.name, to: `/folders/${f.id}` })
    }
    items.push({ label: doc.title.trim() || 'Sans titre', to: `/docs/${doc.id}` })
    return items
  }, [spaceName, spaceId, tree.data?.folders, folderId, doc.title, doc.id])
  return { crumbs, spaceName }
}
