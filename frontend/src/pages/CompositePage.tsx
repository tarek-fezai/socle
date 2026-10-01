// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { CommentsPanel, CommentSelectionButton } from '../components/CommentsPanel'
import { StaleBadge } from '../components/StaleBadge'
import { api } from '../lib/api'
import {
  canCommentOnSpace,
  highlightAnchorsHtml,
  listComments,
  type CommentAnchorInput,
} from '../lib/comments'
import { getResolvedDocument, type TipTapNode } from '../lib/documents'
import { getSpace } from '../lib/spaces'

function textOf(node: TipTapNode | undefined): string {
  if (!node) return ''
  if (node.type === 'text' && typeof node.text === 'string') return node.text
  const kids = Array.isArray(node.content) ? node.content : []
  return kids.map((c) => textOf(c as TipTapNode)).join('')
}

function TransclusionBlock({
  node,
  anchors,
}: {
  node: TipTapNode
  anchors: Array<{ exact: string; attached?: boolean }>
}) {
  const attrs = (node.attrs ?? {}) as Record<string, unknown>
  const accessible = attrs.accessible === true

  if (!accessible) {
    return (
      <aside
        className="my-4 rounded-lg border border-dashed border-socle-line bg-socle-soft px-4 py-3 text-sm text-socle-muted"
        data-testid="transclusion-denied"
      >
        Contenu non accessible
      </aside>
    )
  }

  const title = typeof attrs.title === 'string' ? attrs.title : 'Document'
  const documentId = typeof attrs.documentId === 'string' ? attrs.documentId : null
  const nested = Array.isArray(node.content) ? (node.content[0] as TipTapNode | undefined) : undefined

  return (
    <section
      className="my-6 border-l-2 border-socle-accent pl-4"
      data-testid="transclusion-ok"
    >
      <div className="mb-2 flex flex-wrap items-baseline gap-3">
        <h2 className="font-display text-xl text-socle-ink">{title}</h2>
        {documentId ? (
          <Link
            to={`/docs/${documentId}`}
            className="text-sm font-semibold text-socle-accent underline-offset-4 hover:underline"
          >
            Ouvrir la source →
          </Link>
        ) : null}
      </div>
      {nested ? <TipTapView node={nested} anchors={anchors} /> : null}
    </section>
  )
}

