// SPDX-License-Identifier: AGPL-3.0-or-later
import { FormEvent, useMemo, useState, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import { createDocument, emptyDocBody } from '../lib/documents'
import {
  documentHref,
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
  scopeLabel,
  templatesKey,
} from '../lib/templates'

type Step = 1 | 2 | 3

const STEPS: Array<{ n: Step; label: string }> = [
  { n: 1, label: 'Emplacement' },
  { n: 2, label: 'Modèle' },
  { n: 3, label: 'Titre' },
]

const sectionLabel = 'mb-2 text-[12px] font-semibold uppercase tracking-[0.05em] text-socle-muted'

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
      navigate(documentHref(doc.id))
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
    <main className="mx-auto max-w-[760px] px-6 py-10 md:px-10">
      <div className="mb-6 flex items-start justify-between gap-4">
        <div>
          <h1 className="serif-title">Créer un document</h1>
          <p className="mt-1.5 text-sm text-socle-muted">
            Choisissez où le ranger, puis un modèle pour démarrer avec une structure adaptée.
          </p>
        </div>
        <Link
          to={closeHref}
          aria-label="Fermer"
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

          <Footer>
            <Link to={closeHref} className="btn-ghost">
              Annuler
            </Link>
            <button
              type="button"
              className="btn-primary"
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
          <div className="mb-3 flex items-center justify-between gap-3">
            <div className="text-[12px] font-semibold uppercase tracking-[0.05em] text-socle-muted">
              Modèle
            </div>
            <Link
              to="/admin/templates"
              className="text-xs font-semibold text-socle-accent hover:underline"
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
              Modèles indisponibles — vous pouvez partir d’une page vierge.
            </p>
          )}

          <div role="radiogroup" aria-label="Modèle" className="grid gap-3 sm:grid-cols-2">
            <TemplateCard
              blank
              selected={templateId === null}
              onSelect={() => setTemplateId(null)}
              name="Page vierge"
              description="Partir d’une page blanche, sans structure imposée."
            />
            {visibleTemplates.map((t) => (
              <TemplateCard
                key={t.id}
                selected={templateId === t.id}
                onSelect={() => setTemplateId(t.id)}
                name={t.name}
                description={t.description ?? ''}
                badge={scopeLabel(t.scope)}
                docType={t.docType}
              />
            ))}
          </div>
          {templates.data && visibleTemplates.length === 0 && (
            <p className="mt-3 text-sm text-socle-muted">
              {search.trim() ? 'Aucun modèle ne correspond à la recherche.' : 'Aucun modèle disponible.'}
            </p>
          )}

          <Footer>
            <button type="button" className="btn-ghost" onClick={() => setStep(1)}>
              Retour
            </button>
            <button type="button" className="btn-primary" onClick={() => setStep(3)}>
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
          <dl className="mb-6 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 rounded-xl bg-socle-soft px-4 py-3 text-sm">
            <dt className="text-socle-muted">Emplacement</dt>
            <dd className="font-medium text-socle-ink" data-testid="recap-location">
              {locationLabel}
            </dd>
            <dt className="text-socle-muted">Modèle</dt>
            <dd className="font-medium text-socle-ink" data-testid="recap-template">
              {selectedTemplate?.name ?? 'Page vierge'}
            </dd>
          </dl>

          <label className="block">
            <span className={`${sectionLabel} block`}>Titre du document</span>
            <input
              className="field-input px-3.5 py-3 text-[15px]"
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

          <Footer note="Le document sera créé en brouillon.">
            <button type="button" className="btn-ghost" onClick={() => setStep(2)}>
              Retour
            </button>
            <button
              type="submit"
              className="btn-primary"
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

function Footer({ children, note }: { children: ReactNode; note?: string }) {
  return (
    <div className="mt-8 flex items-center justify-between gap-4 border-t border-socle-line pt-5">
      <span className="text-[12.5px] text-socle-muted">{note}</span>
      <div className="flex shrink-0 gap-2.5">{children}</div>
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

function TemplateCard({
  name,
  description,
  selected,
  onSelect,
  blank,
  badge,
  docType,
}: {
  name: string
  description: string
  selected: boolean
  onSelect: () => void
  blank?: boolean
  badge?: string
  docType?: string | null
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={onSelect}
      className={`rounded-xl border-[1.5px] px-[18px] py-4 text-left transition ${
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
            ? 'border-[1.5px] border-dashed border-[#DEDEE1] text-socle-muted'
            : selected
              ? 'bg-socle-accent text-white'
              : 'bg-[#F0EFEA] text-[#6B6862]'
        }`}
      >
        <svg
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          {blank ? (
            <>
              <line x1="12" y1="5" x2="12" y2="19" />
              <line x1="5" y1="12" x2="19" y2="12" />
            </>
          ) : (
            <>
              <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
              <polyline points="14 3 14 8 19 8" />
            </>
          )}
        </svg>
      </div>
      <div className="mb-1 flex flex-wrap items-center gap-1.5">
        <span className="text-sm font-semibold text-socle-ink">{name}</span>
        {badge && (
          <span className="rounded bg-socle-mist px-[5px] py-px text-[9.5px] font-semibold text-socle-accent">
            {badge}
          </span>
        )}
        {docType && <span className="text-[10.5px] text-socle-muted">{docType}</span>}
      </div>
      {description && <p className="text-[12.5px] leading-normal text-socle-slate">{description}</p>}
    </button>
  )
}
