// SPDX-License-Identifier: AGPL-3.0-or-later
import {
  FormEvent,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { listAccess } from '../lib/access'
import { apiErrorMessage } from '../lib/approvals'
import {
  authorInitials,
  commentsQueryKey,
  createComment,
  formatMention,
  formatRelativeTimeFr,
  listComments,
  mentionWarningsDisplay,
  reopenComment,
  renderCommentMarkdown,
  resolveComment,
  selectionToAnchor,
  type CommentAnchorInput,
  type CommentView,
  type MentionCandidate,
} from '../lib/comments'

export type CommentsPanelProps = {
  documentId: string
  versionNo?: number
  canComment: boolean
  readOnly?: boolean
  /** Pour l’autocomplete @ via listAccess(space) */
  spaceId?: string | null
  draftAnchor?: CommentAnchorInput | null
  onDraftAnchorClear?: () => void
  onClose?: () => void
  className?: string
}

type StatusFilter = 'ouvert' | 'resolu' | 'all'

const ACCENT = ['#D9A441', '#B54708', '#3730E0'] as const

export function CommentsPanel({
  documentId,
  versionNo,
  canComment,
  readOnly = false,
  spaceId,
  draftAnchor = null,
  onDraftAnchorClear,
  onClose,
  className = '',
}: CommentsPanelProps) {
  const qc = useQueryClient()
  const [filter, setFilter] = useState<StatusFilter>('ouvert')
  const [composer, setComposer] = useState('')
  const [replyTo, setReplyTo] = useState<string | null>(null)
  const [warnings, setWarnings] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)
  const [mentionOpen, setMentionOpen] = useState(false)
  const [mentionQuery, setMentionQuery] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const statusParam = filter === 'all' ? undefined : filter

  const comments = useQuery({
    queryKey: commentsQueryKey(documentId, { status: statusParam, version: versionNo }),
    queryFn: () => listComments(api, documentId, { status: statusParam, version: versionNo }),
    enabled: Boolean(documentId),
  })

  const members = useQuery({
    queryKey: ['space-access-mentions', spaceId],
    queryFn: async (): Promise<MentionCandidate[]> => {
      if (!spaceId) return []
      try {
        const access = await listAccess(api, 'space', spaceId)
        const seen = new Set<string>()
        const out: MentionCandidate[] = []
        for (const e of access.entries) {
          if (e.subjectType !== 'user' || !e.subjectId || seen.has(e.subjectId)) continue
          seen.add(e.subjectId)
          const label = e.subject?.includes('|')
            ? e.subject.split('|').pop()?.trim()
            : e.subject
          out.push({
            userId: e.subjectId,
            displayName: (label && label !== e.subjectId ? label : e.subject) || e.subjectId,
          })
        }
        return out
      } catch {
        return []
      }
    },
    enabled: Boolean(spaceId) && canComment && !readOnly,
    staleTime: 60_000,
  })

  useEffect(() => {
    if (draftAnchor) {
      setReplyTo(null)
      textareaRef.current?.focus()
    }
  }, [draftAnchor])

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ['document-comments', documentId] })
  }

  const create = useMutation({
    mutationFn: (body: {
      text: string
      parentId?: string | null
      anchor?: CommentAnchorInput | null
    }) =>
      createComment(api, documentId, {
        body: body.text,
        parentId: body.parentId,
        anchor: body.parentId ? null : body.anchor,
      }),
    onSuccess: (view) => {
      setComposer('')
      setReplyTo(null)
      setError(null)
      setWarnings(mentionWarningsDisplay(view.mentionWarnings))
      onDraftAnchorClear?.()
      invalidate()
    },
    onError: (e) => setError(apiErrorMessage(e, 'Impossible de publier le commentaire')),
  })

  const resolveMut = useMutation({
    mutationFn: (id: string) => resolveComment(api, id),
    onSuccess: () => invalidate(),
    onError: (e) => setError(apiErrorMessage(e, 'Résolution impossible')),
  })

  const reopenMut = useMutation({
    mutationFn: (id: string) => reopenComment(api, id),
    onSuccess: () => invalidate(),
    onError: (e) => setError(apiErrorMessage(e, 'Réouverture impossible')),
  })

  const mentionSuggestions = useMemo(() => {
    if (!mentionOpen) return []
    const q = mentionQuery.toLowerCase()
    return (members.data ?? [])
      .filter((m) => !q || m.displayName.toLowerCase().includes(q) || m.userId.includes(q))
      .slice(0, 8)
  }, [mentionOpen, mentionQuery, members.data])

  function onComposerChange(value: string) {
    setComposer(value)
    const ta = textareaRef.current
    const caret = ta?.selectionStart ?? value.length
    const before = value.slice(0, caret)
    const at = before.match(/@([^@\s[\]]{0,40})$/)
    if (at && members.data && members.data.length > 0) {
      setMentionOpen(true)
      setMentionQuery(at[1] ?? '')
    } else {
      setMentionOpen(false)
      setMentionQuery('')
    }
  }

  function insertMention(candidate: MentionCandidate) {
    const ta = textareaRef.current
    const caret = ta?.selectionStart ?? composer.length
    const before = composer.slice(0, caret)
    const after = composer.slice(caret)
    const replaced = before.replace(/@([^@\s[\]]*)$/, formatMention(candidate.displayName, candidate.userId) + ' ')
    setComposer(replaced + after)
    setMentionOpen(false)
    setMentionQuery('')
    requestAnimationFrame(() => {
      ta?.focus()
    })
  }

  function submitComposer(e?: FormEvent) {
    e?.preventDefault()
    const text = composer.trim()
    if (!text || create.isPending) return
    create.mutate({
      text,
      parentId: replyTo,
      anchor: replyTo ? null : draftAnchor,
    })
  }

  function onComposerKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault()
      submitComposer()
    }
    if (e.key === 'Escape' && mentionOpen) {
      setMentionOpen(false)
    }
  }

  const openCount = comments.data?.openThreadCount ?? 0
  const threads = comments.data?.threads ?? []
  const detached = comments.data?.detached ?? []
  const allowWrite = canComment && !readOnly

  return (
    <aside
      className={`flex w-[340px] shrink-0 flex-col gap-3.5 overflow-hidden border-l border-socle-line bg-white px-5 py-5 ${className}`}
      data-testid="comments-panel"
      aria-label="Commentaires"
    >
      <div className="flex items-center justify-between gap-2">
        <div className="section-label">
          {openCount} commentaire{openCount === 1 ? '' : 's'} actif
          {openCount === 1 ? '' : 's'}
        </div>
        {onClose ? (
          <button
            type="button"
            onClick={onClose}
            className="text-xs font-medium text-socle-muted hover:text-socle-ink"
            aria-label="Fermer les commentaires"
          >
            Fermer
          </button>
        ) : null}
      </div>

      <div className="flex gap-1 rounded-lg border border-socle-line p-0.5 text-xs" role="tablist">
        {(
          [
            ['ouvert', 'Ouverts'],
            ['resolu', 'Résolus'],
            ['all', 'Tous'],
          ] as const
        ).map(([value, label]) => (
          <button
            key={value}
            type="button"
            role="tab"
            aria-selected={filter === value}
            onClick={() => setFilter(value)}
            className={`flex-1 rounded-md px-2 py-1.5 font-medium transition ${
              filter === value
                ? 'bg-socle-mist text-socle-accent'
                : 'text-socle-muted hover:bg-socle-soft'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {comments.isLoading && <p className="text-sm text-socle-muted">Chargement…</p>}
      {comments.isError && (
        <p className="text-sm text-socle-danger">Impossible de charger les commentaires.</p>
      )}

      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto pr-0.5">
        {threads.map((thread, i) => (
          <ThreadCard
            key={thread.id}
            thread={thread}
            accent={ACCENT[i % ACCENT.length]!}
            allowWrite={allowWrite}
            onResolve={() => resolveMut.mutate(thread.id)}
            onReopen={() => reopenMut.mutate(thread.id)}
            onReply={() => {
              setReplyTo(thread.id)
              textareaRef.current?.focus()
            }}
            resolving={resolveMut.isPending || reopenMut.isPending}
          />
        ))}

        {!comments.isLoading && threads.length === 0 && detached.length === 0 && (
          <p className="text-sm text-socle-muted">Aucun commentaire pour ce filtre.</p>
        )}

        {detached.length > 0 && (
          <section className="pt-2" data-testid="comments-detached">
            <div className="section-label mb-2">Détachés</div>
            <p className="mb-2 text-xs text-socle-muted">
              Ces commentaires ne trouvent plus leur passage dans le texte actuel.
            </p>
            <div className="space-y-3">
              {detached.map((thread) => (
                <ThreadCard
                  key={thread.id}
                  thread={thread}
                  accent="#9B9BA1"
                  allowWrite={allowWrite}
                  detached
                  onResolve={() => resolveMut.mutate(thread.id)}
                  onReopen={() => reopenMut.mutate(thread.id)}
                  onReply={() => {
                    setReplyTo(thread.id)
                    textareaRef.current?.focus()
                  }}
                  resolving={resolveMut.isPending || reopenMut.isPending}
                />
              ))}
            </div>
          </section>
        )}
      </div>

      {warnings.length > 0 && (
        <ul className="space-y-1 rounded-lg border border-[#F3C9BB] bg-[#FBE2DB]/60 px-3 py-2 text-xs text-socle-danger" data-testid="mention-warnings">
          {warnings.map((w) => (
            <li key={w}>{w}</li>
          ))}
        </ul>
      )}
      {error && <p className="text-xs text-socle-danger">{error}</p>}

      {allowWrite ? (
        <form className="relative mt-auto space-y-2" onSubmit={submitComposer}>
          {draftAnchor && !replyTo ? (
            <div
              className="rounded-md border border-[#E8D9A8] bg-[#FBF3E4] px-2.5 py-1.5 text-xs text-socle-warn"
              data-testid="draft-anchor"
            >
              <span className="font-semibold">Sélection · </span>
              <span className="line-clamp-2 italic">« {draftAnchor.exact} »</span>
              <button
                type="button"
                className="ml-2 font-semibold underline"
                onClick={() => onDraftAnchorClear?.()}
              >
                Retirer
              </button>
            </div>
          ) : null}
          {replyTo ? (
            <div className="flex items-center justify-between text-xs text-socle-muted">
              <span>Réponse au fil</span>
              <button type="button" className="font-semibold text-socle-accent" onClick={() => setReplyTo(null)}>
                Annuler
              </button>
            </div>
          ) : null}
          <div className="relative flex items-end gap-2 rounded-[9px] border border-socle-line px-2.5 py-2">
            <textarea
              ref={textareaRef}
              value={composer}
              onChange={(e) => onComposerChange(e.target.value)}
              onKeyDown={onComposerKeyDown}
              rows={2}
              placeholder="Ajouter un commentaire…"
              className="max-h-28 min-h-[40px] flex-1 resize-none border-0 bg-transparent text-[13px] text-socle-ink outline-none placeholder:text-socle-faint"
              data-testid="comment-composer"
            />
            <button
              type="submit"
              disabled={!composer.trim() || create.isPending}
              className="shrink-0 text-xs font-semibold text-socle-accent disabled:opacity-40"
            >
              {create.isPending ? '…' : 'Envoyer'}
            </button>
            {mentionOpen && mentionSuggestions.length > 0 && (
              <ul
                className="absolute bottom-full left-0 z-10 mb-1 max-h-40 w-full overflow-y-auto rounded-lg border border-socle-line bg-white py-1 shadow-sm"
                data-testid="mention-dropdown"
              >
                {mentionSuggestions.map((m) => (
                  <li key={m.userId}>
                    <button
                      type="button"
                      className="w-full px-3 py-1.5 text-left text-xs hover:bg-socle-soft"
                      onClick={() => insertMention(m)}
                    >
                      {m.displayName}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <p className="text-[10px] text-socle-faint">Markdown limité · @mention · Ctrl+Entrée</p>
        </form>
      ) : (
        <p className="mt-auto text-xs text-socle-muted">Lecture seule — commentaires non modifiables.</p>
      )}
    </aside>
  )
}

function ThreadCard({
  thread,
  accent,
  allowWrite,
  detached,
  onResolve,
  onReopen,
  onReply,
  resolving,
}: {
  thread: CommentView
  accent: string
  allowWrite: boolean
  detached?: boolean
  onResolve: () => void
  onReopen: () => void
  onReply: () => void
  resolving: boolean
}) {
  const open = thread.status === 'ouvert'
  return (
    <article
      className="rounded-[10px] border border-socle-line p-3.5"
      style={{ borderLeftWidth: 3, borderLeftColor: accent }}
      data-testid={`comment-thread-${thread.id}`}
      data-status={thread.status}
      data-detached={detached ? 'true' : undefined}
    >
      <CommentBody comment={thread} />
      {thread.anchor?.exact ? (
        <blockquote className="mb-2 line-clamp-2 border-l-2 border-socle-line pl-2 text-[11px] italic text-socle-muted">
          « {thread.anchor.exact} »
          {detached || thread.anchor.attached === false ? (
            <span className="ml-1 not-italic text-socle-warn">(détaché)</span>
          ) : null}
        </blockquote>
      ) : null}
      {allowWrite && !thread.deleted && (
        <div className="mt-2 flex flex-wrap gap-2">
          {open ? (
            <button
              type="button"
              onClick={onResolve}
              disabled={resolving}
              className="rounded-md border border-socle-line px-2.5 py-1 text-xs font-semibold text-socle-success hover:bg-[#E7EFE3] disabled:opacity-50"
              data-testid={`resolve-${thread.id}`}
            >
              Résoudre
            </button>
          ) : (
            <button
              type="button"
              onClick={onReopen}
              disabled={resolving}
              className="rounded-md border border-socle-line px-2.5 py-1 text-xs font-semibold text-socle-accent hover:bg-socle-mist disabled:opacity-50"
              data-testid={`reopen-${thread.id}`}
            >
              Rouvrir
            </button>
          )}
          {open && (
            <button
              type="button"
              onClick={onReply}
              className="bg-transparent text-xs text-[#6B6B72] hover:text-socle-ink"
            >
              Répondre
            </button>
          )}
        </div>
      )}
      {thread.replies?.length > 0 && (
        <div className="mt-3 space-y-2 border-t border-socle-line pt-3">
          {thread.replies.map((r) => (
            <CommentBody key={r.id} comment={r} compact />
          ))}
        </div>
      )}
    </article>
  )
}

function CommentBody({ comment, compact }: { comment: CommentView; compact?: boolean }) {
  const name = comment.authorAnonymized
    ? 'Utilisateur supprimé'
    : comment.authorDisplayName || 'Anonyme'
  return (
    <div className={compact ? 'pl-1' : ''}>
      <div className="mb-2 flex items-center gap-2">
        <div
          className={`flex shrink-0 items-center justify-center rounded-full text-[9.5px] font-semibold ${
            compact ? 'h-5 w-5 bg-socle-soft text-socle-muted' : 'h-[22px] w-[22px] bg-socle-mist text-socle-accent'
          }`}
          aria-hidden
        >
          {authorInitials(name)}
        </div>
        <span className="text-[12.5px] font-semibold text-socle-ink">{name}</span>
        <span className="text-[11px] text-socle-faint">{formatRelativeTimeFr(comment.createdAt)}</span>
        {comment.status === 'resolu' && !compact && (
          <span className="ml-auto text-[10px] font-semibold uppercase tracking-wide text-socle-success">
            Résolu
          </span>
        )}
      </div>
      {comment.deleted ? (
        <p className="mb-2 text-[13px] italic text-socle-muted">
          {comment.deletedLabel || 'Commentaire supprimé'}
        </p>
      ) : (
        <div
          className="mb-2 text-[13px] leading-relaxed text-[#43434A] [&_a]:font-semibold [&_a]:text-socle-accent [&_code]:rounded [&_code]:bg-socle-soft [&_code]:px-1 [&_code]:text-[12px]"
          dangerouslySetInnerHTML={{ __html: renderCommentMarkdown(comment.body || ' ') }}
        />
      )}
    </div>
  )
}

/** Bouton flottant « Commenter » près de la sélection. */
export function CommentSelectionButton({
  rootRef,
  enabled,
  onComment,
}: {
  rootRef: { current: HTMLElement | null }
  enabled: boolean
  onComment: (anchor: CommentAnchorInput) => void
}) {
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null)
  const anchorRef = useRef<CommentAnchorInput | null>(null)

  useEffect(() => {
    if (!enabled) {
      setPos(null)
      return
    }

    function update() {
      const sel = window.getSelection()
      const root = rootRef.current
      if (!sel || !root || sel.isCollapsed || sel.rangeCount === 0) {
        setPos(null)
        anchorRef.current = null
        return
      }
      const range = sel.getRangeAt(0)
      if (!root.contains(range.commonAncestorContainer)) {
        setPos(null)
        anchorRef.current = null
        return
      }
      const anchor = selectionToAnchor(sel)
      if (!anchor) {
        setPos(null)
        return
      }
      anchorRef.current = anchor
      const rect = range.getBoundingClientRect()
      const rootRect = root.getBoundingClientRect()
      setPos({
        top: rect.bottom - rootRect.top + 8,
        left: Math.min(
          Math.max(0, rect.left - rootRect.left + rect.width / 2 - 48),
          rootRect.width - 100,
        ),
      })
    }

    document.addEventListener('selectionchange', update)
    document.addEventListener('mouseup', update)
    return () => {
      document.removeEventListener('selectionchange', update)
      document.removeEventListener('mouseup', update)
    }
  }, [enabled, rootRef])

  if (!enabled || !pos) return null

  return (
    <button
      type="button"
      style={{ top: pos.top, left: pos.left }}
      className="absolute z-20 rounded-lg border border-socle-line bg-white px-3 py-1.5 text-xs font-semibold text-socle-accent shadow-sm hover:bg-socle-mist"
      data-testid="comment-selection-btn"
      onMouseDown={(e) => {
        e.preventDefault()
        e.stopPropagation()
        if (anchorRef.current) onComment(anchorRef.current)
        setPos(null)
        window.getSelection()?.removeAllRanges()
      }}
    >
      Commenter
    </button>
  )
}