function TipTapView({
  node,
  anchors = [],
}: {
  node: TipTapNode
  anchors?: Array<{ exact: string; attached?: boolean }>
}) {
  if (!node || typeof node !== 'object') return null
  const type = String(node.type ?? '')

  if (type === 'transclusion') {
    return <TransclusionBlock node={node} anchors={anchors} />
  }

  if (type === 'doc' || type === 'blockquote') {
    const kids = Array.isArray(node.content) ? node.content : []
    return (
      <div className={type === 'blockquote' ? 'border-l border-socle-line pl-3 text-socle-slate' : ''}>
        {kids.map((child, i) => (
          <TipTapView key={i} node={child as TipTapNode} anchors={anchors} />
        ))}
      </div>
    )
  }

  if (type === 'paragraph') {
    const plain = textOf(node)
    if (anchors.length > 0) {
      return (
        <p
          className="mb-3 leading-relaxed text-socle-ink"
          dangerouslySetInnerHTML={{ __html: highlightAnchorsHtml(plain, anchors) }}
        />
      )
    }
    return <p className="mb-3 leading-relaxed text-socle-ink">{plain}</p>
  }

  if (type === 'heading') {
    const level = Number((node.attrs as { level?: number } | undefined)?.level ?? 2)
    const Tag = (`h${Math.min(6, Math.max(1, level))}` as 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6')
    const plain = textOf(node)
    if (anchors.length > 0) {
      return (
        <Tag
          className="mb-3 font-display text-socle-ink"
          dangerouslySetInnerHTML={{ __html: highlightAnchorsHtml(plain, anchors) }}
        />
      )
    }
    return <Tag className="mb-3 font-display text-socle-ink">{plain}</Tag>
  }

  if (type === 'bulletList' || type === 'orderedList') {
    const List = type === 'bulletList' ? 'ul' : 'ol'
    const kids = Array.isArray(node.content) ? node.content : []
    return (
      <List className="mb-3 list-inside list-disc space-y-1 text-socle-ink">
        {kids.map((child, i) => (
          <li key={i}>{textOf(child as TipTapNode)}</li>
        ))}
      </List>
    )
  }

  const kids = Array.isArray(node.content) ? node.content : []
  if (kids.length) {
    return (
      <>
        {kids.map((child, i) => (
          <TipTapView key={i} node={child as TipTapNode} anchors={anchors} />
        ))}
      </>
    )
  }
  return null
}

export function CompositePage() {
  const { id = '' } = useParams()
  const [searchParams] = useSearchParams()
  const [commentsOpen, setCommentsOpen] = useState(
    () => searchParams.get('comments') === '1' || searchParams.get('comments') === 'open',
  )
  const [draftAnchor, setDraftAnchor] = useState<CommentAnchorInput | null>(null)
  const contentRef = useRef<HTMLDivElement>(null)

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

  const comments = useQuery({
    queryKey: ['document-comments', id, 'ouvert', ''],
    queryFn: () => listComments(api, id, { status: 'ouvert' }),
    enabled: Boolean(id),
  })

  useEffect(() => {
    if (searchParams.get('comments') === '1' || searchParams.get('comments') === 'open') {
      setCommentsOpen(true)
    }
  }, [searchParams])

  const highlightAnchors = useMemo(() => {
    const threads = comments.data?.threads ?? []
    return threads
      .filter((t) => t.anchor?.exact && t.anchor.attached !== false)
      .map((t) => ({ exact: t.anchor!.exact, attached: t.anchor!.attached }))
  }, [comments.data])

  if (doc.isLoading) {
    return <main className="page-shell text-socle-muted">Chargement…</main>
  }

  if (doc.isError || !doc.data) {
    return (
      <main className="page-shell">
        <Link to="/docs" className="text-sm font-semibold text-socle-accent">
          ← Documents
        </Link>
        <p className="mt-4 text-socle-danger">Document introuvable ou accès refusé.</p>
      </main>
    )
  }

  const body = (doc.data.body ?? { type: 'doc', content: [] }) as TipTapNode
  const canComment = space.data ? canCommentOnSpace(space.data) : false
  const openCount = comments.data?.openThreadCount ?? 0

  return (
    <div className="flex min-h-[calc(100vh-0px)]">
      <main className={`page-shell min-w-0 flex-1 ${commentsOpen ? 'max-w-none' : 'max-w-3xl'}`}>
        <div className="mb-6 flex flex-wrap items-center gap-4">
          <Link to="/docs" className="text-sm font-semibold text-socle-accent">
            ← Documents
          </Link>
          <Link
            to={`/docs/${id}`}
            className="text-sm font-semibold text-socle-accent underline-offset-4 hover:underline"
          >
            Éditer
          </Link>
          <Link
            to={`/docs/${id}/export`}
            className="text-sm font-semibold text-socle-accent underline-offset-4 hover:underline"
          >
            Exporter
          </Link>
          <button
            type="button"
            onClick={() => setCommentsOpen((v) => !v)}
            className="inline-flex items-center gap-1.5 text-sm font-semibold text-socle-accent underline-offset-4 hover:underline"
            data-testid="toggle-comments"
          >
            Commentaires
            {openCount > 0 && (
              <span className="rounded-lg bg-socle-accent px-1.5 py-0.5 text-[10px] font-semibold text-white">
                {openCount}
              </span>
            )}
          </button>
        </div>

        <p className="mb-2 text-xs font-medium uppercase tracking-[0.15em] text-socle-muted">
          Page composite
        </p>
        <h1 className="font-display text-4xl text-socle-ink">{doc.data.title}</h1>
        <div className="mt-3">
          <StaleBadge stale={doc.data.stale} contentModifiedAt={doc.data.contentModifiedAt} />
        </div>
        <div className="relative mt-8" ref={contentRef} data-comment-root>
          <TipTapView node={body} anchors={highlightAnchors} />
          <CommentSelectionButton
            rootRef={contentRef}
            enabled={canComment}
            onComment={(anchor) => {
              setDraftAnchor(anchor)
              setCommentsOpen(true)
            }}
          />
        </div>
      </main>
      {commentsOpen && (
        <CommentsPanel
          documentId={id}
          versionNo={doc.data.currentVersionNo}
          canComment={canComment}
          spaceId={doc.data.spaceId}
          draftAnchor={draftAnchor}
          onDraftAnchorClear={() => setDraftAnchor(null)}
          onClose={() => {
            setCommentsOpen(false)
            setDraftAnchor(null)
          }}
        />
      )}
    </div>
  )
}
