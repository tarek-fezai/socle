// SPDX-License-Identifier: AGPL-3.0-or-later
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useOutletContext, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CommentSelectionButton, CommentsPanel } from '../../components/CommentsPanel'
import { StaleBadge } from '../../components/StaleBadge'
import type { ShellOutletContext } from '../../components/shell/shellUtils'
import { api } from '../../lib/api'
import { apiErrorMessage } from '../../lib/approvals'
import {
  acknowledgeAttestation,
  attestationKey,
  getActiveAttestation,
  shouldShowAttestationBanner,
} from '../../lib/attestations'
import {
  canCommentOnSpace,
  listComments,
  type CommentAnchorInput,
} from '../../lib/comments'
import { getResolvedDocument, recordDocumentView, type TipTapNode } from '../../lib/documents'
import { documentLinksKey, getDocumentLinks, mergeRelatedLinks } from '../../lib/documentLinks'
import { downloadExport } from '../../lib/export'
import { feedbackKey, getFeedback, putFeedback } from '../../lib/feedback'
import { folderPath, getSpaceTree, spaceTreeKey } from '../../lib/folders'
import { reliabilityLevelLabel } from '../../lib/reliability'
import { getSpace } from '../../lib/spaces'
import { placeholderConflictMessage } from '../../lib/templates'
import {
  AttestationBanner,
  DocumentMobileTabs,
  DocumentMobileTop,
  DocumentTabs,
  DocumentTopBar,
  FeedbackRow,
  type Crumb,
} from './DocumentChrome'
import { DocumentRail } from './DocumentRail'
import { TipTapReadView, analyzeBody } from './TipTapReadView'
import {
  documentPermissions,
  formatLongDateFr,
  formatShortDateFr,
  revisedAt,
  statusMeta,
  tagColors,
} from './documentPageUtils'
import './document-page.css'

/** Hauteur cumulée barre haute + onglets (sticky) pour le décalage de défilement. */
const SCROLL_OFFSET = 120

