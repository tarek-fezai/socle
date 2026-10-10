// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useMemo, useState, type ReactNode } from 'react'
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
  filterTemplates,
  getCreationWarnings,
  listTemplates,
  templatesKey,
  type TemplateSummary,
} from '../lib/templates'

type Step = 1 | 2 | 3

const STEPS: Array<{ n: Step; label: string }> = [
  { n: 1, label: 'Emplacement' },
  { n: 2, label: 'Modèle' },
  { n: 3, label: 'Titre' },
]

const sectionLabel = 'mb-2 text-[12px] font-semibold uppercase tracking-[0.05em] text-socle-muted'

/** Boutons NewDocument.dc.html : ghost 13.5/500 (9×16) et CTA 13.5/600 (9×20), rayon 7. */
/** Alignement vertical maquette NewDocument (padding 9×16 / 9×20, leading UA). */
const btnGhost =
  'flex flex-col justify-start rounded-[7px] border border-socle-line px-4 py-[9px] text-[13.5px] font-medium leading-[normal] text-[#43434A] hover:bg-[#F5F5F7] disabled:opacity-60'
const btnCta =
  'flex flex-col justify-start rounded-[7px] bg-socle-accent px-5 py-[9px] text-[13.5px] font-semibold leading-[normal] text-white hover:bg-socle-accent-hover disabled:opacity-60'

const slug = (s: string) =>
  s
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')

