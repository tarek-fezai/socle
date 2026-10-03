// SPDX-License-Identifier: AGPL-3.0-or-later
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Link, useOutletContext, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Editor } from '@tiptap/react'
import { useAuth } from '../auth/AuthProvider'
import { CommentSelectionButton, CommentsPanel } from '../components/CommentsPanel'
import { PlaceholderBanner } from '../components/PlaceholderBanner'
import type { ShellOutletContext } from '../components/shell/shellUtils'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import { canCommentOnSpace, listComments, type CommentAnchorInput } from '../lib/comments'
import {
  customFieldsKey,
  listDocumentCustomFields,
  setDocumentCustomField,
  type CustomFieldView,
} from '../lib/customFields'
import {
  emptyDocBody,
  getDocument,
  updateDocument,
  type DocumentDetail,
  type TagRef,
  type TipTapNode,
} from '../lib/documents'
import { folderPath, getSpaceTree, spaceTreeKey } from '../lib/folders'
import { getSpace } from '../lib/spaces'
import { attachTag, detachTag, sortTags } from '../lib/tags'
import { placeholderConflictMessage } from '../lib/templates'
import { fetchApplicableWorkflow } from '../lib/workflows'
import {
  DEFAULT_LONG_PARAGRAPH_WORDS,
  findLongParagraphs,
  getWritingHints,
  writingHintsKey,
} from '../lib/writingAssistant'
import { DocumentEditor } from './DocumentEditor'
import { DocumentMobileTabs, DocumentMobileTop, DocumentTabs, type Crumb } from './document/DocumentChrome'
import { EditLockBanner, EditNotice, EditTopBar } from './document/DocumentEditChrome'
import { EditAssistantPanel } from './document/EditAssistantPanel'
import { TipTapReadView, analyzeBody } from './document/TipTapReadView'
import {
  countWordsFromTipTap,
  findUnsupportedContent,
  formatWordStats,
  hasUnsupportedContent,
  lockHeldByOther,
  presenceAvatars,
  reliabilityShortLabel,
  reviewButtonState,
  reviewCadenceLabel,
} from './document/documentEditUtils'
import { documentPermissions, ownerLabel } from './document/documentPageUtils'
import { useAutosave } from './document/useAutosave'
import { useEditLock } from './document/useEditLock'
import './document/document-page.css'
import './document/document-edit.css'

type ApprovalCurrent = {
  approvalRequestId: string
  temporalWorkflowId: string
  status: string
  currentStepOrder: number
}

type Draft = { title: string; body: Record<string, unknown> }

function saveErrorMessage(e: unknown): string {
  if (e instanceof Error && !('response' in e)) return e.message
  return apiErrorMessage(e, "Échec de l'enregistrement")
}

/** Navigation du texte : minutes écoulées depuis l'acquisition du verrou (≥ 1). */
function minutesSince(iso: string | null | undefined, nowMs = Date.now()): number | null {
  if (!iso) return null
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return null
  return Math.max(1, Math.round((nowMs - t) / 60_000))
}

/* ------------------------------------------------------------------ */
/* Page : chargement du document                                        */
/* ------------------------------------------------------------------ */