export function DocumentReadPage() {
  const { id = '' } = useParams()
  const [searchParams] = useSearchParams()
  const qc = useQueryClient()
  const shell = useOutletContext<ShellOutletContext | null | undefined>()

  const [commentsOpen, setCommentsOpen] = useState(
    () => searchParams.get('comments') === '1' || searchParams.get('comments') === 'open',
  )
  const [draftAnchor, setDraftAnchor] = useState<CommentAnchorInput | null>(null)
  const [infoOpen, setInfoOpen] = useState(false)
  const [activeId, setActiveId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [publishMsg, setPublishMsg] = useState<string | null>(null)
  const [exporting, setExporting] = useState(false)
  const contentRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (searchParams.get('comments') === '1' || searchParams.get('comments') === 'open') {
      setCommentsOpen(true)
    }
  }, [searchParams])

  /* ---------------- données ---------------- */

  const doc = useQuery({
    queryKey: ['document-resolved', id],
    queryFn: () => getResolvedDocument(api, id),
    enabled: Boolean(id),
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: true,
  })

  const spaceId = doc.data?.spaceId ?? ''
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
  const comments = useQuery({
    queryKey: ['document-comments', id, 'ouvert', ''],
    queryFn: () => listComments(api, id, { status: 'ouvert' }),
    enabled: Boolean(id),
  })
  const feedback = useQuery({
    queryKey: feedbackKey(id),
    queryFn: () => getFeedback(api, id),
    enabled: Boolean(id),
    retry: false,
  })
  const attestation = useQuery({
    queryKey: attestationKey(id),
    queryFn: () => getActiveAttestation(api, id),
    enabled: Boolean(id),
    retry: false,
  })
  const links = useQuery({
    queryKey: documentLinksKey(id),
    queryFn: () => getDocumentLinks(api, id),
    enabled: Boolean(id),
    retry: false,
  })
  useEffect(() => {
    if (!id) return
    void recordDocumentView(api, id).catch(() => {
      // compteur non bloquant
    })
  }, [id])

  /* ---------------- dérivés ---------------- */

  const body = useMemo(
    () => (doc.data?.body ?? { type: 'doc', content: [] }) as TipTapNode,
    [doc.data?.body],
  )
  const analysis = useMemo(() => analyzeBody(body), [body])
  const related = useMemo(() => mergeRelatedLinks(links.data), [links.data])

  const perms = documentPermissions(doc.data?.permissions)
  const canComment =
    perms.canComment || (space.data ? canCommentOnSpace(space.data) : false)
  const openCount = comments.data?.openThreadCount ?? 0

  const highlightAnchors = useMemo(() => {
    const threads = comments.data?.threads ?? []
    return threads
      .filter((t) => t.anchor?.exact && t.anchor.attached !== false)
      .map((t) => ({ exact: t.anchor!.exact, attached: t.anchor!.attached }))
  }, [comments.data])

  const spaceName = space.data?.name ?? tree.data?.spaceName ?? ''
  const folderId = doc.data?.folderId ?? tree.data?.documents.find((d) => d.id === id)?.folderId ?? null

  const crumbs = useMemo<Crumb[]>(() => {
    const items: Crumb[] = []
    if (doc.data) {
      items.push({ label: spaceName || 'Espace', to: `/spaces/${doc.data.spaceId}/tree` })
      for (const f of folderPath(tree.data?.folders ?? [], folderId)) {
        items.push({ label: f.name, to: `/folders/${f.id}` })
      }
      items.push({ label: doc.data.title || 'Sans titre' })
    }
    return items
  }, [doc.data, spaceName, tree.data?.folders, folderId])

  /* ---------------- actions ---------------- */

  const acknowledge = useMutation({
    mutationFn: (campaignId: string) => acknowledgeAttestation(api, id, campaignId),
    onSuccess: (updated) => {
      setActionError(null)
      qc.setQueryData(attestationKey(id), updated)
      void qc.invalidateQueries({ queryKey: attestationKey(id) })
    },
    onError: (e) => setActionError(apiErrorMessage(e, "Impossible d'enregistrer l'accusé de lecture")),
  })

  const vote = useMutation({
    mutationFn: (helpful: boolean) => putFeedback(api, id, helpful),
    onSuccess: (updated) => qc.setQueryData(feedbackKey(id), updated),
  })

  const publish = useMutation({
    mutationFn: async () => {
      const { data } = await api.post<{ approvalRequestId: string; temporalWorkflowId: string }>(
        `/api/v1/documents/${id}/approvals`,
      )
      return data
    },
    onSuccess: () => {
      setActionError(null)
      setPublishMsg("Document soumis pour approbation.")
      void qc.invalidateQueries({ queryKey: ['document-resolved', id] })
      void qc.invalidateQueries({ queryKey: ['document', id] })
      void qc.invalidateQueries({ queryKey: ['documents'] })
      void qc.invalidateQueries({ queryKey: ['approval', id] })
      void qc.invalidateQueries({ queryKey: ['approvals', 'mine'] })
    },
    onError: (err) => {
      setPublishMsg(null)
      setActionError(
        placeholderConflictMessage(err) ?? apiErrorMessage(err, 'Échec de la soumission pour approbation'),
      )
    },
  })

  const onDownloadPdf = useCallback(async () => {
    if (!doc.data) return
    setExporting(true)
    setActionError(null)
    try {
      await downloadExport(api, 'document', id, `${doc.data.title || 'document'}.pdf`)
    } catch (e) {
      setActionError(apiErrorMessage(e, "Échec de l'export PDF"))
    } finally {
      setExporting(false)
    }
  }, [doc.data, id])

  const onPrint = useCallback(() => window.print(), [])

  const scrollToHeading = useCallback((hid: string) => {
    const el = document.getElementById(hid)
    if (!el) return
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
    setActiveId(hid)
    try {
      window.history.replaceState(window.history.state, '', `#${hid}`)
    } catch {
      // ignore
    }
  }, [])

  const toggleComments = useCallback(() => {
    setCommentsOpen((v) => !v)
    setDraftAnchor(null)
  }, [])

  /* ---------------- scroll spy ---------------- */

  const tocIds = useMemo(() => {
    const ids = analysis.headings.map((h) => h.id)
    if (related.length > 0) ids.push('documents-lies')
    return ids
  }, [analysis.headings, related.length])

  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined' || tocIds.length === 0) return
    const visible = new Set<string>()
    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (e.isIntersecting) visible.add(e.target.id)
          else visible.delete(e.target.id)
        }
        const first = tocIds.find((tid) => visible.has(tid))
        if (first) setActiveId(first)
      },
      { rootMargin: `-${SCROLL_OFFSET}px 0px -55% 0px`, threshold: 0 },
    )
    for (const tid of tocIds) {
      const el = document.getElementById(tid)
      if (el) io.observe(el)
    }
    return () => io.disconnect()
  }, [tocIds, doc.data?.id])

  /* ---------------- rendu ---------------- */

  if (doc.isLoading) {
    return (
      <div className="doc-page doc-page--state" data-testid="doc-loading">
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
          Document introuvable ou accès refusé.
        </p>
      </div>
    )
  }

  const d = doc.data
  const st = statusMeta(d.status)
  const revised = revisedAt(d)
  const revisedLong = formatLongDateFr(revised)
  const revisedShort = formatShortDateFr(revised)
  const showEdit = perms.canEdit
  const showAccess = perms.canManageAccess || perms.canEdit
  const showPublish = perms.canPublish
  const banner = attestation.data
  const tags = d.tags ?? []

  const tabProps = {
    documentId: id,
    showEdit,
    showAccess,
    openComments: openCount,
    commentsOpen,
    onToggleComments: toggleComments,
  }

  const mobileActions = (
    <>
      <Link to={`/spaces/${d.spaceId}/graph`} className="doc-rail-action">
        Graphe
      </Link>
      <button type="button" className="doc-rail-action" onClick={onPrint}>
        Imprimer
      </button>
      <button type="button" className="doc-rail-action" disabled={exporting} onClick={() => void onDownloadPdf()}>
        Télécharger en PDF
      </button>
      <Link to={`/docs/${id}/export`} className="doc-rail-action">
        Options d&apos;export avancées →
      </Link>
      {showPublish && (
        <button
          type="button"
          className="doc-rail-action doc-rail-action--primary"
          disabled={publish.isPending}
          onClick={() => publish.mutate()}
        >
          {publish.isPending ? 'Publication…' : 'Publier'}
        </button>
      )}
    </>
  )

  return (
    <div className="doc-page" data-testid="document-read-page" data-mock-id="doc-page">
      <DocumentMobileTop
        title={d.title}
        spaceName={spaceName}
        onOpenMenu={shell?.openMenu}
        onOpenSearch={shell?.openSearch}
      />
      <DocumentTopBar
        documentId={id}
        spaceId={d.spaceId}
        crumbs={crumbs}
        showPublish={showPublish}
        publishing={publish.isPending}
        onPublish={() => publish.mutate()}
        onPrint={onPrint}
        onDownloadPdf={() => void onDownloadPdf()}
        exporting={exporting}
      />
      <DocumentTabs {...tabProps} />

      <div className={`doc-content${commentsOpen ? ' doc-content--comments' : ''}`}>
        <main className="doc-main" data-mock-id="doc-main">
          <div className="doc-status" data-mock-id="doc-status">
            <span className="doc-status-badge" style={{ color: st.color }} data-testid="doc-status" data-mock-id="doc-status-badge">
              <span className="doc-dot" style={{ background: st.color }} />
              {st.label}
            </span>
            <span className="doc-sep doc-only-desktop" />
            <span className="doc-status-text doc-only-desktop" data-testid="doc-reliability-label" data-mock-id="doc-status-reliability">
              {reliabilityLevelLabel(d.reliabilityScore)}
            </span>
            <span className="doc-sep" />
            <span className="doc-status-text doc-only-desktop" data-testid="doc-revised" data-mock-id="doc-status-revised">
              {revisedLong ? `Révisé le ${revisedLong}` : ''}
            </span>
            <span className="doc-status-text doc-only-mobile" data-mock-id="doc-status-revised-mobile">
              {revisedShort ? `Révisé le ${revisedShort}` : ''}
            </span>
            {d.stale ? <StaleBadge stale contentModifiedAt={d.contentModifiedAt} compact /> : null}
          </div>

          {actionError && (
            <p className="doc-alert doc-alert--error" role="alert" data-testid="doc-action-error">
              {actionError}
            </p>
          )}
          {publishMsg && !actionError && (
            <p className="doc-alert" role="status" data-testid="doc-publish-msg">
              {publishMsg}
            </p>
          )}

          {banner && shouldShowAttestationBanner(banner) && (
            <AttestationBanner
              attestation={banner}
              docType={d.docType}
              spaceName={spaceName}
              pending={acknowledge.isPending}
              error={acknowledge.isError ? actionError : null}
              onAcknowledge={() => acknowledge.mutate(banner.campaignId)}
            />
          )}

          <h1 className="doc-title" data-mock-id="doc-title">
            {d.title}
          </h1>

          {tags.length > 0 && (
            <div className="doc-tags doc-tags--mobile" data-mock-id="doc-mobile-tags">
              {tags.map((t, i) => {
                const c = tagColors(t, i)
                return (
                  <Link
                    key={t.id}
                    to={`/search?q=${encodeURIComponent(t.name)}`}
                    className="doc-tag"
                    style={{ color: c.fg, background: c.bg }}
                  >
                    {t.name}
                  </Link>
                )
              })}
            </div>
          )}

          <div className="doc-body" ref={contentRef} data-comment-root data-mock-id="doc-body">
            <TipTapReadView body={body} anchors={highlightAnchors} analysis={analysis} />
            <CommentSelectionButton
              rootRef={contentRef}
              enabled={canComment}
              onComment={(anchor) => {
                setDraftAnchor(anchor)
                setCommentsOpen(true)
              }}
            />
          </div>

          {related.length > 0 && (
            <section aria-labelledby="documents-lies" data-testid="related-documents">
              <h2 id="documents-lies" className="doc-h doc-h2 doc-h2--plain" data-mock-id="doc-related-title">
                Documents liés
              </h2>
              <div className="doc-related">
                {related.map((r, i) => (
                  <Link
                    key={r.id}
                    to={`/docs/${r.id}`}
                    className="doc-related-link"
                    data-mock-id={i === 0 ? 'doc-related-link' : undefined}
                  >
                    {r.title} →
                  </Link>
                ))}
              </div>
            </section>
          )}

          <FeedbackRow
            myVote={feedback.data?.myVote}
            pending={vote.isPending}
            onVote={(helpful) => vote.mutate(helpful)}
            totals={feedback.data?.totals ?? null}
          />
        </main>

        <DocumentRail
          doc={d}
          spaceName={spaceName}
          headings={analysis.headings}
          hasRelatedLinks={related.length > 0}
          activeId={activeId}
          onNavigate={scrollToHeading}
          canEdit={perms.canEdit}
          canViewAccess={showAccess}
          infoOpen={infoOpen}
          onToggleInfo={() => setInfoOpen((v) => !v)}
          mobileActions={mobileActions}
        />

        {commentsOpen && (
          <div className="doc-comments-col" data-testid="doc-comments-col">
            <CommentsPanel
              documentId={id}
              versionNo={d.currentVersionNo}
              canComment={canComment}
              spaceId={d.spaceId}
              draftAnchor={draftAnchor}
              onDraftAnchorClear={() => setDraftAnchor(null)}
              className="doc-comments-panel"
              onClose={() => {
                setCommentsOpen(false)
                setDraftAnchor(null)
              }}
            />
          </div>
        )}
      </div>

      <DocumentMobileTabs {...tabProps} />
    </div>
  )
}