/** Assistant de création en 3 étapes : emplacement → modèle → titre (maquette NewDocument). */
export function NewDocumentPage() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [params] = useSearchParams()

  const [step, setStep] = useState<Step>(1)
  const [spaceId, setSpaceId] = useState(params.get('spaceId') ?? '')
  const [folderId, setFolderId] = useState<string | null>(params.get('folderId') || null)
  const [templateId, setTemplateId] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [title, setTitle] = useState('')

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

  const templates = useQuery({
    queryKey: templatesKey(activeSpaceId),
    queryFn: () => listTemplates(api, activeSpaceId),
    enabled: step >= 2 && Boolean(activeSpaceId),
  })
  const visibleTemplates = useMemo(
    () => filterTemplates(templates.data ?? [], search),
    [templates.data, search],
  )
  const systemTemplates = visibleTemplates.filter((t) => !t.createdBy)
  const customTemplates = visibleTemplates.filter((t) => t.createdBy)
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
  const selectedTemplate = templates.data?.find((t) => t.id === templateId) ?? null

  const warnings = useQuery({
    queryKey: ['template-warnings', templateId, activeSpaceId],
    queryFn: () => getCreationWarnings(api, templateId as string, activeSpaceId),
    enabled: step === 3 && Boolean(templateId) && Boolean(activeSpaceId),
  })
  const warningList = warnings.data ?? []

  const create = useMutation({
    mutationFn: () =>
      createDocument(
        api,
        title.trim(),
        activeSpaceId,
        templateId ? null : emptyDocBody,
        activeFolderId,
        { templateId },
      ),
    onSuccess: (doc) => {
      void qc.invalidateQueries({ queryKey: spaceTreeKey(activeSpaceId) })
      void qc.invalidateQueries({ queryKey: ['documents'] })
      navigate(documentEditHref(doc.id))
    },
  })

  function chooseSpace(id: string) {
    if (id === activeSpaceId) return
    setSpaceId(id)
    setFolderId(null)
    setTemplateId(null)
    setSearch('')
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (title.trim() && activeSpaceId) create.mutate()
  }

  const closeHref = activeSpaceId ? spaceBrowseHref(activeSpaceId) : '/docs'
  const locationLabel = `${space?.name ?? 'Espace'}${folder ? ` / ${folder.name}` : ' (racine)'}`

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

      <ol className="mb-8 flex items-center gap-2" aria-label="Étapes de création">
        {STEPS.map(({ n, label }, i) => {
          const current = step === n
          const done = step > n
          return (
            <li key={n} className="flex items-center gap-2" aria-current={current ? 'step' : undefined}>
              {i > 0 && <span className="h-px w-6 bg-socle-line" aria-hidden />}
              <span
                className={`flex h-6 w-6 items-center justify-center rounded-full text-xs font-semibold ${
                  current
                    ? 'bg-socle-accent text-white'
                    : done
                      ? 'bg-socle-mist text-socle-accent'
                      : 'border border-socle-line text-socle-muted'
                }`}
              >
                {n}
              </span>
              <span
                className={`text-[13px] ${current ? 'font-semibold text-socle-ink' : 'text-socle-muted'}`}
              >
                {label}
              </span>
            </li>
          )
        })}
      </ol>

      {step === 1 && (
        <section aria-labelledby="step1-title" data-testid="step-1">
          <h2 id="step1-title" className="sr-only">
            Étape 1 sur 3 : emplacement
          </h2>
          <div className={sectionLabel}>Espace</div>
          {spaces.isLoading && <p className="text-sm text-socle-muted">Chargement…</p>}
          {spaces.isError && (
            <p className="text-sm text-socle-danger" role="alert">
              Impossible de charger les espaces.
            </p>
          )}
          {spaces.data && spaceList.length === 0 && (
            <p className="rounded-xl border border-dashed border-[#DEDEE1] px-4 py-8 text-center text-sm text-socle-muted">
              Aucun espace disponible.{' '}
              <Link to="/spaces" className="font-semibold text-socle-accent hover:underline">
                Créer un espace
              </Link>
            </p>
          )}
          <div role="radiogroup" aria-label="Espace" className="grid gap-2 sm:grid-cols-2">
            {spaceList.map((s) => (
              <ChoiceRow
                key={s.id}
                selected={s.id === activeSpaceId}
                onSelect={() => chooseSpace(s.id)}
                label={s.name}
                swatch={s.color}
              />
            ))}
          </div>

          {activeSpaceId && (
            <div className="mt-6">
              <div className={sectionLabel}>Emplacement dans l’arborescence</div>
              {tree.isLoading && <p className="text-sm text-socle-muted">Chargement…</p>}
              {tree.isError && (
                <p className="text-sm text-socle-danger" role="alert">
                  Arborescence indisponible — le document sera créé à la racine.
                </p>
              )}
              <div
                role="radiogroup"
                aria-label="Dossier"
                className="max-h-64 overflow-y-auto rounded-lg border border-socle-line p-1.5"
              >
                <ChoiceRow
                  selected={activeFolderId === null}
                  onSelect={() => setFolderId(null)}
                  label={`${space?.name ?? 'Espace'} (racine)`}
                  root
                />
                {folderOptions.map((f) => (
                  <ChoiceRow
                    key={f.id}
                    selected={activeFolderId === f.id}
                    onSelect={() => setFolderId(f.id)}
                    label={f.name}
                    depth={f.depth}
                  />
                ))}
              </div>
              <p className="mt-1.5 text-[11.5px] text-socle-faint">
                À la racine de l’espace, ou dans un dossier existant.
              </p>
            </div>
          )}

          <Footer top="mt-[32.5px]">
            <button type="button" className={`${btnGhost} bg-white`} onClick={() => navigate(closeHref)}>
              Annuler
            </button>
            <button
              type="button"
              className={btnCta}
              disabled={!activeSpaceId}
              onClick={() => setStep(2)}
            >
              Continuer
            </button>
          </Footer>
        </section>
      )}

      {step === 2 && (
        <section aria-labelledby="step2-title" data-testid="step-2">
          <h2 id="step2-title" className="sr-only">
            Étape 2 sur 3 : modèle
          </h2>
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
          <input
            type="search"
            className="field-input mb-4"
            placeholder="Rechercher un modèle…"
            aria-label="Rechercher un modèle"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />

          {templates.isLoading && <p className="text-sm text-socle-muted">Chargement…</p>}
          {templates.isError && (
            <p className="mb-3 text-sm text-socle-danger" role="alert">
              Modèles indisponibles — vous pouvez partir d’un document vierge.
            </p>
          )}

          <div role="radiogroup" aria-label="Modèle" className="grid gap-3 sm:grid-cols-2">
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
          {templates.data && visibleTemplates.length === 0 && (
            <p className="mt-3 text-sm text-socle-muted">
              {search.trim() ? 'Aucun modèle ne correspond à la recherche.' : 'Aucun modèle disponible.'}
            </p>
          )}

          <Footer>
            <button type="button" className={btnGhost} onClick={() => setStep(1)}>
              Retour
            </button>
            <button type="button" className={btnCta} onClick={() => setStep(3)}>
              Continuer
            </button>
          </Footer>
        </section>
      )}

      {step === 3 && (
        <form onSubmit={onSubmit} aria-labelledby="step3-title" data-testid="step-3">
          <h2 id="step3-title" className="sr-only">
            Étape 3 sur 3 : titre
          </h2>
          <dl className="mb-[24.5px] grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 rounded-xl bg-socle-soft px-4 py-3 text-sm">
            <dt className="text-socle-muted">Emplacement</dt>
            <dd className="font-medium text-socle-ink" data-testid="recap-location">
              {locationLabel}
            </dd>
            <dt className="text-socle-muted">Modèle</dt>
            <dd className="font-medium text-socle-ink" data-testid="recap-template">
              {selectedTemplate?.name ?? 'Document vierge'}
            </dd>
          </dl>

          <label className="block" data-mock-id="newdoc-title-field">
            <span className={`${sectionLabel} block`}>Titre du document</span>
            <input
              className="block w-full rounded-[9px] border border-socle-line bg-white px-[14px] pb-[10.203px] pt-[13px] text-[15px] leading-[20.8px] text-socle-ink outline-none placeholder:text-[#C2C2C6] focus:border-[#C7C6F5]"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Ex. Politique de classification des données"
              aria-label="Titre du document"
              autoFocus
              required
            />
          </label>

          {warningList.length > 0 && (
            <div
              className="mt-5 rounded-lg border border-[#E8D9A8] bg-[#FBF3E4] px-4 py-3 text-sm text-socle-warn"
              role="alert"
              data-testid="creation-warnings"
            >
              <p className="font-semibold">
                {warningList.length > 1 ? 'Points d’attention' : 'Point d’attention'} avant la
                création
              </p>
              <ul className="mt-1.5 list-disc space-y-1 pl-5 text-socle-slate">
                {warningList.map((w) => (
                  <li key={w}>{w}</li>
                ))}
              </ul>
            </div>
          )}

          {create.isError && (
            <p className="mt-4 text-sm text-socle-danger" role="alert">
              {apiErrorMessage(create.error, 'Création refusée — accès editor requis sur l’espace')}
            </p>
          )}

          <Footer note="Le document sera créé en brouillon." top="mt-[32.016px]">
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
              disabled={!title.trim() || create.isPending || warnings.isLoading}
            >
              {create.isPending
                ? 'Création…'
                : warningList.length > 0
                  ? 'Créer quand même'
                  : 'Créer le document'}
            </button>
          </Footer>
        </form>
      )}
    </main>
  )
}