export function DocumentEditPage() {
  const { id = '' } = useParams()
  const doc = useQuery({
    queryKey: ['document', id],
    queryFn: () => getDocument(api, id),
    enabled: Boolean(id),
    // Le brouillon local démarre du contenu serveur le plus récent, jamais d'un cache périmé.
    gcTime: 0,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  if (doc.isLoading) {
    return (
      <div className="doc-page doc-page--state" data-testid="edit-loading">
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

  return <EditSurface key={doc.data.id} doc={doc.data} />
}

/* ------------------------------------------------------------------ */
/* Écran Modifier                                                       */
/* ------------------------------------------------------------------ */

function EditSurface({ doc }: { doc: DocumentDetail }) {
  const id = doc.id
  const qc = useQueryClient()
  const shell = useOutletContext<ShellOutletContext | null | undefined>()
  const { me } = useAuth()
  const perms = documentPermissions(doc.permissions)

  /* ---------- brouillon local ---------- */

  const initialBody = useMemo(() => (doc.body ?? emptyDocBody) as Record<string, unknown>, [doc.body])
  const initial = useMemo<Draft>(() => ({ title: doc.title, body: initialBody }), [doc.title, initialBody])
  const [title, setTitle] = useState(doc.title)
  const [body, setBody] = useState<Record<string, unknown>>(initialBody)
  const [tags, setTags] = useState<TagRef[]>(doc.tags ?? [])
  const [previewing, setPreviewing] = useState(false)
  const [commentsOpen, setCommentsOpen] = useState(false)
  const [draftAnchor, setDraftAnchor] = useState<CommentAnchorInput | null>(null)
  const [approvalMsg, setApprovalMsg] = useState<string | null>(null)
  const [approvalError, setApprovalError] = useState<string | null>(null)
  const [tagError, setTagError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [savingFields, setSavingFields] = useState<Record<string, boolean>>({})
  const editorRef = useRef<Editor | null>(null)
  const previewingRef = useRef(false)
  previewingRef.current = previewing
  const contentRef = useRef<HTMLDivElement>(null)
  const titleRef = useRef<HTMLTextAreaElement>(null)
  const versionRef = useRef<number | null>(doc.currentVersionNo ?? null)

  // Blocs / marques hors schéma : TipTap les supprimerait au premier enregistrement.
  const unsupported = useMemo(() => findUnsupportedContent(initialBody), [initialBody])
  const bodyLocked = hasUnsupportedContent(unsupported)

  /* ---------- verrou exclusif / présence ---------- */

  const lockState = useEditLock(id, perms.canEdit)
  const holder = lockHeldByOther(lockState.lock)
  const lockPending = perms.canEdit && !lockState.settled
  const canWrite = perms.canEdit && !holder && !lockPending
  const bodyEditable = canWrite && !bodyLocked

  /* ---------- enregistrement automatique ---------- */

  const draft = useMemo<Draft>(() => ({ title, body }), [title, body])
  const autosave = useAutosave<Draft>({
    value: draft,
    initial,
    enabled: canWrite,
    initialSavedAt: doc.updatedAt ? new Date(doc.updatedAt) : null,
    describeError: saveErrorMessage,
    save: async (d) => {
      if (!d.title.trim()) throw new Error('Le titre du document ne peut pas être vide.')
      const updated = await updateDocument(
        api,
        id,
        d.title.trim(),
        d.body,
        doc.docType?.trim() || null,
        versionRef.current,
      )
      versionRef.current = updated.currentVersionNo ?? versionRef.current
      qc.setQueryData(['document', id], (prev: DocumentDetail | undefined) =>
        prev ? { ...prev, currentVersionNo: updated.currentVersionNo, updatedAt: updated.updatedAt } : prev,
      )
      void qc.invalidateQueries({ queryKey: ['documents'] })
      void qc.invalidateQueries({ queryKey: ['document-resolved', id] })
      void qc.invalidateQueries({ queryKey: ['applicable-workflow', id] })
      void qc.invalidateQueries({ queryKey: writingHintsKey(id) })
      return updated
    },
  })

  /* ---------- données annexes ---------- */

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
  const openComments = useQuery({
    queryKey: ['document-comments', id, 'ouvert', ''],
    queryFn: () => listComments(api, id, { status: 'ouvert' }),
    refetchInterval: commentsOpen ? false : 30_000,
  })
  const currentApproval = useQuery({
    queryKey: ['approval', id],
    queryFn: async () => {
      const res = await api.get<ApprovalCurrent>(`/api/v1/documents/${id}/approvals/current`, {
        validateStatus: (s) => s === 200 || s === 204,
      })
      return res.status === 204 ? null : res.data
    },
    refetchInterval: (q) => (q.state.data ? 3000 : false),
  })
  const applicable = useQuery({
    queryKey: ['applicable-workflow', id, doc.spaceId, doc.docType],
    queryFn: () => fetchApplicableWorkflow(api, id),
    enabled: !currentApproval.data,
    retry: false,
  })
  const hints = useQuery({
    queryKey: writingHintsKey(id),
    queryFn: () => getWritingHints(api, id),
    retry: false,
    staleTime: 0,
  })
  const customFields = useQuery({
    queryKey: customFieldsKey(id),
    queryFn: () => listDocumentCustomFields(api, id),
    retry: false,
  })

  /* ---------- dérivés ---------- */

  const spaceName = space.data?.name ?? tree.data?.spaceName ?? ''
  const folderId = doc.folderId ?? tree.data?.documents.find((d) => d.id === id)?.folderId ?? null
  const crumbs = useMemo<Crumb[]>(() => {
    const items: Crumb[] = [{ label: spaceName || 'Espace', to: `/spaces/${spaceId}/tree` }]
    for (const f of folderPath(tree.data?.folders ?? [], folderId)) {
      items.push({ label: f.name, to: `/folders/${f.id}` })
    }
    items.push({ label: title.trim() || 'Sans titre' })
    return items
  }, [spaceName, spaceId, tree.data?.folders, folderId, title])

  const wordStats = useMemo(() => formatWordStats(countWordsFromTipTap(body)), [body])
  const threshold = hints.data?.longParagraphThresholdWords ?? DEFAULT_LONG_PARAGRAPH_WORDS
  const longParagraphs = useMemo(
    () => findLongParagraphs(body as TipTapNode, threshold),
    [body, threshold],
  )
  const analysis = useMemo(() => (previewing ? analyzeBody(body as TipTapNode) : undefined), [previewing, body])

  const pending = currentApproval.data
  const review = reviewButtonState({
    canPublish: perms.canPublish,
    hasWorkflow: applicable.isSuccess && Boolean(applicable.data),
    workflowLoading: !pending && applicable.isLoading,
    pending: Boolean(pending),
    lockedByOther: Boolean(holder),
    submitting: false,
  })
  const canComment = perms.canComment || (space.data ? canCommentOnSpace(space.data) : false)
  const canEditMeta = canWrite
  const avatars = presenceAvatars(lockState.lock, me)

  /* ---------- actions ---------- */

  const send = useMutation({
    mutationFn: async () => {
      const saved = await autosave.flush()
      if (!saved) throw new Error("L'enregistrement a échoué : corrigez-le avant d'envoyer en révision.")
      const { data } = await api.post<{ approvalRequestId: string; temporalWorkflowId: string; status: string }>(
        `/api/v1/documents/${id}/approvals`,
      )
      return data
    },
    onSuccess: () => {
      setApprovalError(null)
      setApprovalMsg('Document envoyé en révision.')
      void qc.invalidateQueries({ queryKey: ['document', id] })
      void qc.invalidateQueries({ queryKey: ['document-resolved', id] })
      void qc.invalidateQueries({ queryKey: ['documents'] })
      void qc.invalidateQueries({ queryKey: ['approval', id] })
      void qc.invalidateQueries({ queryKey: ['approvals', 'mine'] })
    },
    onError: (err) => {
      setApprovalMsg(null)
      setApprovalError(
        err instanceof Error && !('response' in err)
          ? err.message
          : (placeholderConflictMessage(err) ??
              apiErrorMessage(err, 'Échec de la soumission pour approbation')),
      )
    },
  })

  const addTag = useMutation({
    mutationFn: (tag: { tagId: string } | { name: string }) => attachTag(api, id, tag),
    onSuccess: (tag) => {
      setTagError(null)
      setTags((prev) => (prev.some((t) => t.id === tag.id) ? prev : sortTags([...prev, tag])))
      void qc.invalidateQueries({ queryKey: ['document-resolved', id] })
      void qc.invalidateQueries({ queryKey: ['tags-search'] })
    },
    onError: (e) => setTagError(apiErrorMessage(e, "Impossible d'ajouter ce tag")),
  })
  const removeTag = useMutation({
    mutationFn: (tag: TagRef) => detachTag(api, id, tag.id),
    onSuccess: (_v, tag) => {
      setTagError(null)
      setTags((prev) => prev.filter((t) => t.id !== tag.id))
      void qc.invalidateQueries({ queryKey: ['document-resolved', id] })
    },
    onError: (e) => setTagError(apiErrorMessage(e, 'Impossible de retirer ce tag')),
  })

  const saveField = useCallback(
    async (field: CustomFieldView, value: unknown) => {
      setSavingFields((s) => ({ ...s, [field.id]: true }))
      try {
        const updated = await setDocumentCustomField(api, id, field.id, value)
        setFieldErrors((e) => Object.fromEntries(Object.entries(e).filter(([k]) => k !== field.id)))
        qc.setQueryData(customFieldsKey(id), (prev: CustomFieldView[] | undefined) =>
          prev?.map((f) => (f.id === field.id ? { ...f, value: updated.value } : f)),
        )
      } catch (e) {
        setFieldErrors((errs) => ({ ...errs, [field.id]: apiErrorMessage(e, 'Impossible d’enregistrer ce champ') }))
      } finally {
        setSavingFields((s) => ({ ...s, [field.id]: false }))
      }
    },
    [id, qc],
  )

  const goToParagraph = useCallback((index: number) => {
    const focus = () => {
      const ed = editorRef.current
      if (!ed) return
      let n = 0
      let target = -1
      ed.state.doc.descendants((node, pos) => {
        if (target >= 0) return false
        if (node.type.name === 'paragraph') {
          if (n === index) {
            target = pos
            return false
          }
          n += 1
        }
        return true
      })
      if (target < 0) return
      ed.chain().focus().setTextSelection(target + 1).run()
      const dom = ed.view.nodeDOM(target)
      if (dom instanceof HTMLElement) dom.scrollIntoView({ block: 'center', behavior: 'smooth' })
    }
    if (previewingRef.current) {
      setPreviewing(false)
      // L'éditeur est de nouveau visible au prochain rendu.
      window.setTimeout(focus, 0)
    } else {
      focus()
    }
  }, [])

  /* ---------- titre : hauteur automatique (repli si field-sizing absent) ---------- */

  useLayoutEffect(() => {
    const el = titleRef.current
    if (!el) return
    if (typeof CSS !== 'undefined' && CSS.supports?.('field-sizing', 'content')) return
    el.style.height = 'auto'
    if (el.scrollHeight > 0) el.style.height = `${el.scrollHeight}px`
  }, [title, previewing])

  // Les erreurs d'envoi se dissipent dès que le brouillon change.
  useEffect(() => {
    setApprovalMsg(null)
  }, [draft])

  const toggleComments = useCallback(() => {
    setCommentsOpen((v) => !v)
    setDraftAnchor(null)
  }, [])

  const saveErr = autosave.status.kind === 'error' ? autosave.status.message : null
  const titleSlot = (
    <textarea
      ref={titleRef}
      className="edit-title"
      rows={1}
      value={title}
      placeholder="Titre du document"
      aria-label="Titre du document"
      readOnly={!canWrite}
      maxLength={300}
      data-mock-id="edit-title"
      data-testid="edit-title"
      onChange={(e) => setTitle(e.target.value.replace(/\n/g, ' '))}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault()
          editorRef.current?.chain().focus('start').run()
        }
      }}
    />
  )

  const tabProps = {
    documentId: id,
    current: 'edit' as const,
    mockPrefix: 'edit' as const,
    showEdit: perms.canEdit,
    showAccess: perms.canManageAccess || perms.canEdit,
    openComments: openComments.data?.openThreadCount ?? 0,
    commentsOpen,
    onToggleComments: toggleComments,
  }

  return (
    <div className="doc-page edit-page" data-testid="document-edit-page" data-mock-id="edit-page">
      <DocumentMobileTop
        title={title || doc.title}
        spaceName={spaceName}
        onOpenMenu={shell?.openMenu}
        onOpenSearch={shell?.openSearch}
      />
      <EditTopBar
        crumbs={crumbs}
        save={autosave.status}
        onRetrySave={() => void autosave.retry()}
        wordStats={wordStats}
        presence={avatars}
        previewing={previewing}
        onTogglePreview={() => setPreviewing((v) => !v)}
        reviewLabel={send.isPending ? 'Envoi…' : pending ? 'En révision' : 'Envoyer en révision'}
        reviewDisabled={review.disabled || send.isPending}
        reviewReason={review.reason}
        onSendReview={() => send.mutate()}
      />
      {holder && (
        <EditLockBanner name={holder.name} initials={holder.initials} minutes={minutesSince(holder.since)} />
      )}
      {!perms.canEdit && (
        <EditNotice testId="edit-readonly-banner" tone="warn">
          Vous n&apos;avez pas le droit de modifier ce document : il est affiché en lecture seule.
        </EditNotice>
      )}
      <DocumentTabs {...tabProps} />

      <div className="edit-body">
        <div className="edit-main" data-mock-id="edit-main">
          <div className="edit-column" ref={contentRef} data-comment-root>
            {saveErr && autosave.status.kind === 'error' && (
              <p className="edit-alert edit-alert--error" role="alert" data-testid="edit-save-error">
                {saveErr}
              </p>
            )}
            {approvalError && (
              <p className="edit-alert edit-alert--error" role="alert" data-testid="edit-approval-error">
                {approvalError}
              </p>
            )}
            {approvalMsg && !approvalError && (
              <p className="edit-alert" role="status" data-testid="edit-approval-msg">
                {approvalMsg}{' '}
                <Link to={`/docs/${id}`} className="doc-back">
                  Voir la page
                </Link>
              </p>
            )}
            {bodyLocked && (
              <EditNotice testId="edit-unsupported-banner" tone="warn">
                Ce document contient des éléments que l&apos;éditeur ne sait pas encore représenter (
                {[...unsupported.nodes, ...unsupported.marks].join(', ')}). Le corps est affiché en lecture seule
                pour éviter de les perdre ; le titre et les métadonnées restent modifiables.
              </EditNotice>
            )}
            <PlaceholderBanner body={body} />

            <DocumentEditor
              variant="document"
              content={body}
              onChange={setBody}
              editable={bodyEditable}
              hidden={previewing}
              titleSlot={titleSlot}
              onEditorReady={(ed) => {
                editorRef.current = ed
              }}
            />

            {previewing && (
              <div className="edit-preview" data-testid="edit-preview-body">
                <h1 className="edit-title">{title || 'Sans titre'}</h1>
                <TipTapReadView body={body as TipTapNode} analysis={analysis} />
              </div>
            )}
            <CommentSelectionButton
              rootRef={contentRef}
              enabled={canComment && commentsOpen}
              onComment={(anchor) => {
                setDraftAnchor(anchor)
                setCommentsOpen(true)
              }}
            />
          </div>
        </div>

        {commentsOpen ? (
          <div className="edit-comments-col" data-testid="edit-comments-col">
            <CommentsPanel
              documentId={id}
              versionNo={doc.currentVersionNo}
              canComment={canComment}
              spaceId={doc.spaceId}
              draftAnchor={draftAnchor}
              onDraftAnchorClear={() => setDraftAnchor(null)}
              className="doc-comments-panel"
              onClose={() => {
                setCommentsOpen(false)
                setDraftAnchor(null)
              }}
            />
          </div>
        ) : (
          <EditAssistantPanel
            spaceId={spaceId}
            threshold={threshold}
            longParagraphs={longParagraphs}
            brokenLinks={hints.data?.brokenLinks ?? []}
            onGoToParagraph={goToParagraph}
            owner={ownerLabel(spaceName)}
            reliability={reliabilityShortLabel(doc.reliabilityScore)}
            reviewCadence={reviewCadenceLabel(doc.stalenessThresholdDays)}
            tags={tags}
            canEditMeta={canEditMeta}
            tagError={tagError}
            onAddTag={(t) => addTag.mutate(t)}
            onRemoveTag={(t) => removeTag.mutate(t)}
            customFields={customFields.data ?? []}
            fieldErrors={fieldErrors}
            savingFields={savingFields}
            onSaveField={(f, v) => void saveField(f, v)}
          />
        )}
      </div>

      <DocumentMobileTabs {...tabProps} />
    </div>
  )
}
