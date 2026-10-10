// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useOutletContext, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Editor } from '@tiptap/react'
import { useAuth } from '../auth/AuthProvider'
import { CommentSelectionButton, CommentsPanel } from '../components/CommentsPanel'
import { PlaceholderBanner } from '../components/PlaceholderBanner'
import type { ShellOutletContext } from '../components/shell/shellUtils'
import { api } from '../lib/api'
import { apiErrorCode, apiErrorMessage, apiRequiredFieldRefs } from '../lib/apiError'
import {
  deleteDocumentDraft,
  documentDraftKey,
  getDocumentDraft,
  isDraftStale,
  putDocumentDraft,
  type DocumentDraft,
} from '../lib/documentDrafts'
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
import {
  attachTag,
  detachTag,
  formatTagAttachFailuresNotice,
  sortTags,
  type TagAttachFailuresNavState,
} from '../lib/tags'
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
  const location = useLocation()
  const navigate = useNavigate()
  const qc = useQueryClient()
  // Flash non bloquant après création (échecs d'attachTag) — lu une fois puis retiré de l'historique.
  const [tagAttachNotice] = useState<string | null>(() => {
    const failures = (location.state as TagAttachFailuresNavState | null)?.tagAttachFailures
    return failures?.length ? formatTagAttachFailuresNotice(failures) : null
  })
  useEffect(() => {
    if (!tagAttachNotice) return
    if (!(location.state as TagAttachFailuresNavState | null)?.tagAttachFailures?.length) return
    navigate(`${location.pathname}${location.search}${location.hash}`, { replace: true, state: {} })
  }, [tagAttachNotice, location.pathname, location.search, location.hash, location.state, navigate])
  // Incrémenté après « Abandonner le brouillon » : l'écran repart du contenu publié.
  const [epoch, setEpoch] = useState(0)
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

  // Brouillon de l'appelant : restauré à l'ouverture (seulement s'il peut modifier le document).
  const canEditDoc = documentPermissions(doc.data?.permissions).canEdit
  const draftQuery = useQuery({
    queryKey: documentDraftKey(id),
    queryFn: async () => {
      try {
        return await getDocumentDraft(api, id)
      } catch {
        // Un brouillon illisible ne doit jamais empêcher d'ouvrir le document publié.
        return null
      }
    },
    enabled: Boolean(id) && Boolean(doc.data) && canEditDoc,
    gcTime: 0,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  })

  const abandonDraft = useCallback(async () => {
    await deleteDocumentDraft(api, id)
    qc.setQueryData(documentDraftKey(id), null)
    await qc.refetchQueries({ queryKey: ['document', id] })
    setEpoch((n) => n + 1)
  }, [id, qc])

  if (doc.isLoading || (canEditDoc && draftQuery.isLoading)) {
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

  return (
    <EditSurface
      key={`${doc.data.id}:${epoch}`}
      doc={doc.data}
      serverDraft={canEditDoc ? (draftQuery.data ?? null) : null}
      onAbandonDraft={abandonDraft}
      tagAttachNotice={tagAttachNotice}
    />
  )
}

/* ------------------------------------------------------------------ */
/* Écran Modifier                                                       */
/* ------------------------------------------------------------------ */

