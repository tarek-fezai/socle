// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import { emptyDocBody } from '../lib/documents'
import { listSpaces } from '../lib/spaces'
import {
  countPlaceholders,
  createTemplate,
  deleteTemplate,
  formatTagIds,
  getTemplate,
  listTemplates,
  parseTagIds,
  placeholderBannerText,
  templatesKey,
  updateTemplate,
  type TemplateResponse,
  type TemplateSummary,
} from '../lib/templates'
import { DocumentEditor } from './DocumentEditor'

type EditorState = {
  /** null = nouveau modèle (ou duplication) */
  template: TemplateResponse | null
  name: string
  description: string
  docType: string
  tags: string
  body: Record<string, unknown>
  scope: 'global' | 'space'
  spaceId: string
}

const labelCls = 'mb-1.5 block text-[12px] font-semibold uppercase tracking-[0.05em] text-socle-muted'
const actionCls = 'text-[12.5px] font-semibold hover:underline'

/** Administration des modèles de pages : globaux et par espace (maquette TemplatesAdmin). */
export function TemplatesAdminPage() {
  const qc = useQueryClient()
  const [spaceFilter, setSpaceFilter] = useState('')
  const [editor, setEditor] = useState<EditorState | null>(null)
  const [error, setError] = useState<string | null>(null)

  const spaces = useQuery({ queryKey: ['spaces'], queryFn: () => listSpaces(api) })
  const spaceName = (id: string | null) =>
    spaces.data?.find((s) => s.id === id)?.name ?? 'Espace'

  const templates = useQuery({
    queryKey: templatesKey(spaceFilter || null),
    queryFn: () => listTemplates(api, spaceFilter || null),
  })
  const all = templates.data ?? []
  const globals = all.filter((t) => !t.spaceId)
  const forSpace = spaceFilter ? all.filter((t) => t.spaceId === spaceFilter) : []

  const save = useMutation({
    mutationFn: async (s: EditorState) => {
      const base = {
        name: s.name.trim(),
        description: s.description.trim() || null,
        docType: s.docType.trim() || null,
        defaultTagIds: parseTagIds(s.tags),
        body: s.body,
      }
      if (s.template) return updateTemplate(api, s.template.id, base)
      return createTemplate(api, {
        ...base,
        spaceId: s.scope === 'space' ? s.spaceId : null,
      })
    },
    onSuccess: () => {
      setError(null)
      setEditor(null)
      void qc.invalidateQueries({ queryKey: ['templates'] })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Enregistrement du modèle impossible')),
  })

  const remove = useMutation({
    mutationFn: (t: TemplateSummary) => deleteTemplate(api, t.id),
    onSuccess: () => {
      setError(null)
      void qc.invalidateQueries({ queryKey: ['templates'] })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Suppression impossible')),
  })

  function openNew() {
    setError(null)
    setEditor({
      template: null,
      name: '',
      description: '',
      docType: '',
      tags: '',
      body: emptyDocBody,
      scope: spaceFilter ? 'space' : 'global',
      spaceId: spaceFilter || spaces.data?.[0]?.id || '',
    })
  }

  /** La liste ne contient pas le corps : on charge le détail avant d'ouvrir l'éditeur. */
  async function openFrom(t: TemplateSummary, duplicate: boolean) {
    setError(null)
    try {
      const full = await getTemplate(api, t.id)
      setEditor({
        template: duplicate ? null : full,
        name: duplicate ? `${full.name} (copie)` : full.name,
        description: full.description ?? '',
        docType: full.docType ?? '',
        tags: formatTagIds(full.defaultTagIds),
        body: full.body ?? emptyDocBody,
        scope: full.scope,
        spaceId: full.spaceId ?? '',
      })
    } catch (e) {
      setError(apiErrorMessage(e, 'Impossible de charger le modèle'))
    }
  }

  const openEdit = (t: TemplateSummary) => void openFrom(t, false)
  const openDuplicate = (t: TemplateSummary) => void openFrom(t, true)

  function onDelete(t: TemplateSummary) {
    if (window.confirm(`Supprimer le modèle « ${t.name} » ? Les documents déjà créés ne sont pas modifiés.`)) {
      remove.mutate(t)
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (!editor || !editor.name.trim()) return
    if (!editor.template && editor.scope === 'space' && !editor.spaceId) return
    save.mutate(editor)
  }

  return (
    <main className="page-shell-wide">
      <div className="breadcrumb mb-3">
        <Link to="/">Accueil</Link>
        <span className="text-[#DEDEE1]">→</span>
        <span className="font-medium text-socle-ink">Modèles</span>
      </div>

      <div className="mb-1.5 flex items-start justify-between gap-4">
        <h1 className="serif-title">Modèles</h1>
        {!editor && (
          <button type="button" className="btn-primary shrink-0" onClick={openNew}>
            + Nouveau modèle
          </button>
        )}
      </div>
      <p className="mb-6 max-w-2xl text-sm text-socle-muted">
        Structures de départ proposées à la création d’un document. Les modèles globaux sont communs
        à tous les espaces ; les modèles d’espace ne sont proposés que dans cet espace.
      </p>

      {error && (
        <p className="mb-4 text-sm text-socle-danger" role="alert">
          {error}
        </p>
      )}

      {editor ? (
        <form onSubmit={onSubmit} className="space-y-5" aria-label="Édition du modèle">
          <h2 className="font-display text-2xl font-normal text-socle-ink">
            {editor.template ? `Modifier « ${editor.template.name} »` : 'Nouveau modèle'}
          </h2>

          <div className="grid gap-4 md:grid-cols-2">
            <label className="block">
              <span className={labelCls}>Nom</span>
              <input
                className="field-input"
                value={editor.name}
                onChange={(e) => setEditor({ ...editor, name: e.target.value })}
                aria-label="Nom du modèle"
                required
              />
            </label>
            <label className="block">
              <span className={labelCls}>Type de document</span>
              <input
                className="field-input"
                value={editor.docType}
                onChange={(e) => setEditor({ ...editor, docType: e.target.value })}
                aria-label="Type de document"
                placeholder="ex. politique, procédure"
              />
            </label>
          </div>

          <label className="block">
            <span className={labelCls}>Description</span>
            <input
              className="field-input"
              value={editor.description}
              onChange={(e) => setEditor({ ...editor, description: e.target.value })}
              aria-label="Description"
            />
          </label>

          <label className="block">
            <span className={labelCls}>
              Tags par défaut{' '}
              <span className="font-normal normal-case text-socle-faint">
                (identifiants de tags séparés par des virgules)
              </span>
            </span>
            <input
              className="field-input font-mono text-xs"
              value={editor.tags}
              onChange={(e) => setEditor({ ...editor, tags: e.target.value })}
              aria-label="Tags par défaut"
              placeholder="uuid, uuid"
            />
          </label>

          {!editor.template && (
            <div className="flex flex-wrap items-end gap-4">
              <label className="block">
                <span className={labelCls}>Portée</span>
                <select
                  className="field-input"
                  value={editor.scope}
                  onChange={(e) => setEditor({ ...editor, scope: e.target.value as 'global' | 'space' })}
                  aria-label="Portée"
                >
                  <option value="global">Global (toute l’organisation)</option>
                  <option value="space">Un espace</option>
                </select>
              </label>
              {editor.scope === 'space' && (
                <label className="block">
                  <span className={labelCls}>Espace</span>
                  <select
                    className="field-input"
                    value={editor.spaceId}
                    onChange={(e) => setEditor({ ...editor, spaceId: e.target.value })}
                    aria-label="Espace du modèle"
                  >
                    {(spaces.data ?? []).map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.name}
                      </option>
                    ))}
                  </select>
                </label>
              )}
            </div>
          )}

          <div>
            <span className={labelCls}>Contenu</span>
            <DocumentEditor
              templateTools
              content={editor.body}
              onChange={(body) => setEditor((prev) => (prev ? { ...prev, body } : prev))}
            />
            <p className="mt-1.5 text-xs text-socle-muted">
              {countPlaceholders(editor.body) > 0
                ? placeholderBannerText(countPlaceholders(editor.body))
                : 'Aucune zone à compléter.'}{' '}
              Les variables {'{{date}}'}, {'{{auteur}}'}, {'{{espace}}'} et {'{{titre}}'} sont
              remplacées à la création.
            </p>
          </div>

          <div className="flex justify-end gap-2">
            <button type="button" className="btn-ghost" onClick={() => setEditor(null)}>
              Annuler
            </button>
            <button type="submit" className="btn-primary" disabled={save.isPending || !editor.name.trim()}>
              {save.isPending ? 'Enregistrement…' : 'Enregistrer le modèle'}
            </button>
          </div>
        </form>
      ) : (
        <>
          <label className="mb-6 flex items-center gap-3 text-sm">
            <span className="text-socle-muted">Espace</span>
            <select
              className="rounded-lg border border-socle-line bg-white px-3 py-2 text-sm"
              value={spaceFilter}
              onChange={(e) => setSpaceFilter(e.target.value)}
              aria-label="Espace"
            >
              <option value="">Modèles globaux uniquement</option>
              {(spaces.data ?? []).map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>

          {templates.isLoading && <p className="text-sm text-socle-muted">Chargement…</p>}
          {templates.isError && (
            <p className="text-sm text-socle-danger" role="alert">
              Impossible de charger les modèles.
            </p>
          )}

          {templates.data && (
            <div className="space-y-7">
              <TemplateSection
                title="Modèles globaux"
                items={globals}
                emptyText="Aucun modèle global."
                onEdit={openEdit}
                onDuplicate={openDuplicate}
                onDelete={onDelete}
              />
              {spaceFilter && (
                <TemplateSection
                  title={`Modèles de l’espace — ${spaceName(spaceFilter)}`}
                  items={forSpace}
                  emptyText="Aucun modèle pour cet espace."
                  onEdit={openEdit}
                  onDuplicate={openDuplicate}
                  onDelete={onDelete}
                />
              )}
            </div>
          )}

          <div className="mt-8 rounded-r-lg border-l-[3px] border-socle-accent bg-[#FAFAFE] px-[18px] py-3.5 text-[13px] leading-relaxed text-[#33333C]">
            Un modèle peut aussi partir d’un document existant : ouvrez-le, puis « Enregistrer comme
            modèle ». Vous ne pouvez modifier ou supprimer que les modèles dont vous avez la
            gestion ; les autres peuvent être dupliqués pour créer une variante.
          </div>
        </>
      )}
    </main>
  )
}

function TemplateSection({
  title,
  items,
  emptyText,
  onEdit,
  onDuplicate,
  onDelete,
}: {
  title: string
  items: TemplateSummary[]
  emptyText: string
  onEdit: (t: TemplateSummary) => void
  onDuplicate: (t: TemplateSummary) => void
  onDelete: (t: TemplateSummary) => void
}) {
  return (
    <section aria-label={title}>
      <div className="mb-2.5 text-[11px] font-semibold uppercase tracking-[0.06em] text-socle-faint">
        {title}
      </div>
      <div className="overflow-hidden rounded-[10px] border border-socle-line">
        <div className="flex bg-socle-soft px-4 py-2.5 text-[11px] font-semibold uppercase tracking-[0.05em] text-socle-faint">
          <div className="flex-[2.4]">Modèle</div>
          <div className="flex-1">Type</div>
          <div className="w-[210px]">Actions</div>
        </div>
        {items.length === 0 && <p className="px-4 py-5 text-sm text-socle-muted">{emptyText}</p>}
        <ul>
          {items.map((t) => {
            const canEdit = t.canManage
            return (
              <li
                key={t.id}
                className="flex items-center border-t border-[#F5F5F7] px-4 py-3 text-[13.5px] first:border-t-0 hover:bg-socle-soft"
              >
                <div className="flex-[2.4] pr-3">
                  <div className="font-semibold text-socle-ink">{t.name}</div>
                  {t.description && (
                    <div className="text-[11.5px] text-socle-muted">{t.description}</div>
                  )}
                </div>
                <div className="flex-1 text-socle-slate">{t.docType || '—'}</div>
                <div className="flex w-[210px] items-center gap-3">
                  {canEdit && (
                    <button
                      type="button"
                      className={`${actionCls} text-socle-accent`}
                      onClick={() => onEdit(t)}
                      aria-label={`Modifier ${t.name}`}
                    >
                      Modifier
                    </button>
                  )}
                  <button
                    type="button"
                    className={`${actionCls} text-socle-slate`}
                    onClick={() => onDuplicate(t)}
                    aria-label={`Dupliquer ${t.name}`}
                  >
                    Dupliquer
                  </button>
                  {canEdit && (
                    <button
                      type="button"
                      className={`${actionCls} text-socle-danger`}
                      onClick={() => onDelete(t)}
                      aria-label={`Supprimer ${t.name}`}
                    >
                      Supprimer
                    </button>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      </div>
    </section>
  )
}
