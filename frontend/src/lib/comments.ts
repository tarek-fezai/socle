// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import DOMPurify from 'dompurify'
import type { AxiosInstance } from 'axios'

export type CommentStatus = 'ouvert' | 'resolu'

export type CommentAnchor = {
  exact: string
  prefix?: string | null
  suffix?: string | null
  blockId?: string | null
  versionNo?: number | null
  attached?: boolean
  startOffset?: number | null
  endOffset?: number | null
}

export type CommentAnchorInput = {
  exact: string
  prefix?: string | null
  suffix?: string | null
  blockId?: string | null
}

export type MentionWarning = {
  userId: string
  displayName: string
  message: string
}

export type CommentView = {
  id: string
  documentId: string
  parentId: string | null
  authorId: string
  authorDisplayName: string
  authorAnonymized: boolean
  body: string
  status: CommentStatus | string
  deleted: boolean
  deletedLabel: string | null
  anchor: CommentAnchor | null
  createdAt: string
  updatedAt: string
  resolvedBy?: string | null
  resolvedAt?: string | null
  replies: CommentView[]
  mentionWarnings: MentionWarning[]
}

export type CommentsPage = {
  documentId: string
  versionNo: number
  threads: CommentView[]
  detached: CommentView[]
  openThreadCount: number
}

export type CreateCommentBody = {
  body: string
  anchor?: CommentAnchorInput | null
  parentId?: string | null
}

export type MentionCandidate = {
  userId: string
  displayName: string
  email?: string | null
}

export const MENTION_SUGGEST_MIN_PREFIX = 2
export const MENTION_SUGGEST_LIMIT = 10
export const MENTION_NO_NOTIFY = 'Cette personne ne sera pas notifiée'

export const COMMENT_BODY_MAX = 10_000

const HTML_TAGS = /<[^>]*>/gi
const JS_SCHEME = /\bjavascript\s*:/gi
const DATA_SCHEME = /\bdata\s*:/gi
const MD_LINK = /\[([^\]]*)\]\(([^)]*)\)/g
/** `@[Name](uuid)` or `@handle` */
export const MENTION_PATTERN =
  /@\[([^\]]+)\]\(([0-9a-fA-F-]{36})\)|@([\w.-]+)/g

export function commentsQueryKey(
  documentId: string,
  opts?: { status?: string; version?: number },
) {
  return ['document-comments', documentId, opts?.status ?? '', opts?.version ?? ''] as const
}

export async function listComments(
  api: AxiosInstance,
  documentId: string,
  opts: { status?: string; version?: number } = {},
): Promise<CommentsPage> {
  const params = new URLSearchParams()
  if (opts.status) params.set('status', opts.status)
  if (opts.version != null) params.set('version', String(opts.version))
  const qs = params.toString()
  const { data } = await api.get<CommentsPage>(
    `/api/v1/documents/${documentId}/comments${qs ? `?${qs}` : ''}`,
  )
  return data
}

export async function createComment(
  api: AxiosInstance,
  documentId: string,
  body: CreateCommentBody,
): Promise<CommentView> {
  const { data } = await api.post<CommentView>(`/api/v1/documents/${documentId}/comments`, {
    body: body.body,
    anchor: body.anchor ?? undefined,
    parentId: body.parentId ?? undefined,
  })
  return data
}

export async function updateComment(
  api: AxiosInstance,
  commentId: string,
  body: string,
): Promise<CommentView> {
  const { data } = await api.patch<CommentView>(`/api/v1/comments/${commentId}`, { body })
  return data
}

export async function deleteComment(
  api: AxiosInstance,
  commentId: string,
): Promise<CommentView> {
  const { data } = await api.delete<CommentView>(`/api/v1/comments/${commentId}`)
  return data
}

export async function resolveComment(
  api: AxiosInstance,
  commentId: string,
): Promise<CommentView> {
  const { data } = await api.post<CommentView>(`/api/v1/comments/${commentId}/resolve`)
  return data
}

export async function reopenComment(
  api: AxiosInstance,
  commentId: string,
): Promise<CommentView> {
  const { data } = await api.post<CommentView>(`/api/v1/comments/${commentId}/reopen`)
  return data
}

/** Autocomplétion @ — commentateurs seulement ; lecteurs du document ; q ≥ 2. */
export async function fetchMentionSuggestions(
  api: AxiosInstance,
  documentId: string,
  q: string,
): Promise<MentionCandidate[]> {
  const query = q.trim()
  if (query.length < MENTION_SUGGEST_MIN_PREFIX) return []
  const { data } = await api.get<MentionCandidate[]>(
    `/api/v1/documents/${documentId}/comments/mention-suggestions`,
    { params: { q: query } },
  )
  return (data ?? []).slice(0, MENTION_SUGGEST_LIMIT)
}