function Footer({ children, note, top = 'mt-8' }: { children: ReactNode; note?: string; top?: string }) {
  return (
    <div className={`${top} flex items-center justify-between gap-4 pb-12`}>
      <span className="text-[12.5px] text-socle-muted">{note}</span>
      <div className="flex shrink-0 gap-2.5" data-mock-id="newdoc-actions">
        {children}
      </div>
    </div>
  )
}

function ChoiceRow({
  label,
  selected,
  onSelect,
  depth = 0,
  root,
  swatch,
}: {
  label: string
  selected: boolean
  onSelect: () => void
  depth?: number
  root?: boolean
  swatch?: string | null
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={onSelect}
      style={{ paddingLeft: 10 + depth * 16 }}
      className={`flex w-full items-center gap-2 rounded-[7px] py-[8px] pr-2.5 text-left text-[13.5px] ${
        selected
          ? 'bg-socle-mist font-semibold text-socle-accent'
          : 'text-[#4B4B52] hover:bg-[#F5F5F7]'
      }`}
    >
      <span
        aria-hidden
        style={swatch ? { backgroundColor: swatch } : undefined}
        className={`h-[9px] w-[9px] shrink-0 rounded-[3px] ${
          swatch || root ? 'bg-socle-accent' : 'border border-socle-faint'
        }`}
      />
      <span className="truncate">{label}</span>
    </button>
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
