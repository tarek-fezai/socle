// SPDX-License-Identifier: AGPL-3.0-or-later
import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../auth/AuthProvider'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import {
  VERSION_PAGE_SIZE,
  getDocument,
  listVersions,
  restoreVersion,
  type DocumentDetail,
  type VersionPage,
} from '../lib/documents'
import { useIsMobile } from '../lib/useMediaQuery'
import { DocumentMobileTabs, DocumentTabs } from './document/DocumentChrome'
import {
  HistoryMobileTop,
  HistoryTopBar,
  RestoreDialog,
  VersionAvatar,
} from './document/DocumentHistoryChrome'
import { documentPermissions } from './document/documentPageUtils'
import { useDocumentCrumbs } from './document/useDocumentCrumbs'
import { useHistoryTabProps } from './document/useHistoryTabProps'
import {
  buildVersionRows,
  formatLinesAdded,
  formatLinesRemoved,
  formatVersionDateTime,
  formatVersionDateTimeMobile,
  versionAuthor,
  versionsCountLabel,
  type VersionRow,
} from './document/versionHistoryUtils'
import './document/document-page.css'
import './document/document-history.css'

const NO_SUMMARY_LABEL = 'Aucun résumé'

/** Lien « Comparer » : la version contre sa précédente (route `/docs/:id/history/compare`). */
function compareHref(docId: string, from: number, to: number): string {
  return `/docs/${docId}/history/compare?from=${from}&to=${to}`
}

export function DocumentHistoryPage() {
  const { id = '' } = useParams()
  const doc = useQuery({
    queryKey: ['document', id],
    queryFn: () => getDocument(api, id),
    enabled: Boolean(id),
  })

  if (doc.isLoading) {
    return (
      <div className="doc-page doc-page--state" data-testid="history-loading">
        <p className="doc-state">Chargement…</p>
      </div>
    )
  }

  if (doc.isError || !doc.data) {
    return (
      <div className="doc-page doc-page--state">
        <Link to="/docs" className="doc-back">
          ← Documents
        </Link>
        <p className="doc-state doc-state--error" role="alert">
          Accès refusé ou document introuvable — l&apos;historique n&apos;est pas accessible.
        </p>
      </div>
    )
  }

  return <HistorySurface doc={doc.data} />
}

