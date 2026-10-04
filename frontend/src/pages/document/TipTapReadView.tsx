// SPDX-License-Identifier: AGPL-3.0-or-later
import { Fragment, useMemo, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import DOMPurify from 'dompurify'
import { AttachmentFile, AttachmentImage } from '../../components/attachments/AttachmentViews'
import { RichButton, RichVideo } from '../../components/rich-blocks/RichBlockViews'
import { formatDateFr } from '../../components/rich-blocks/richBlockUtils'
import { highlightAnchorsHtml, isSafeHttpUrl } from '../../lib/comments'
import type { TipTapNode } from '../../lib/documents'
import { PLACEHOLDER_NODE_TYPE, DEFAULT_PLACEHOLDER_HINT } from '../../lib/templates'

/** Libellé neutre affiché à la place de tout bloc que cette version ne sait pas rendre. */
export const UNSUPPORTED_BLOCK_LABEL = 'Bloc non pris en charge dans cette version'

export type HighlightAnchor = { exact: string; attached?: boolean }

export type ReadHeading = {
  /** id DOM (ancre du sommaire) */
  id: string
  /** niveau TipTap d'origine (1..6) */
  level: number
  /** texte brut du titre, sans numéro */
  text: string
  /** numéro de section (titres de niveau ≤ 2) ; null sinon */
  number: number | null
}

export type BodyAnalysis = {
  /** Titres de niveau ≤ 3, dans l'ordre du document (sommaire). */
  headings: ReadHeading[]
  headingByNode: Map<TipTapNode, ReadHeading>
  /** Numéro de figure pour chaque figure légendée. */
  figureByNode: Map<TipTapNode, number>
  /** Premier paragraphe non vide au niveau racine (chapeau) — repère de test visuel. */
  leadParagraph?: TipTapNode
}

/** Types de nœuds traités comme « figures » (légende numérotée). */
const FIGURE_TYPES = new Set([
  'figure',
  'image',
  'drawio',
  'diagram',
  'screenshot',
  'excalidraw',
  'mermaid',
])

const TRANSPARENT_TYPES = new Set(['doc', 'tableWrapper'])

const NUMBERED_PREFIX = /^\s*\d+(\.\d+)*[.)]\s+/

/** Ids réservés par la page (sections non issues du corps TipTap). */
export const RESERVED_HEADING_IDS = ['documents-lies']

export function nodeText(node: TipTapNode | undefined | null): string {
  if (!node || typeof node !== 'object') return ''
  if (node.type === 'text' && typeof node.text === 'string') return node.text
  if (node.type === 'hardBreak') return ' '
  const kids = Array.isArray(node.content) ? node.content : []
  return kids.map((c) => nodeText(c as TipTapNode)).join('')
}

export function slugifyHeading(text: string): string {
  const s = text
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
  return s || 'section'
}

/** Retire un éventuel « 1. » saisi à la main (évite le double numéro). */
export function stripSectionNumber(text: string): string {
  return text.replace(NUMBERED_PREFIX, '').trim()
}

function captionOf(node: TipTapNode): string | null {
  const attrs = (node.attrs ?? {}) as Record<string, unknown>
  if (typeof attrs.caption === 'string' && attrs.caption.trim()) return attrs.caption.trim()
  if (node.type === 'figure') {
    const cap = (Array.isArray(node.content) ? node.content : []).find(
      (c) => (c as TipTapNode).type === 'figcaption',
    ) as TipTapNode | undefined
    const t = nodeText(cap).trim()
    if (t) return t
  }
  return null
}

/**
 * Pré-passe : titres (id stable + numéro de section) et numéros de figures.
 * Ne descend pas dans les transclusions (titres d'un autre document).
 */
