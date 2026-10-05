// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../../lib/api'
import { listComments } from '../../lib/comments'
import type { DocumentDetail } from '../../lib/documents'
import type { DocTabsProps } from './DocumentChrome'
import { documentPermissions } from './documentPageUtils'

/** Props des onglets documentaires (desktop + barre mobile) pour Historique / Comparer. */
export function useHistoryTabProps(doc: Pick<DocumentDetail, 'id' | 'permissions'>): DocTabsProps {
  const navigate = useNavigate()
  const perms = documentPermissions(doc.permissions)
  const openComments = useQuery({
    queryKey: ['document-comments', doc.id, 'ouvert', ''],
    queryFn: () => listComments(api, doc.id, { status: 'ouvert' }),
    retry: false,
  })
  return {
    documentId: doc.id,
    current: 'history',
    mockPrefix: 'hist',
    showEdit: perms.canEdit,
    showAccess: perms.canManageAccess || perms.canEdit,
    openComments: openComments.data?.openThreadCount ?? 0,
    commentsOpen: false,
    // L'historique n'embarque pas le panneau : on ouvre la page de lecture avec les commentaires.
    onToggleComments: () => navigate(`/docs/${doc.id}?comments=1`),
  }
}
