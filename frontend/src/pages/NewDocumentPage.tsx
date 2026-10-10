// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useMemo, useState, type KeyboardEvent, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import { organizationDisplayName } from '../lib/auth'
import { createDocument, emptyDocBody } from '../lib/documents'
import {
  documentEditHref,
  flattenFolderOptions,
  getSpaceTree,
  spaceBrowseHref,
  spaceTreeKey,
} from '../lib/folders'
import { listSpaces } from '../lib/spaces'
import {
  attachTag,
  filterTagSuggestions,
  searchTags,
  tagAttachFailureReasonLabel,
  type TagAttachFailure,
  type TagAttachFailuresNavState,
} from '../lib/tags'
import {
  getCreationWarnings,
  listTemplates,
  templatesKey,
  type TemplateSummary,
} from '../lib/templates'

/** Étiquette choisie : existante (`id`) ou à créer (`name` seul). */
type TagChoice = { id?: string; name: string }

const sectionLabel = 'mb-2 text-[12px] font-semibold uppercase tracking-[0.05em] text-socle-muted'

/** Boutons NewDocument.dc.html : ghost 13.5/500 (9×16) et CTA 13.5/600 (9×20), rayon 7. */
/** Alignement vertical maquette NewDocument (padding 9×16 / 9×20, leading UA). */
const btnGhost =
  'flex flex-col justify-start rounded-[7px] border border-socle-line px-4 py-[9px] text-[13.5px] font-medium leading-[normal] text-[#43434A] hover:bg-[#F5F5F7] disabled:opacity-60'
const btnCta =
  'flex flex-col justify-start rounded-[7px] bg-socle-accent px-5 py-[9px] text-[13.5px] font-semibold leading-[normal] text-white hover:bg-socle-accent-hover disabled:opacity-60'

/** Champ maquette : bordure 1px #ECECEE, rayon 9, focus #C7C6F5. */
const fieldBox = 'rounded-[9px] border border-[#ECECEE] bg-white focus-within:border-[#C7C6F5]'

const slug = (s: string) =>
  s
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')