export function analyzeBody(
  body: TipTapNode | null | undefined,
  reservedIds: string[] = RESERVED_HEADING_IDS,
): BodyAnalysis {
  const headings: ReadHeading[] = []
  const headingByNode = new Map<TipTapNode, ReadHeading>()
  const figureByNode = new Map<TipTapNode, number>()
  const used = new Set(reservedIds)
  let section = 0
  let figure = 0
  let leadParagraph: TipTapNode | undefined

  const walk = (node: TipTapNode | undefined) => {
    if (!node || typeof node !== 'object') return
    if (node.type === 'transclusion') return
    if (!leadParagraph && node.type === 'paragraph' && nodeText(node).trim()) leadParagraph = node
    if (node.type === 'heading') {
      const level = Number((node.attrs as { level?: number } | undefined)?.level ?? 2)
      const raw = nodeText(node)
      const text = stripSectionNumber(raw)
      if (level <= 3 && text) {
        const base = slugifyHeading(text)
        let id = base
        let n = 2
        while (used.has(id)) id = `${base}-${n++}`
        used.add(id)
        const number = level <= 2 ? ++section : null
        const h: ReadHeading = { id, level, text, number }
        headings.push(h)
        headingByNode.set(node, h)
      }
      return
    }
    if (FIGURE_TYPES.has(String(node.type)) && captionOf(node)) {
      figureByNode.set(node, ++figure)
    }
    for (const c of Array.isArray(node.content) ? node.content : []) walk(c as TipTapNode)
  }
  walk(body ?? undefined)
  return { headings, headingByNode, figureByNode, leadParagraph }
}

/* ------------------------------------------------------------------ */
/* Inline                                                              */
/* ------------------------------------------------------------------ */

function esc(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function safeHref(href: unknown): string | null {
  if (typeof href !== 'string') return null
  const h = href.trim()
  if (isSafeHttpUrl(h) || h.startsWith('mailto:')) return h
  if (h.startsWith('/') && !h.startsWith('//')) return h
  if (h.startsWith('#')) return h
  return null
}

function wrapMarks(html: string, marks: unknown): string {
  if (!Array.isArray(marks)) return html
  let out = html
  for (const m of marks as Array<{ type?: string; attrs?: Record<string, unknown> }>) {
    switch (m?.type) {
      case 'bold':
        out = `<strong>${out}</strong>`
        break
      case 'italic':
        out = `<em>${out}</em>`
        break
      case 'strike':
        out = `<s>${out}</s>`
        break
      case 'underline':
        out = `<u>${out}</u>`
        break
      case 'code':
        out = `<code>${out}</code>`
        break
      case 'link': {
        const href = safeHref(m.attrs?.href)
        if (href) out = `<a href="${esc(href)}" rel="noopener noreferrer">${out}</a>`
        break
      }
      default:
        break
    }
  }
  return out
}

/** HTML inline assaini : texte échappé + marques whitelistées + surlignage des ancres. */
export function inlineHtml(node: TipTapNode, anchors: HighlightAnchor[]): string {
  const kids = (Array.isArray(node.content) ? node.content : []) as TipTapNode[]
  const plain = nodeText(node)
  const attached = anchors.filter((a) => a.attached !== false && a.exact.trim())

  let html: string
  if (attached.length === 0) {
    html = kids.map((k) => inlineNodeHtml(k, [])).join('')
  } else {
    const perNode = kids.map((k) => inlineNodeHtml(k, attached)).join('')
    // Ancre à cheval sur plusieurs nœuds (marques) → repli sur le texte brut du bloc.
    const needsFallback = attached.some(
      (a) => plain.includes(a.exact) && !perNode.includes('class="comment-mark'),
    )
    html = needsFallback ? highlightAnchorsHtml(plain, attached) : perNode
  }
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ['strong', 'em', 's', 'u', 'code', 'a', 'mark', 'br', 'span'],
    ALLOWED_ATTR: ['href', 'rel', 'class'],
    ALLOW_DATA_ATTR: false,
  })
}

function inlineNodeHtml(node: TipTapNode, anchors: HighlightAnchor[]): string {
  if (node.type === 'text') {
    const text = typeof node.text === 'string' ? node.text : ''
    const inner = anchors.length ? highlightAnchorsHtml(text, anchors) : esc(text)
    return wrapMarks(inner, node.marks)
  }
  if (node.type === 'hardBreak') return '<br>'
  if (node.type === 'date') {
    const value = (node.attrs as { value?: unknown } | undefined)?.value
    return `<span class="doc-date">${esc(formatDateFr(value))}</span>`
  }
  // Nœud inline inconnu : texte brut seulement (jamais de JSON).
  const t = nodeText(node)
  return t ? esc(t) : ''
}