function EditSurface({
  doc,
  serverDraft,
  onAbandonDraft,
  tagAttachNotice,
}: {
  doc: DocumentDetail
  /** Brouillon de l'appelant restauré à l'ouverture (null = repart du contenu publié). */
  serverDraft: DocumentDraft | null
  onAbandonDraft: () => Promise<void>
  /** Flash non bloquant après création (échecs d'attachTag). */
  tagAttachNotice: string | null
}) {
  const id = doc.id
  const qc = useQueryClient()
  const shell = useOutletContext<ShellOutletContext | null | undefined>()
  const { me } = useAuth()
  const perms = documentPermissions(doc.permissions)

  /* ---------- brouillon (autosave) vs version publiée ---------- */

  const publishedBody = useMemo(() => (doc.body ?? emptyDocBody) as Record<string, unknown>, [doc.body])
  const initialBody = useMemo(
    () => (serverDraft ? serverDraft.body : publishedBody),
    [serverDraft, publishedBody],
  )
  const initialTitle = serverDraft ? serverDraft.title?.trim() || doc.title : doc.title
  // Référence « déjà enregistré » de l'autosave = ce que le serveur détient (brouillon restauré ou publié).
  const initial = useMemo<Draft>(() => ({ title: initialTitle, body: initialBody }), [initialTitle, initialBody])
  // Référence « publié » : sert à détecter des changements non publiés (confirmation de sortie).
  const publishedKeyRef = useRef(JSON.stringify({ title: doc.title, body: publishedBody }))
  const hasServerDraftRef = useRef(Boolean(serverDraft))
  const discardedRef = useRef(false)
  const [draftStale, setDraftStale] = useState(isDraftStale(serverDraft, doc.currentVersionNo))
  const [draftBusy, setDraftBusy] = useState(false)
  const [draftError, setDraftError] = useState<string | null>(null)
  const [versionMsg, setVersionMsg] = useState<string | null>(null)
  const [title, setTitle] = useState(initialTitle)
  const [body, setBody] = useState<Record<string, unknown>>(initialBody)
  const [tags, setTags] = useState<TagRef[]>(doc.tags ?? [])
  const [previewing, setPreviewing] = useState(false)
  const [commentsOpen, setCommentsOpen] = useState(false)
  const [draftAnchor, setDraftAnchor] = useState<CommentAnchorInput | null>(null)
  const [approvalMsg, setApprovalMsg] = useState<string | null>(null)
  const [approvalError, setApprovalError] = useState<string | null>(null)
  const [tagError, setTagError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [missingRequiredFieldIds, setMissingRequiredFieldIds] = useState<Set<string>>(new Set())
  const [savingFields, setSavingFields] = useState<Record<string, boolean>>({})
  const editorRef = useRef<Editor | null>(null)
  const previewingRef = useRef(false)
  previewingRef.current = previewing
  const contentRef = useRef<HTMLDivElement>(null)
  const titleRef = useRef<HTMLTextAreaElement>(null)
  // Version publiée sur laquelle repose le contenu édité : celle du brouillon restauré, sinon la courante.
  // Sert de `baseVersionNo` (brouillon) et d'`expectedVersionNo` (version) — un brouillon périmé
  // provoque donc un 409 explicite plutôt qu'un écrasement silencieux de la version plus récente.
  const versionRef = useRef<number | null>(serverDraft?.baseVersionNo ?? doc.currentVersionNo ?? null)

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
  const draftRef = useRef(draft)
  draftRef.current = draft
  const draftKey = useMemo(() => JSON.stringify(draft), [draft])
  const draftKeyRef = useRef(draftKey)
  draftKeyRef.current = draftKey

  // L'autosave n'écrit QUE le brouillon (PUT …/draft) : ni version, ni audit, ni Git.
  const autosave = useAutosave<Draft>({
    value: draft,
    initial,
    enabled: canWrite,
    initialSavedAt: serverDraft
      ? new Date(serverDraft.updatedAt)
      : doc.updatedAt
        ? new Date(doc.updatedAt)
        : null,
    describeError: saveErrorMessage,
    resolveSavedAt: (r) => {
      const at = (r as { updatedAt?: string } | null)?.updatedAt
      return at ? new Date(at) : null
    },
    save: async (d) => {
      // Brouillon abandonné : l'écran se recharge, ne pas le recréer au démontage.
      if (discardedRef.current) return null
      if (!d.title.trim()) throw new Error('Le titre du document ne peut pas être vide.')
      const saved = await putDocumentDraft(api, id, {
        title: d.title.trim(),
        body: d.body,
        baseVersionNo: versionRef.current ?? 0,
      })
      hasServerDraftRef.current = true
      return saved
    },
  })

  /**
   * Version explicite : crée une version via `updateDocument` (le serveur supprime alors le brouillon).
   * Sans effet si le contenu est identique à la version publiée. Résout `true` si une version a été créée.
   */
  const createVersion = useCallback(async (changeSummary?: string): Promise<boolean> => {
    const d = draftRef.current
    if (!d.title.trim()) throw new Error('Le titre du document ne peut pas être vide.')
    if (draftKeyRef.current === publishedKeyRef.current) {
      // Retour au contenu publié : un éventuel brouillon n'a plus de raison d'être.
      if (hasServerDraftRef.current) {
        await deleteDocumentDraft(api, id)
        hasServerDraftRef.current = false
      }
      return false
    }
    const updated = await updateDocument(
      api,
      id,
      d.title.trim(),
      d.body,
      doc.docType?.trim() || null,
      versionRef.current,
      changeSummary ?? null,
    )
    versionRef.current = updated.currentVersionNo ?? versionRef.current
    publishedKeyRef.current = JSON.stringify(d)
    hasServerDraftRef.current = false
    setDraftStale(false)
    qc.setQueryData(documentDraftKey(id), null)
    qc.setQueryData(['document', id], (prev: DocumentDetail | undefined) =>
      prev ? { ...prev, currentVersionNo: updated.currentVersionNo, updatedAt: updated.updatedAt } : prev,
    )
    void qc.invalidateQueries({ queryKey: ['documents'] })
    void qc.invalidateQueries({ queryKey: ['document-resolved', id] })
    void qc.invalidateQueries({ queryKey: ['applicable-workflow', id] })
    void qc.invalidateQueries({ queryKey: writingHintsKey(id) })
    setVersionMsg(updated.currentVersionNo ? `Version ${updated.currentVersionNo} enregistrée.` : 'Version enregistrée.')
    return true
  }, [id, doc.docType, qc])

  /** Brouillon vidé sur le serveur puis version : l'ordre garantit que la version reflète le dernier brouillon. */
  const persistVersion = useCallback(async (changeSummary?: string) => {
    const saved = await autosave.flush()
    if (!saved) throw new Error("L'enregistrement du brouillon a échoué : corrigez-le avant de créer une version.")
    return createVersion(changeSummary)
  }, [autosave, createVersion])

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
    mutationFn: async (changeSummary: string) => {
      // Brouillon vidé, puis version (si le contenu diffère du publié), puis demande d'approbation.
      await persistVersion(changeSummary)
      const { data } = await api.post<{ approvalRequestId: string; temporalWorkflowId: string; status: string }>(
        `/api/v1/documents/${id}/approvals`,
      )
      return data
    },
    onSuccess: () => {
      setApprovalError(null)
      setMissingRequiredFieldIds(new Set())
      setApprovalMsg('Document envoyé en révision.')
      void qc.invalidateQueries({ queryKey: ['document', id] })
      void qc.invalidateQueries({ queryKey: ['document-resolved', id] })
      void qc.invalidateQueries({ queryKey: ['documents'] })
      void qc.invalidateQueries({ queryKey: ['approval', id] })
      void qc.invalidateQueries({ queryKey: ['approvals', 'mine'] })
    },
    onError: (err) => {
      setApprovalMsg(null)
      if (apiErrorCode(err) === 'required_field_missing') {
        setMissingRequiredFieldIds(new Set(apiRequiredFieldRefs(err).map((f) => f.id)))
      }
      setApprovalError(
        err instanceof Error && !('response' in err)
          ? err.message
          : (placeholderConflictMessage(err) ??
              apiErrorMessage(err, 'Échec de la soumission pour approbation')),
      )
    },
  })

  const saveVersion = useMutation({
    mutationFn: (changeSummary: string) => persistVersion(changeSummary),
    onSuccess: () => setDraftError(null),
    onError: (err) => {
      setVersionMsg(null)
      setDraftError(saveErrorMessage(err))
    },
  })
  const saveVersionRef = useRef(saveVersion)
  saveVersionRef.current = saveVersion

  const abandonDraft = useCallback(async () => {
    setDraftBusy(true)
    setDraftError(null)
    discardedRef.current = true
    try {
      await onAbandonDraft()
    } catch (e) {
      discardedRef.current = false
      setDraftBusy(false)
      setDraftError(apiErrorMessage(e, "Impossible d'abandonner le brouillon"))
    }
  }, [onAbandonDraft])

  // Ctrl/Cmd+S : crée une version explicite (jamais l'autosave).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey) || e.altKey || e.key.toLowerCase() !== 's') return
      e.preventDefault()
      if (!canWrite || saveVersionRef.current.isPending) return
      // Raccourci clavier : résumé vide → génération auto côté serveur.
      saveVersionRef.current.mutate('')
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [canWrite])

  // Sortie de page : confirmation si des modifications ne sont pas enregistrées au brouillon, ou
  // si le contenu diffère de la version publiée (le brouillon est conservé mais non publié).
  const unpublished = draftKey !== publishedKeyRef.current
  const needsLeaveConfirm = canWrite && (autosave.dirty || unpublished)
  const leaveRef = useRef({ needs: false, dirty: false })
  leaveRef.current = { needs: needsLeaveConfirm, dirty: autosave.dirty }
  useEffect(() => {
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      if (!leaveRef.current.needs) return
      e.preventDefault()
      e.returnValue = ''
    }
    // Navigation interne (BrowserRouter : pas de useBlocker) : liens du même site.
    const onClick = (e: MouseEvent) => {
      if (!leaveRef.current.needs || e.defaultPrevented || e.button !== 0) return
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
      const a = (e.target as Element | null)?.closest?.('a[href]') as HTMLAnchorElement | null
      if (!a || (a.target && a.target !== '_self') || a.hasAttribute('download')) return
      const href = a.getAttribute('href') ?? ''
      if (!href.startsWith('/') || href.startsWith('//') || href.includes('#')) return
      const message = leaveRef.current.dirty
        ? 'Des modifications ne sont pas encore enregistrées. Quitter cette page ?'
        : 'Votre brouillon est enregistré mais pas encore publié en version (Ctrl/Cmd+S). Quitter cette page ?'
      if (!window.confirm(message)) {
        e.preventDefault()
        e.stopPropagation()
      }
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    document.addEventListener('click', onClick, true)
    return () => {
      window.removeEventListener('beforeunload', onBeforeUnload)
      document.removeEventListener('click', onClick, true)
    }
  }, [])

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
    setVersionMsg(null)
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
        onSendReview={(summary) => send.mutate(summary)}
        saveVersionDisabled={!canWrite || saveVersion.isPending}
        onSaveVersion={(summary) => saveVersion.mutate(summary)}
      />
      {holder && (
        <EditLockBanner name={holder.name} initials={holder.initials} minutes={minutesSince(holder.since)} />
      )}
      {tagAttachNotice && (
        <EditNotice testId="edit-tag-attach-notice" tone="warn">
          {tagAttachNotice}
        </EditNotice>
      )}
      {!perms.canEdit && (
        <EditNotice testId="edit-readonly-banner" tone="warn">
          Vous n&apos;avez pas le droit de modifier ce document : il est affiché en lecture seule.
        </EditNotice>
      )}
      {pending && doc.status === 'en_revue' && (
        <EditNotice testId="edit-approval-banner" tone="warn">
          Ce document est en revue.{' '}
          <Link
            to={`/approvals/${pending.approvalRequestId}`}
            className="doc-back"
            data-testid="edit-view-approval"
          >
            Voir la demande
          </Link>
        </EditNotice>
      )}
      {serverDraft && draftStale && !draftBusy && (
        <EditNotice testId="edit-draft-stale-banner" tone="warn">
          Le document a changé depuis votre brouillon (version {serverDraft.baseVersionNo} → {doc.currentVersionNo}).{' '}
          <Link to={`/docs/${id}/history`} target="_blank" rel="noopener" className="doc-back" data-testid="edit-draft-compare">
            Comparer
          </Link>
          {' · '}
          <button
            type="button"
            className="edit-retry"
            disabled={draftBusy}
            onClick={() => void abandonDraft()}
            data-testid="edit-draft-abandon"
          >
            Abandonner le brouillon
          </button>
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
            {draftError && (
              <p className="edit-alert edit-alert--error" role="alert" data-testid="edit-draft-error">
                {draftError}
              </p>
            )}
            {versionMsg && !draftError && (
              <p className="edit-alert" role="status" data-testid="edit-version-msg">
                {versionMsg}{' '}
                <Link to={`/docs/${id}/history`} className="doc-back">
                  Historique
                </Link>
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
              documentId={id || undefined}
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
            canManageGoverned={perms.canManageAccess}
            tagError={tagError}
            onAddTag={(t) => addTag.mutate(t)}
            onRemoveTag={(t) => removeTag.mutate(t)}
            customFields={customFields.data ?? []}
            fieldErrors={fieldErrors}
            savingFields={savingFields}
            onSaveField={(f, v) => void saveField(f, v)}
            missingRequiredFieldIds={missingRequiredFieldIds}
          />
        )}
      </div>

      <DocumentMobileTabs {...tabProps} />
    </div>
  )
}