function HistorySurface({ doc }: { doc: DocumentDetail }) {
  const id = doc.id
  const qc = useQueryClient()
  const { me } = useAuth()
  const isMobile = useIsMobile()
  const perms = documentPermissions(doc.permissions)
  const isArchived = doc.status === 'archive'

  const [restoreTarget, setRestoreTarget] = useState<VersionRow | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [statusNote, setStatusNote] = useState<string | null>(null)

  const versions = useInfiniteQuery({
    queryKey: ['document-versions', id],
    queryFn: ({ pageParam }) => listVersions(api, id, { offset: pageParam, limit: VERSION_PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (last: VersionPage, pages) => {
      const loaded = pages.reduce((n, p) => n + p.items.length, 0)
      return last.items.length > 0 && loaded < last.total ? loaded : undefined
    },
  })

  const tabProps = useHistoryTabProps(doc)
  const { crumbs } = useDocumentCrumbs(doc)

  const loaded = useMemo(() => versions.data?.pages.flatMap((p) => p.items) ?? [], [versions.data])
  const total = versions.data?.pages[0]?.total ?? 0
  const rows = useMemo(
    () =>
      buildVersionRows(loaded, doc, {
        hasMore: Boolean(versions.hasNextPage),
      }),
    [loaded, doc, versions.hasNextPage],
  )
  const count = total
  const currentRow = rows.find((r) => r.isCurrent)

  const restore = useMutation({
    mutationFn: (versionNo: number) => restoreVersion(api, id, versionNo, doc.currentVersionNo ?? null),
    onSuccess: (updated) => {
      setActionError(null)
      setRestoreTarget(null)
      setStatusNote(
        updated.status === 'en_revue'
          ? `Version restaurée — statut ${updated.status} (nouvelle approbation requise si publication).`
          : `Version restaurée — statut ${updated.status}.`,
      )
      qc.setQueryData(['document', id], updated)
      void qc.invalidateQueries({ queryKey: ['document-versions', id] })
      void qc.invalidateQueries({ queryKey: ['documents'] })
      void qc.invalidateQueries({ queryKey: ['document-resolved', id] })
    },
    onError: (err) => setActionError(apiErrorMessage(err, 'Échec de la restauration')),
  })

  const canAct = perms.canEdit
  const openRestore = (row: VersionRow) => {
    setActionError(null)
    setStatusNote(null)
    setRestoreTarget(row)
  }

  const restoreDialog = (
    <RestoreDialog
      open={restoreTarget != null}
      onOpenChange={(o) => {
        if (!o) {
          setRestoreTarget(null)
          setActionError(null)
        }
      }}
      target={
        restoreTarget
          ? {
              versionNo: restoreTarget.versionNo,
              createdAt: restoreTarget.createdAt,
              authorName: versionAuthor(restoreTarget).name,
            }
          : null
      }
      currentVersionNo={doc.currentVersionNo}
      currentChangeSummary={currentRow?.changeSummary}
      status={doc.status}
      pending={restore.isPending}
      error={actionError}
      onConfirm={() => restoreTarget && restore.mutate(restoreTarget.versionNo)}
    />
  )

  const feedback = (
    <>
      {isArchived && (
        <p className="hist-banner" data-testid="history-archived">
          Document archivé — la restauration est désactivée (le backend refuse toute mutation).
        </p>
      )}
      {statusNote && (
        <p className="hist-note" role="status" data-testid="history-status-note">
          {statusNote}
        </p>
      )}
      {actionError && !restoreTarget && (
        <p className="hist-error" role="alert">
          {actionError}
        </p>
      )}
    </>
  )

  const listState = (
    <>
      {versions.isLoading && <p className="hist-empty">Chargement des versions…</p>}
      {versions.isError && (
        <p className="hist-error" role="alert">
          {apiErrorMessage(versions.error, 'Impossible de charger l’historique.')}
        </p>
      )}
      {versions.isSuccess && rows.length === 0 && (
        <p className="hist-empty">Aucune version publiée pour l&apos;instant.</p>
      )}
    </>
  )

  const more = versions.hasNextPage && (
    <div className="hist-more">
      <button
        type="button"
        className="hist-ghost"
        disabled={versions.isFetchingNextPage}
        onClick={() => void versions.fetchNextPage()}
        data-testid="history-more"
      >
        {versions.isFetchingNextPage ? 'Chargement…' : 'Afficher les versions précédentes'}
      </button>
    </div>
  )

  /* ------------------------------ Mobile ------------------------------ */

  if (isMobile) {
    return (
      <div className="doc-page hist-page" data-testid="document-history-page" data-mock-id="hist-page">
        <HistoryMobileTop title="Historique" backTo={`/docs/${id}`} />
        <div className="hist-m-list" data-mock-id="hist-m-list">
          {feedback}
          {listState}
          {rows.map((v, i) => {
            const author = versionAuthor(v)
            const showCompare = canAct && !v.isCurrent && v.previousVersionNo != null
            const showRestore = canAct && !v.isCurrent
            const hasActions = showCompare || showRestore
            return (
              <div className="hist-m-item" key={v.versionNo} data-testid={`history-row-${v.versionNo}`}>
                <VersionAvatar
                  author={author}
                  self={Boolean(me && v.authorId === me.id)}
                  large
                  mockId={`hist-m-avatar-${i}`}
                />
                <div className="hist-m-body">
                  <div className="hist-m-name" data-mock-id={`hist-m-name-${i}`}>
                    {author.name}{' '}
                    <span className="hist-m-ver">
                      · v{v.versionNo}
                      {v.isCurrent ? ' (actuelle)' : ''}
                    </span>
                  </div>
                  <div className={`hist-m-date${hasActions ? ' has-actions' : ''}`} data-mock-id={`hist-m-date-${i}`}>
                    {formatVersionDateTimeMobile(v.createdAt)}
                  </div>
                  {hasActions && (
                    <div className="hist-m-actions">
                      {showCompare && (
                        <Link
                          to={compareHref(id, v.previousVersionNo!, v.versionNo)}
                          className="hist-m-link"
                          data-mock-id={`hist-m-compare-${i}`}
                        >
                          Voir les changements →
                        </Link>
                      )}
                      {showRestore && (
                        <button
                          type="button"
                          className="hist-m-link hist-m-link--muted"
                          disabled={isArchived}
                          onClick={() => openRestore(v)}
                          data-mock-id={`hist-m-restore-${i}`}
                        >
                          Restaurer cette version
                        </button>
                      )}
                    </div>
                  )}
                </div>
              </div>
            )
          })}
          {more}
        </div>
        <DocumentMobileTabs {...tabProps} />
        {restoreDialog}
      </div>
    )
  }

  /* ------------------------------ Desktop ------------------------------ */

  return (
    <div className="doc-page hist-page" data-testid="document-history-page" data-mock-id="hist-page">
      <HistoryTopBar crumbs={crumbs} mockPrefix="hist">
        <Link to={`/docs/${id}`} className="hist-ghost" data-mock-id="hist-back">
          Retour à la page
        </Link>
      </HistoryTopBar>
      <DocumentTabs {...tabProps} />

      <div className="hist-content">
        <div className="hist-column">
          <h1 className="hist-title" data-mock-id="hist-title">
            Historique des versions
          </h1>
          {versions.isSuccess && (
            <p className="hist-sub" data-mock-id="hist-subtitle" data-testid="history-count">
              {versionsCountLabel(count)}
            </p>
          )}
          {feedback}
          {listState}

          <ol className="hist-timeline" data-testid="history-timeline">
            {rows.map((v, i) => {
              const author = versionAuthor(v)
              const added = formatLinesAdded(v.linesAdded)
              const removed = formatLinesRemoved(v.linesRemoved)
              const showCompare = canAct && !v.isCurrent && v.previousVersionNo != null
              const showRestore = canAct && !v.isCurrent
              const mid = (field: string) => `hist-r${i}-${field}`
              return (
                <li
                  key={v.versionNo}
                  className="hist-item"
                  data-testid={`history-row-${v.versionNo}`}
                  data-current={v.isCurrent ? 'true' : undefined}
                >
                  <span className={`hist-dot${v.isCurrent ? ' is-current' : ''}`} aria-hidden data-mock-id={mid('dot')} />
                  <div className="hist-item-head">
                    <span className="hist-v" data-mock-id={mid('version')}>
                      v{v.versionNo}
                    </span>
                    <span className="hist-date" data-mock-id={mid('date')}>
                      {formatVersionDateTime(v.createdAt)}
                    </span>
                    {v.isCurrent && (
                      <span className="hist-current" data-mock-id={mid('badge')} data-testid="history-current-badge">
                        <span className="hist-current-dot" aria-hidden />
                        Actuelle
                      </span>
                    )}
                  </div>
                  <div className="hist-summary" data-mock-id={mid('summary')}>
                    {v.changeSummary?.trim() ? v.changeSummary : NO_SUMMARY_LABEL}
                  </div>
                  <div className="hist-item-foot">
                    <div className="hist-meta">
                      <div className="hist-author">
                        <VersionAvatar
                          author={author}
                          self={Boolean(me && v.authorId === me.id)}
                          mockId={mid('avatar')}
                        />
                        <span className="hist-author-name" data-mock-id={mid('author')}>
                          {author.name}
                        </span>
                      </div>
                      {added && (
                        <span className="hist-added" data-mock-id={mid('added')}>
                          {added}
                        </span>
                      )}
                      {removed && (
                        <span className="hist-removed" data-mock-id={mid('removed')}>
                          {removed}
                        </span>
                      )}
                    </div>
                    {(showCompare || showRestore) && (
                      <div className="hist-row-actions">
                        {showCompare && (
                          <Link
                            to={compareHref(id, v.previousVersionNo!, v.versionNo)}
                            className="hist-link"
                            data-mock-id={mid('compare')}
                            title={`Comparer la v${v.versionNo} à la v${v.previousVersionNo}`}
                          >
                            Comparer
                          </Link>
                        )}
                        {showRestore && (
                          <button
                            type="button"
                            className="hist-link"
                            disabled={isArchived || restore.isPending}
                            title={isArchived ? 'Document archivé — restauration impossible' : 'Restaurer cette version'}
                            data-mock-id={mid('restore')}
                            onClick={() => openRestore(v)}
                          >
                            Restaurer
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                </li>
              )
            })}
          </ol>
          {more}
        </div>
      </div>

      <DocumentMobileTabs {...tabProps} />
      {restoreDialog}
    </div>
  )
}