function Inline({ node, anchors }: { node: TipTapNode; anchors: HighlightAnchor[] }) {
  return <span dangerouslySetInnerHTML={{ __html: inlineHtml(node, anchors) }} />
}

/* ------------------------------------------------------------------ */
/* Blocs                                                               */
/* ------------------------------------------------------------------ */

type Ctx = {
  anchors: HighlightAnchor[]
  analysis: BodyAnalysis
}

function children(node: TipTapNode): TipTapNode[] {
  return (Array.isArray(node.content) ? node.content : []) as TipTapNode[]
}

export function UnsupportedBlock({ type, caption }: { type?: string; caption?: string | null }) {
  return (
    <figure className="doc-unsupported" data-testid="unsupported-block" data-block-type={type ?? ''}>
      <div className="doc-unsupported-box" role="note">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="M14 3v4a1 1 0 0 0 1 1h4" />
          <path d="M17 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2z" />
        </svg>
        <span>{UNSUPPORTED_BLOCK_LABEL}</span>
      </div>
      {caption ? <figcaption className="doc-figcaption">{caption}</figcaption> : null}
    </figure>
  )
}

function safeImageSrc(src: unknown): string | null {
  if (typeof src !== 'string') return null
  const s = src.trim()
  if (isSafeHttpUrl(s)) return s
  if (s.startsWith('/') && !s.startsWith('//')) return s
  if (/^data:image\/(png|jpe?g|gif|webp);base64,[a-z0-9+/=]+$/i.test(s)) return s
  return null
}

/** « Fig. 2 — légende » (sauf si la légende porte déjà son numéro). */
function captionLabel(ctx: Ctx, node: TipTapNode): string | null {
  const cap = captionOf(node)
  if (!cap) return null
  const n = ctx.analysis.figureByNode.get(node)
  return /^fig(ure|\.)\s*\d+/i.test(cap) || n == null ? cap : `Fig. ${n} — ${cap}`
}

function figureCaption(ctx: Ctx, node: TipTapNode): ReactNode {
  const label = captionLabel(ctx, node)
  return label ? <figcaption className="doc-figcaption">{label}</figcaption> : null
}

function renderChildren(node: TipTapNode, ctx: Ctx): ReactNode {
  return children(node).map((c, i) => <Fragment key={i}>{renderBlock(c, ctx)}</Fragment>)
}

function renderListItem(item: TipTapNode, ctx: Ctx, key: number) {
  return (
    <li key={key}>
      {children(item).map((c, i) =>
        c.type === 'paragraph' ? (
          <Inline key={i} node={c} anchors={ctx.anchors} />
        ) : (
          <Fragment key={i}>{renderBlock(c, ctx)}</Fragment>
        ),
      )}
    </li>
  )
}