export function isSafeHttpUrl(url: string | null | undefined): boolean {
  if (!url) return false
  const u = url.trim()
  return u.startsWith('http://') || u.startsWith('https://')
}

/** Assaini le markdown limité (miroir serveur) avant envoi / affichage. */
export function sanitizeCommentBody(raw: string): string {
  let s = raw.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  s = s.replace(HTML_TAGS, '')
  // Liens d'abord (préserve @[Name](uuid)) — puis schémas dangereux restants
  s = rewriteLinks(s)
  s = s.replace(JS_SCHEME, '')
  s = s.replace(DATA_SCHEME, '')
  s = s.trim()
  if (!s) {
    throw new Error('body vide après assainissement')
  }
  if (s.length > COMMENT_BODY_MAX) {
    throw new Error(`body trop long (max ${COMMENT_BODY_MAX})`)
  }
  return s
}

function rewriteLinks(s: string): string {
  let out = ''
  let i = 0
  while (i < s.length) {
    const start = s.indexOf('[', i)
    if (start < 0) {
      out += s.slice(i)
      break
    }
    out += s.slice(i, start)
    const mid = s.indexOf('](', start)
    if (mid < 0) {
      out += s[start]!
      i = start + 1
      continue
    }
    const label = s.slice(start + 1, mid)
    let depth = 1
    let j = mid + 2
    while (j < s.length && depth > 0) {
      const ch = s[j]!
      if (ch === '(') depth++
      else if (ch === ')') depth--
      if (depth > 0) j++
      else break
    }
    if (depth !== 0) {
      out += s[start]!
      i = start + 1
      continue
    }
    const url = s.slice(mid + 2, j)
    if (start > 0 && s[start - 1] === '@') {
      out += s.slice(start, j + 1)
    } else {
      const trimmed = url.trim()
      const safe = isSafeHttpUrl(trimmed) ? trimmed : '#'
      out += `[${label}](${safe})`
    }
    i = j + 1
  }
  return out
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * Rendu HTML limité : gras, italique, code inline, liens http(s), mentions.
 * Échappement puis balises générées ; passe finale DOMPurify (liste blanche).
 */
export function renderCommentMarkdown(raw: string): string {
  let plain: string
  try {
    plain = sanitizeCommentBody(raw)
  } catch {
    plain = raw.replace(HTML_TAGS, '').trim()
  }

  // Échapper une fois le texte ; les URLs des liens ne sont pas ré-échappées ensuite.
  let s = escapeHtml(plain)

  s = s.replace(MENTION_PATTERN, (_full, name?: string, uuid?: string, handle?: string) => {
    if (uuid && name) {
      return `<span class="comment-mention" data-user-id="${uuid}">@${name}</span>`
    }
    if (handle) {
      return `<span class="comment-mention">@${handle}</span>`
    }
    return _full
  })

  s = s.replace(/`([^`]+)`/g, '<code>$1</code>')

  s = s.replace(MD_LINK, (_m, label: string, url: string) => {
    // url déjà échappé (ex. & → &amp;) — ne pas re-échapper
    const decoded = url.trim().replace(/&amp;/g, '&')
    const href = isSafeHttpUrl(decoded) ? url.trim() : '#'
    return `<a href="${href}" target="_blank" rel="noopener noreferrer">${label}</a>`
  })

  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  s = s.replace(/\*([^*]+)\*/g, '<em>$1</em>')
  s = s.replace(/\n/g, '<br/>')

  return purifyCommentHtml(s)
}

function purifyCommentHtml(html: string): string {
  const clean = DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ['strong', 'em', 'code', 'br', 'a', 'span'],
    ALLOWED_ATTR: ['href', 'target', 'rel', 'class', 'data-user-id'],
    ALLOW_DATA_ATTR: false,
  })
  if (typeof document === 'undefined') return clean
  const wrap = document.createElement('div')
  wrap.innerHTML = clean
  wrap.querySelectorAll('a').forEach((a) => {
    const href = a.getAttribute('href') ?? ''
    if (!isSafeHttpUrl(href)) {
      a.setAttribute('href', '#')
    }
    a.setAttribute('target', '_blank')
    a.setAttribute('rel', 'noopener noreferrer')
  })
  return wrap.innerHTML
}

export type ParsedMention =
  | { kind: 'ref'; name: string; userId: string; raw: string; index: number }
  | { kind: 'handle'; handle: string; raw: string; index: number }

export function parseMentions(body: string): ParsedMention[] {
  const out: ParsedMention[] = []
  const re = new RegExp(MENTION_PATTERN.source, 'g')
  let m: RegExpExecArray | null
  while ((m = re.exec(body)) !== null) {
    if (m[2]) {
      out.push({ kind: 'ref', name: m[1], userId: m[2], raw: m[0], index: m.index })
    } else if (m[3]) {
      out.push({ kind: 'handle', handle: m[3], raw: m[0], index: m.index })
    }
  }
  return out
}

export function formatMention(displayName: string, userId: string): string {
  const name = displayName.replace(/[\[\]]/g, '').trim() || userId
  return `@[${name}](${userId})`
}

/** Messages à afficher après création (warnings API). */
export function mentionWarningsDisplay(warnings: MentionWarning[] | null | undefined): string[] {
  if (!warnings?.length) return []
  return warnings.map((w) => w.message?.trim() || MENTION_NO_NOTIFY)
}

export function authorInitials(name: string | null | undefined): string {
  const n = (name ?? '').trim()
  if (!n) return '?'
  const parts = n.split(/\s+/).filter(Boolean)
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase()
  return (parts[0]![0]! + parts[parts.length - 1]![0]!).toUpperCase()
}

export function formatRelativeTimeFr(iso: string, nowMs = Date.now()): string {
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return ''
  const diffSec = Math.round((nowMs - t) / 1000)
  if (diffSec < 60) return "à l'instant"
  const min = Math.round(diffSec / 60)
  if (min < 60) return `il y a ${min} min`
  const h = Math.round(min / 60)
  if (h < 24) return `il y a ${h}h`
  const d = Math.round(h / 24)
  if (d < 30) return `il y a ${d} j`
  return new Date(iso).toLocaleDateString('fr-FR')
}

/** Contexte texte autour d'une sélection pour préfixe / suffixe d'ancre. */
export function selectionToAnchor(
  selection: Selection | null,
  opts: { contextChars?: number; blockId?: string | null } = {},
): CommentAnchorInput | null {
  if (!selection || selection.isCollapsed || selection.rangeCount === 0) return null
  const exact = selection.toString().replace(/\s+/g, ' ').trim()
  if (!exact) return null
  const range = selection.getRangeAt(0)
  const container = range.commonAncestorContainer
  const root =
    container.nodeType === Node.TEXT_NODE ? container.parentElement : (container as Element | null)
  const blockText = (root?.closest('[data-comment-root]') as HTMLElement | null)?.innerText
    ?? root?.textContent
    ?? exact
  const normalized = blockText.replace(/\s+/g, ' ')
  const idx = normalized.indexOf(exact)
  const ctx = opts.contextChars ?? 32
  let prefix: string | null = null
  let suffix: string | null = null
  if (idx >= 0) {
    prefix = normalized.slice(Math.max(0, idx - ctx), idx) || null
    suffix = normalized.slice(idx + exact.length, idx + exact.length + ctx) || null
  }
  return {
    exact,
    prefix,
    suffix,
    blockId: opts.blockId ?? null,
  }
}

/**
 * Entoure la première occurrence de chaque ancre attachée par un &lt;mark&gt;.
 * Utilisé en vue lecture (sans plugin TipTap).
 */
export function highlightAnchorsHtml(
  plainText: string,
  anchors: Array<{ exact: string; attached?: boolean }>,
): string {
  let html = escapeHtml(plainText)
  const attached = anchors.filter((a) => a.attached !== false && a.exact.trim())
  // Plus long d'abord pour éviter les chevauchements partiels
  attached.sort((a, b) => b.exact.length - a.exact.length)
  let colorIdx = 0
  for (const a of attached) {
    const needle = escapeHtml(a.exact)
    const i = html.indexOf(needle)
    if (i < 0) continue
    const cls = `comment-mark comment-mark-${(colorIdx % 3) + 1}`
    colorIdx++
    html =
      html.slice(0, i) +
      `<mark class="${cls}">${needle}</mark>` +
      html.slice(i + needle.length)
  }
  return html
}

export function canCommentOnSpace(space: {
  commentPolicy?: string | null
  membership?: string | null
}): boolean {
  if (space.commentPolicy === 'all_readers') return true
  return space.membership !== 'public-only'
}