/** Formulaire de création en une page (maquette NewDocument) : modèle, emplacement, titre, tags. */
export function NewDocumentPage() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [params] = useSearchParams()

  const [spaceId, setSpaceId] = useState(params.get('spaceId') ?? '')
  const [folderId, setFolderId] = useState<string | null>(params.get('folderId') || null)
  const [templateId, setTemplateId] = useState<string | null>(null)
  const [title, setTitle] = useState('')
  const [tags, setTags] = useState<TagChoice[]>([])
  const [tagQuery, setTagQuery] = useState('')
  const [tagsOpen, setTagsOpen] = useState(false)

  const spaces = useQuery({ queryKey: ['spaces'], queryFn: () => listSpaces(api) })
  const spaceList = useMemo(
    () => (spaces.data ?? []).filter((s) => s.membership !== 'public-only'),
    [spaces.data],
  )
  const soleSpaceId = spaceList.length === 1 ? spaceList[0].id : ''
  const activeSpaceId = spaceId || soleSpaceId
  const space = spaceList.find((s) => s.id === activeSpaceId)

  const tree = useQuery({
    queryKey: spaceTreeKey(activeSpaceId),
    queryFn: () => getSpaceTree(api, activeSpaceId),
    enabled: Boolean(activeSpaceId),
  })
  const folderOptions = useMemo(
    () => flattenFolderOptions(tree.data ?? { folders: [], documents: [] }),
    [tree.data],
  )
  const folder = folderId ? folderOptions.find((f) => f.id === folderId) : undefined
  // Un dossier inconnu (lien périmé, autre espace) retombe sur la racine.
  const activeFolderId = tree.data && folderId && !folder ? null : folderId
  const rootNames = folderOptions.filter((f) => f.depth === 1).map((f) => f.name)
  const folderHint = `À la racine de l'espace, ou dans un dossier existant${
    rootNames.length ? ` (${rootNames.slice(0, 2).join(', ')}${rootNames.length > 1 ? '…' : ''})` : ''
  } pour en faire un sous-élément.`

  const templates = useQuery({
    queryKey: templatesKey(activeSpaceId),
    queryFn: () => listTemplates(api, activeSpaceId),
    enabled: Boolean(activeSpaceId),
  })
  const allTemplates = templates.data ?? []
  const systemTemplates = allTemplates.filter((t) => !t.createdBy)
  const customTemplates = allTemplates.filter((t) => t.createdBy)
  const renderTemplate = (t: TemplateSummary) => (
    <TemplateCard
      key={t.id}
      template={t}
      selected={templateId === t.id}
      onSelect={() => setTemplateId(t.id)}
      name={t.name}
      description={t.description ?? ''}
    />
  )

  const warnings = useQuery({
    queryKey: ['template-warnings', templateId, activeSpaceId],
    queryFn: () => getCreationWarnings(api, templateId as string, activeSpaceId),
    enabled: Boolean(templateId) && Boolean(activeSpaceId),
  })
  const warningList = warnings.data ?? []

  const tagSuggestions = useQuery({
    queryKey: ['tags', 'search', tagQuery.trim()],
    queryFn: () => searchTags(api, tagQuery),
    enabled: tagsOpen,
  })
  const suggestions = useMemo(() => {
    const picked = new Set(tags.map((t) => t.name.toLowerCase()))
    return filterTagSuggestions(
      tagSuggestions.data ?? [],
      tags.flatMap((t) => (t.id ? [{ id: t.id, name: t.name }] : [])),
    ).filter((s) => !picked.has(s.name.trim().toLowerCase()))
  }, [tagSuggestions.data, tags])

  const create = useMutation({
    mutationFn: async () => {
      const doc = await createDocument(
        api,
        title.trim(),
        activeSpaceId,
        templateId ? null : emptyDocBody,
        activeFolderId,
        { templateId },
      )
      // Le document existe déjà : un échec de rattachement ne doit pas bloquer l'ouverture.
      const settled = await Promise.allSettled(
        tags.map((t) => attachTag(api, doc.id, t.id ? { tagId: t.id } : { name: t.name })),
      )
      const tagAttachFailures: TagAttachFailure[] = []
      settled.forEach((r, i) => {
        if (r.status === 'rejected') {
          tagAttachFailures.push({
            name: tags[i]!.name,
            reasonLabel: tagAttachFailureReasonLabel(r.reason),
          })
        }
      })
      return { doc, tagAttachFailures }
    },
    onSuccess: ({ doc, tagAttachFailures }) => {
      void qc.invalidateQueries({ queryKey: spaceTreeKey(activeSpaceId) })
      void qc.invalidateQueries({ queryKey: ['documents'] })
      const state: TagAttachFailuresNavState | undefined =
        tagAttachFailures.length > 0 ? { tagAttachFailures } : undefined
      navigate(documentEditHref(doc.id), state ? { state } : undefined)
    },
  })

  function chooseSpace(id: string) {
    if (id === activeSpaceId) return
    setSpaceId(id)
    setFolderId(null)
    setTemplateId(null)
  }

  function addTag(choice: TagChoice) {
    const name = choice.name.trim()
    if (name && !tags.some((t) => t.name.toLowerCase() === name.toLowerCase())) {
      setTags([...tags, { id: choice.id, name }])
    }
    setTagQuery('')
  }

  function commitTypedTag() {
    const name = tagQuery.trim()
    if (!name) return
    const known = (tagSuggestions.data ?? []).find(
      (s) => s.name.trim().toLowerCase() === name.toLowerCase(),
    )
    addTag(known ? { id: known.id, name: known.name } : { name })
  }

  function onTagKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      commitTypedTag()
    } else if (e.key === 'Backspace' && !tagQuery && tags.length > 0) {
      setTags(tags.slice(0, -1))
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (title.trim() && activeSpaceId && !create.isPending) create.mutate()
  }

  const closeHref = activeSpaceId ? spaceBrowseHref(activeSpaceId) : '/docs'

  return (
    <main className="mx-auto max-w-[840px] px-6 pb-10 pt-8 leading-[normal] md:px-10 md:pt-0">
      <div className="mb-6 flex items-center justify-between gap-4 md:-mb-[7px] md:h-[59px]">
        <div className="breadcrumb gap-[7px] text-[13px]" data-mock-id="newdoc-breadcrumb">
          <Link to="/">Accueil</Link>{' '}
          <span className="text-[#DEDEE1]">→</span>{' '}
          <span className="font-medium text-socle-ink">Nouveau document</span>
        </div>
        <Link
          to={closeHref}
          aria-label="Fermer"
          data-mock-id="newdoc-close"
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-[7px] text-socle-slate hover:bg-[#F5F5F7]"
        >
          <svg
            width="14"
            height="14"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            aria-hidden
          >
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </Link>
      </div>

      <h1
        className="mb-1.5 font-display text-[36px] font-normal leading-[normal] text-socle-ink"
        data-mock-id="newdoc-title"
      >
        Créer un document
      </h1>
      <p className="mb-[30px] text-[14px] text-socle-muted" data-mock-id="newdoc-lead">
        Choisissez un modèle pour démarrer avec une structure adaptée au type de contenu.
      </p>

      <form onSubmit={onSubmit} aria-label="Nouveau document">
        <div
          className="mb-[10px] flex items-center justify-between gap-3"
          data-mock-id="newdoc-models-head"
        >
          <div className="text-[12px] font-semibold uppercase tracking-[0.05em] text-socle-muted">
            Modèle
          </div>
          <Link
            to="/admin/templates"
            className="text-[12px] font-semibold text-socle-accent hover:underline"
          >
            Gérer les modèles →
          </Link>
        </div>
        {templates.isError && (
          <p className="mb-3 text-sm text-socle-danger" role="alert">
            Modèles indisponibles — vous pouvez partir d’un document vierge.
          </p>
        )}
        <div role="radiogroup" aria-label="Modèle" className="mb-7 grid gap-3 sm:grid-cols-2">
          {/* Ordre maquette : modèles système, « Document vierge », puis modèles personnalisés. */}
          {systemTemplates.map(renderTemplate)}
          <TemplateCard
            blank
            selected={templateId === null}
            onSelect={() => setTemplateId(null)}
            name="Document vierge"
            description="Partir d'une page blanche, sans structure imposée."
          />
          {customTemplates.map(renderTemplate)}
        </div>

        <div className="mb-[18px] flex gap-[14px]">
          <div className="min-w-0 flex-1" data-mock-id="newdoc-space-select">
            <label htmlFor="newdoc-space" className={`${sectionLabel} block`}>
              Espace
            </label>
            <SelectField
              id="newdoc-space"
              value={activeSpaceId}
              onChange={chooseSpace}
              swatch={space ? (space.color ?? '') : null}
              disabled={spaces.isLoading || spaceList.length === 0}
            >
              {!activeSpaceId && <option value="">Choisir un espace</option>}
              {spaceList.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </SelectField>
          </div>
          {/* Maquette « Fiabilité cible » : pas de paramètre à la création (NOT_IMPLEMENTED). */}
          <div className="min-w-0 flex-1" data-visual-mask="newdoc-fiabilite">
            <div className={sectionLabel}>Fiabilité cible</div>
            <div
              className={`${fieldBox} flex items-center justify-between px-[13px] py-[10px] opacity-60`}
              aria-disabled="true"
            >
              <span className="text-[13.5px] font-medium leading-[normal] text-socle-muted">
                Bientôt
              </span>
              <Chevron />
            </div>
          </div>
        </div>
        {spaces.isError && (
          <p className="-mt-3 mb-4 text-sm text-socle-danger" role="alert">
            Impossible de charger les espaces.
          </p>
        )}
        {spaces.data && spaceList.length === 0 && (
          <p className="-mt-3 mb-4 text-sm text-socle-muted">
            Aucun espace disponible.{' '}
            <Link to="/spaces" className="font-semibold text-socle-accent hover:underline">
              Créer un espace
            </Link>
          </p>
        )}

        <div className="mb-[22px]" data-mock-id="newdoc-folder-select">
          <label htmlFor="newdoc-folder" className={`${sectionLabel} block`}>
            {"Emplacement dans l'arborescence"}
          </label>
          <SelectField
            id="newdoc-folder"
            value={folder?.id ?? ''}
            onChange={(id) => setFolderId(id || null)}
            disabled={!activeSpaceId}
          >
            <option value="">{`${space?.name ?? 'Espace'} (racine)`}</option>
            {folderOptions.map((f) => (
              <option key={f.id} value={f.id ?? ''}>
                {'\u00A0\u00A0'.repeat(Math.max(0, f.depth - 1))}
                {f.name}
              </option>
            ))}
          </SelectField>
          {tree.isError ? (
            <p className="mt-1.5 text-[11.5px] text-socle-danger" role="alert">
              Arborescence indisponible — le document sera créé à la racine.
            </p>
          ) : (
            <p className="mt-1.5 text-[11.5px] text-socle-faint">{folderHint}</p>
          )}
        </div>

        {/* mb 22.02 px : compense la hauteur 45.98 px du champ pour caler la suite sur la maquette (y .5). */}
        <label className="mb-[22.02px] block" data-mock-id="newdoc-title-field">
          <span className={`${sectionLabel} block`}>Titre du document</span>
          <input
            className="block w-full rounded-[9px] border border-socle-line bg-white px-[14px] pb-[10.203px] pt-[13px] text-[15px] leading-[20.8px] text-socle-ink outline-none placeholder:text-[#C2C2C6] focus:border-[#C7C6F5]"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Ex. Politique de classification des données"
            aria-label="Titre du document"
            required
          />
        </label>

        <div className="relative mb-7" data-mock-id="newdoc-tags">
          <label htmlFor="newdoc-tags-input" className={`${sectionLabel} block`}>
            Tags <span className="font-normal normal-case text-[#C2C2C6]">(optionnel)</span>
          </label>
          <div className={`${fieldBox} flex flex-wrap items-center gap-1.5 px-2.5 py-[9px]`}>
            {tags.map((t) => (
              <span
                key={t.id ?? `new:${t.name}`}
                className="inline-flex items-center gap-[5px] rounded-md bg-socle-mist py-[3px] pl-[9px] pr-2 text-[12px] font-semibold text-socle-accent"
              >
                {t.name}
                <button
                  type="button"
                  aria-label={`Retirer le tag ${t.name}`}
                  className="text-[#9B95E6] hover:text-socle-accent"
                  onClick={() => setTags(tags.filter((x) => x !== t))}
                >
                  ×
                </button>
              </span>
            ))}
            <input
              id="newdoc-tags-input"
              className="min-w-[200px] flex-1 bg-transparent text-[13.5px] leading-[normal] text-socle-ink outline-none placeholder:text-[#C2C2C6]"
              placeholder="Ajouter un tag existant ou en créer un…"
              value={tagQuery}
              autoComplete="off"
              onChange={(e) => setTagQuery(e.target.value)}
              onKeyDown={onTagKeyDown}
              onFocus={() => setTagsOpen(true)}
              onBlur={() => setTagsOpen(false)}
            />
          </div>
          {tagsOpen && suggestions.length > 0 && (
            <ul
              role="listbox"
              aria-label="Tags existants"
              className="absolute left-0 right-0 z-10 mt-1 max-h-48 overflow-y-auto rounded-[9px] border border-[#ECECEE] bg-white p-1 shadow-md"
            >
              {suggestions.map((s) => (
                <li key={s.id} role="option" aria-selected={false}>
                  <button
                    type="button"
                    className="w-full rounded-[6px] px-2.5 py-1.5 text-left text-[13px] text-[#4B4B52] hover:bg-[#F5F5F7]"
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={() => addTag({ id: s.id, name: s.name })}
                  >
                    {s.name}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        {warningList.length > 0 && (
          <div
            className="mb-5 rounded-lg border border-[#E8D9A8] bg-[#FBF3E4] px-4 py-3 text-sm text-socle-warn"
            role="alert"
            data-testid="creation-warnings"
          >
            <p className="font-semibold">
              {warningList.length > 1 ? 'Points d’attention' : 'Point d’attention'} avant la création
            </p>
            <ul className="mt-1.5 list-disc space-y-1 pl-5 text-socle-slate">
              {warningList.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          </div>
        )}

        {create.isError && (
          <p className="mb-4 text-sm text-socle-danger" role="alert">
            {apiErrorMessage(create.error, 'Création refusée — accès editor requis sur l’espace')}
          </p>
        )}

        <Footer note="Le document sera créé en brouillon.">
          <button
            type="button"
            className={`${btnGhost} bg-white`}
            data-mock-id="newdoc-cancel"
            onClick={() => navigate(closeHref)}
          >
            Annuler
          </button>
          <button
            type="submit"
            className={btnCta}
            data-mock-id="newdoc-cta"
            disabled={!title.trim() || !activeSpaceId || create.isPending || warnings.isLoading}
          >
            {create.isPending
              ? 'Création…'
              : warningList.length > 0
                ? 'Créer quand même'
                : 'Créer le document'}
          </button>
        </Footer>
      </form>
    </main>
  )
}

function Footer({ children, note }: { children: ReactNode; note?: string }) {
  return (
    <div className="flex items-center justify-between gap-4 pb-12">
      <span className="text-[12.5px] text-socle-muted" data-visual-mask="newdoc-footer-note">
        {note}
      </span>
      <div className="flex shrink-0 gap-2.5" data-mock-id="newdoc-actions">
        {children}
      </div>
    </div>
  )
}

function Chevron() {
  return (
    <svg
      width="12"
      height="12"
      viewBox="0 0 24 24"
      fill="none"
      stroke="#9B9BA1"
      strokeWidth="2"
      aria-hidden
      className="shrink-0"
    >
      <polyline points="6 9 12 15 18 9" />
    </svg>
  )
}

/**
 * <select> natif habillé comme les champs de la maquette (bordure #ECECEE, rayon 9, 10×13, chevron).
 * `swatch` : `undefined` = pas de pastille ; `null` = pastille vide ; `''` = couleur d'accent.
 */
function SelectField({
  id,
  value,
  onChange,
  swatch,
  disabled,
  children,
}: {
  id: string
  value: string
  onChange: (value: string) => void
  swatch?: string | null
  disabled?: boolean
  children: ReactNode
}) {
  return (
    <div className="relative">
      {swatch !== undefined && (
        <span
          aria-hidden
          style={swatch ? { backgroundColor: swatch } : undefined}
          className={`pointer-events-none absolute left-[13px] top-1/2 h-[9px] w-[9px] -translate-y-1/2 rounded-[3px] ${
            swatch === null ? 'border border-socle-faint' : swatch === '' ? 'bg-socle-accent' : ''
          }`}
        />
      )}
      <select
        id={id}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        className={`block w-full cursor-pointer appearance-none truncate rounded-[9px] border border-[#ECECEE] bg-white py-[10px] pr-[34px] text-[13.5px] font-medium leading-[18px] text-socle-ink outline-none focus:border-[#C7C6F5] disabled:cursor-default disabled:opacity-60 ${
          swatch !== undefined ? 'pl-[31px]' : 'pl-[13px]'
        }`}
      >
        {children}
      </select>
      <span className="pointer-events-none absolute right-[13px] top-1/2 flex -translate-y-1/2">
        <Chevron />
      </span>
    </div>
  )
}

/** Icône de modèle (maquette) : selon le type de document, ou étiquette pour un modèle personnalisé. */
function TemplateIcon({ template, blank }: { template?: TemplateSummary; blank?: boolean }) {
  if (blank) {
    return (
      <>
        <line x1="12" y1="5" x2="12" y2="19" />
        <line x1="5" y1="12" x2="19" y2="12" />
      </>
    )
  }
  if (template?.createdBy) {
    return (
      <>
        <path d="M20.59 13.41L13.42 20.58a2 2 0 0 1-2.83 0L2.59 12.58a2 2 0 0 1 0-2.83L9.76 2.58A2 2 0 0 1 11.17 2H18a2 2 0 0 1 2 2v6.83a2 2 0 0 1-.59 1.41z" />
        <line x1="7" y1="7" x2="7.01" y2="7" />
      </>
    )
  }
  switch (template?.docType) {
    case 'politique':
      return (
        <>
          <path d="M9 12l2 2 4-4" />
          <circle cx="12" cy="12" r="9" />
        </>
      )
    case 'procedure':
      return <path d="M9 18l6-6-6-6" />
    case 'guide':
      return (
        <>
          <circle cx="12" cy="12" r="9" />
          <line x1="12" y1="8" x2="12" y2="12" />
          <circle cx="12" cy="16" r="0.5" fill="currentColor" />
        </>
      )
    default:
      return (
        <>
          <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
          <polyline points="14 3 14 8 19 8" />
        </>
      )
  }
}

function TemplateCard({
  name,
  description,
  selected,
  onSelect,
  blank,
  template,
}: {
  name: string
  description: string
  selected: boolean
  onSelect: () => void
  blank?: boolean
  template?: TemplateSummary
}) {
  // Badge : uniquement pour un modèle personnalisé (créé par un utilisateur, `createdBy` renseigné).
  const badge = template?.createdBy
    ? template.scope === 'space'
      ? 'Espace'
      : organizationDisplayName()
    : undefined
  const custom = Boolean(template?.createdBy)
  const iconSize = blank ? 14 : 15
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={onSelect}
      data-mock-id={`newdoc-tpl-${slug(name)}`}
      className={`flex flex-col items-start justify-start rounded-xl border-[1.5px] px-[18px] py-4 text-left transition ${
        selected
          ? 'border-socle-accent bg-socle-mist'
          : blank
            ? 'border-dashed border-[#DEDEE1] hover:border-socle-accent hover:bg-[#FAFAFE]'
            : 'border-socle-line hover:border-socle-accent hover:bg-[#FAFAFE]'
      }`}
    >
      <div
        aria-hidden
        className={`mb-3 flex h-[30px] w-[30px] items-center justify-center rounded-lg ${
          blank
            ? 'box-content border-[1.5px] border-dashed border-[#DEDEE1] text-socle-muted'
            : selected
              ? 'bg-socle-accent text-white'
              : custom
                ? 'bg-socle-mist text-socle-accent'
                : 'bg-[#F0EFEA] text-[#6B6862]'
        }`}
      >
        <svg
          width={iconSize}
          height={iconSize}
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <TemplateIcon template={template} blank={blank} />
        </svg>
      </div>
      <div className="mb-1 flex items-center gap-1.5">
        <span className="text-[14px] font-semibold text-socle-ink">{name}</span>
        {badge && (
          <span className="rounded bg-socle-mist px-[5px] py-px text-[9.5px] font-semibold text-socle-accent">
            {badge}
          </span>
        )}
      </div>
      {description && <p className="text-[12.5px] leading-normal text-socle-slate">{description}</p>}
    </button>
  )
}
