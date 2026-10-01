// SPDX-License-Identifier: AGPL-3.0-or-later
import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { StaleBadge } from '../components/StaleBadge'
import { api } from '../lib/api'
import { getResolvedDocument, type TipTapNode } from '../lib/documents'

function textOf(node: TipTapNode | undefined): string {
  if (!node) return ''
  if (node.type === 'text' && typeof node.text === 'string') return node.text
  const kids = Array.isArray(node.content) ? node.content : []
  return kids.map((c) => textOf(c as TipTapNode)).join('')
}

function TransclusionBlock({ node }: { node: TipTapNode }) {
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
      {nested ? <TipTapView node={nested} /> : null}
    </section>
  )
}

function TipTapView({ node }: { node: TipTapNode }) {
  if (!node || typeof node !== 'object') return null
  const type = String(node.type ?? '')

  if (type === 'transclusion') {
    return <TransclusionBlock node={node} />
  }

  if (type === 'doc' || type === 'blockquote') {
    const kids = Array.isArray(node.content) ? node.content : []
    return (
      <div className={type === 'blockquote' ? 'border-l border-socle-line pl-3 text-socle-slate' : ''}>
        {kids.map((child, i) => (
          <TipTapView key={i} node={child as TipTapNode} />
        ))}
      </div>
    )
  }

  if (type === 'paragraph') {
    return <p className="mb-3 leading-relaxed text-socle-ink">{textOf(node)}</p>
  }

  if (type === 'heading') {
    const level = Number((node.attrs as { level?: number } | undefined)?.level ?? 2)
    const Tag = (`h${Math.min(6, Math.max(1, level))}` as 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6')
    return <Tag className="mb-3 font-display text-socle-ink">{textOf(node)}</Tag>
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
          <TipTapView key={i} node={child as TipTapNode} />
        ))}
      </>
    )
  }
  return null
}

export function CompositePage() {
  const { id = '' } = useParams()

  const doc = useQuery({
    queryKey: ['document-resolved', id],
    queryFn: () => getResolvedDocument(api, id),
    enabled: Boolean(id),
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: true,
  })

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

  return (
    <main className="page-shell max-w-3xl">
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
      </div>

      <p className="mb-2 text-xs font-medium uppercase tracking-[0.15em] text-socle-muted">
        Page composite
      </p>
      <h1 className="font-display text-4xl text-socle-ink">{doc.data.title}</h1>
      <div className="mt-3">
        <StaleBadge stale={doc.data.stale} contentModifiedAt={doc.data.contentModifiedAt} />
      </div>
      <div className="mt-8">
        <TipTapView node={body} />
      </div>
    </main>
  )
}