function renderTable(node: TipTapNode, ctx: Ctx) {
  const rows = children(node).filter((r) => r.type === 'tableRow')
  const cellContent = (cell: TipTapNode) =>
    children(cell).map((c, i) =>
      c.type === 'paragraph' ? (
        <Fragment key={i}>
          {i > 0 ? <br /> : null}
          <Inline node={c} anchors={ctx.anchors} />
        </Fragment>
      ) : (
        <Fragment key={i}>{renderBlock(c, ctx)}</Fragment>
      ),
    )
  return (
    <div className="doc-table-wrap" data-testid="doc-table">
      <table className="doc-table">
        <tbody>
          {rows.map((row, ri) => (
            <tr key={ri}>
              {children(row).map((cell, ci) =>
                cell.type === 'tableHeader' ? (
                  <th key={ci} scope="col">
                    {cellContent(cell)}
                  </th>
                ) : (
                  <td key={ci}>{cellContent(cell)}</td>
                ),
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function TransclusionBlock({ node, ctx }: { node: TipTapNode; ctx: Ctx }) {
  const attrs = (node.attrs ?? {}) as Record<string, unknown>
  if (attrs.accessible !== true) {
    return (
      <aside className="doc-transclusion-denied" data-testid="transclusion-denied">
        Contenu non accessible
      </aside>
    )
  }
  const title = typeof attrs.title === 'string' ? attrs.title : 'Document'
  const documentId = typeof attrs.documentId === 'string' ? attrs.documentId : null
  const nested = children(node)[0]
  const nestedAnalysis = analyzeBody(nested ?? null, [])
  return (
    <section className="doc-transclusion" data-testid="transclusion-ok">
      <div className="doc-transclusion-head">
        <span className="doc-transclusion-title">{title}</span>
        {documentId ? (
          <Link to={`/docs/${documentId}`} className="doc-transclusion-link">
            Ouvrir la source →
          </Link>
        ) : null}
      </div>
      {nested ? (
        <div>
          {renderBlock(nested, {
            anchors: ctx.anchors,
            // Titres de la source : pas de numéro ni d'ancre dans le sommaire de la page courante.
            analysis: {
              headings: [],
              headingByNode: new Map(),
              figureByNode: nestedAnalysis.figureByNode,
            },
          })}
        </div>
      ) : null}
    </section>
  )
}

function renderBlock(node: TipTapNode, ctx: Ctx): ReactNode {
  if (!node || typeof node !== 'object') return null
  const type = String(node.type ?? '')

  if (type === 'transclusion') return <TransclusionBlock node={node} ctx={ctx} />

  if (TRANSPARENT_TYPES.has(type)) return renderChildren(node, ctx)

  switch (type) {
    case 'paragraph': {
      if (children(node).length === 0) return null
      return (
        <p data-mock-id={ctx.analysis.leadParagraph === node ? 'doc-lead' : undefined}>
          <Inline node={node} anchors={ctx.anchors} />
        </p>
      )
    }
    case 'heading': {
      const level = Number((node.attrs as { level?: number } | undefined)?.level ?? 2)
      const h = ctx.analysis.headingByNode.get(node)
      const tagLevel = Math.min(6, Math.max(2, level <= 2 ? 2 : level))
      const Tag = `h${tagLevel}` as 'h2' | 'h3' | 'h4' | 'h5' | 'h6'
      const content = h ? { ...node, content: stripNumberFromContent(node) } : node
      return (
        <Tag
          id={h?.id}
          className={`doc-h doc-h${tagLevel}`}
          data-mock-id={h && ctx.analysis.headings[0] === h ? 'doc-section-first' : undefined}
        >
          {h?.number != null ? <span className="doc-h-num">{h.number}. </span> : null}
          <Inline node={content} anchors={ctx.anchors} />
        </Tag>
      )
    }
    case 'bulletList':
    case 'orderedList': {
      const List = type === 'bulletList' ? 'ul' : 'ol'
      return (
        <List className="doc-list">
          {children(node).map((li, i) => renderListItem(li, ctx, i))}
        </List>
      )
    }
    case 'blockquote':
      // Encart de la maquette : filet accent + fond #FAFAFE.
      return (
        <blockquote className="doc-callout">
          {children(node).map((c, i) =>
            c.type === 'paragraph' ? (
              <p key={i}>
                <Inline node={c} anchors={ctx.anchors} />
              </p>
            ) : (
              <Fragment key={i}>{renderBlock(c, ctx)}</Fragment>
            ),
          )}
        </blockquote>
      )
    case 'codeBlock':
      return (
        <pre className="doc-code">
          <code>{nodeText(node)}</code>
        </pre>
      )
    case 'horizontalRule':
      return <hr className="doc-hr" />
    case 'table':
      return renderTable(node, ctx)
    case PLACEHOLDER_NODE_TYPE: {
      const hint = (node.attrs as { hint?: unknown } | undefined)?.hint
      return (
        <div className="socle-placeholder" data-testid="template-placeholder">
          {typeof hint === 'string' && hint.trim() ? hint : DEFAULT_PLACEHOLDER_HINT}
        </div>
      )
    }
    case 'attachment': {
      const attrs = (node.attrs ?? {}) as Record<string, unknown>
      if (typeof attrs.id !== 'string' || !attrs.id) return <UnsupportedBlock type={type} />
      return (
        <AttachmentFile
          id={attrs.id}
          filename={typeof attrs.filename === 'string' ? attrs.filename : undefined}
          sizeBytes={typeof attrs.sizeBytes === 'number' ? attrs.sizeBytes : null}
          mediaType={typeof attrs.mediaType === 'string' ? attrs.mediaType : undefined}
        />
      )
    }
    case 'button': {
      const attrs = (node.attrs ?? {}) as Record<string, unknown>
      return <RichButton label={attrs.label} href={attrs.href} documentId={attrs.documentId} />
    }
    case 'video': {
      const attrs = (node.attrs ?? {}) as Record<string, unknown>
      if (typeof attrs.id !== 'string' || !attrs.id) return <UnsupportedBlock type={type} />
      return (
        <div className="doc-video-wrap">
          <RichVideo id={attrs.id} filename={typeof attrs.filename === 'string' ? attrs.filename : undefined} />
        </div>
      )
    }
    case 'image': {
      const attrs = (node.attrs ?? {}) as Record<string, unknown>
      if (typeof attrs.id === 'string' && attrs.id) {
        return (
          <figure className="doc-figure">
            <AttachmentImage
              id={attrs.id}
              alt={typeof attrs.alt === 'string' ? attrs.alt : ''}
              filename={typeof attrs.filename === 'string' ? attrs.filename : undefined}
              width={typeof attrs.width === 'number' ? attrs.width : null}
              height={typeof attrs.height === 'number' ? attrs.height : null}
            />
            {figureCaption(ctx, node)}
          </figure>
        )
      }
      const src = safeImageSrc(attrs.src)
      if (!src) return <UnsupportedBlock type={type} caption={captionLabel(ctx, node)} />
      return (
        <figure className="doc-figure">
          <img src={src} alt={typeof attrs.alt === 'string' ? attrs.alt : ''} />
          {figureCaption(ctx, node)}
        </figure>
      )
    }
    case 'figure': {
      const inner = children(node).filter((c) => c.type !== 'figcaption')
      return (
        <figure className="doc-figure">
          {inner.map((c, i) => (
            <Fragment key={i}>{renderBlock(c, ctx)}</Fragment>
          ))}
          {figureCaption(ctx, node)}
        </figure>
      )
    }
    default:
      // Bloc inconnu / non géré (draw.io, capture, extension future…) : jamais de JSON brut.
      return <UnsupportedBlock type={type} caption={captionLabel(ctx, node)} />
  }
}

/** Contenu du titre sans numéro saisi à la main (le numéro est rendu par la vue). */
function stripNumberFromContent(node: TipTapNode): TipTapNode[] {
  const kids = children(node)
  const first = kids[0]
  if (first?.type === 'text' && typeof first.text === 'string' && NUMBERED_PREFIX.test(first.text)) {
    return [{ ...first, text: first.text.replace(NUMBERED_PREFIX, '') }, ...kids.slice(1)]
  }
  return kids
}

/* ------------------------------------------------------------------ */
/* Composant                                                           */
/* ------------------------------------------------------------------ */

export type TipTapReadViewProps = {
  body: TipTapNode | null | undefined
  /** Ancres de commentaires à surligner (déjà filtrées côté appelant). */
  anchors?: HighlightAnchor[]
  /** Analyse déjà calculée par la page (sommaire) — sinon calculée ici. */
  analysis?: BodyAnalysis
  className?: string
}

/**
 * Rendu lecture seule d'un corps TipTap (JSON) : titres ancrés + numérotés, tableaux,
 * légendes de figures, transclusions résolues, surlignage des commentaires.
 * Tout bloc inconnu → {@link UNSUPPORTED_BLOCK_LABEL}.
 */
export function TipTapReadView({ body, anchors = [], analysis, className }: TipTapReadViewProps) {
  const computed = useMemo(() => analysis ?? analyzeBody(body), [analysis, body])
  const root = body && typeof body === 'object' ? body : ({ type: 'doc', content: [] } as TipTapNode)
  return (
    <div className={`doc-read${className ? ` ${className}` : ''}`} data-testid="doc-read-body">
      {renderBlock(root, { anchors, analysis: computed })}
    </div>
  )
}
