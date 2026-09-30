import { FormEvent, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import {
  addSpaceOwner,
  getSpace,
  listSpaceOwners,
  removeSpaceOwner,
  setSpaceResponsible,
  updateSpace,
} from '../lib/spaces'

export function SpaceSettingsPage() {
  const { spaceId = '' } = useParams()
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [externalReference, setExternalReference] = useState<'open' | 'restricted'>('open')
  const [defaultVisibility, setDefaultVisibility] = useState<
    'organisation' | 'space' | 'restricted'
  >('organisation')
  const [ownerId, setOwnerId] = useState('')
  const [error, setError] = useState<string | null>(null)

  const space = useQuery({
    queryKey: ['space', spaceId],
    queryFn: async () => {
      const s = await getSpace(api, spaceId)
      setName(s.name)
      setExternalReference(s.externalReference === 'restricted' ? 'restricted' : 'open')
      const dv = s.defaultVisibility
      setDefaultVisibility(
        dv === 'space' || dv === 'restricted' ? dv : 'organisation',
      )
      return s
    },
    enabled: Boolean(spaceId),
  })

  const owners = useQuery({
    queryKey: ['space-owners', spaceId],
    queryFn: () => listSpaceOwners(api, spaceId),
    enabled: Boolean(spaceId) && Boolean(space.data?.canManage),
  })

  const save = useMutation({
    mutationFn: () =>
      updateSpace(api, spaceId, {
        name: name.trim(),
        externalReference: space.data?.canManage ? externalReference : undefined,
        defaultVisibility: space.data?.canManage ? defaultVisibility : undefined,
      }),
    onSuccess: () => {
      setError(null)
      void qc.invalidateQueries({ queryKey: ['space', spaceId] })
      void qc.invalidateQueries({ queryKey: ['spaces'] })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Mise à jour impossible')),
  })

  const addOwner = useMutation({
    mutationFn: () => addSpaceOwner(api, spaceId, { userId: ownerId.trim(), responsible: false }),
    onSuccess: () => {
      setOwnerId('')
      setError(null)
      void qc.invalidateQueries({ queryKey: ['space-owners', spaceId] })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Ajout owner impossible')),
  })

  const remove = useMutation({
    mutationFn: (userId: string) => removeSpaceOwner(api, spaceId, userId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['space-owners', spaceId] }),
    onError: (e) => setError(apiErrorMessage(e, 'Retrait impossible')),
  })

  const toggleResponsible = useMutation({
    mutationFn: ({ userId, responsible }: { userId: string; responsible: boolean }) =>
      setSpaceResponsible(api, spaceId, userId, responsible),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['space-owners', spaceId] }),
    onError: (e) => setError(apiErrorMessage(e, 'Changement responsible impossible')),
  })

  if (space.isLoading) {
    return <main className="page-shell text-socle-muted">Chargement…</main>
  }
  if (space.isError || !space.data) {
    return (
      <main className="page-shell">
        <Link to="/spaces" className="text-sm text-socle-accent">
          ← Espaces
        </Link>
        <p className="mt-4 text-socle-danger">Espace introuvable ou accès refusé.</p>
      </main>
    )
  }

  return (
    <main className="page-shell max-w-[780px]">
      <div className="breadcrumb mb-6">
        <Link to="/">Accueil</Link>
        <span className="text-[#DEDEE1]">→</span>
        <Link to="/spaces">Espaces</Link>
        <span className="text-[#DEDEE1]">→</span>
        <span className="font-medium text-socle-ink">{space.data.name}</span>
      </div>

      <h1 className="serif-title">Paramètres de l&apos;espace</h1>
      <p className="mt-1.5 font-mono text-xs text-socle-muted">{spaceId}</p>

      <div className="mt-4 flex flex-wrap gap-3 text-sm">
        <Link to={`/spaces/${spaceId}/graph`} className="font-semibold text-socle-accent hover:underline">
          Vue graphe →
        </Link>
        <Link
          to={`/spaces/${spaceId}/content-health`}
          className="font-semibold text-socle-accent hover:underline"
        >
          Santé du contenu →
        </Link>
        <Link to={`/spaces/${spaceId}/access`} className="font-semibold text-socle-accent hover:underline">
          Gérer les accès →
        </Link>
      </div>

      {space.data.canManage && (
        <form
          className="mt-8 space-y-3 rounded-xl border border-socle-line p-4"
          onSubmit={(e: FormEvent) => {
            e.preventDefault()
            save.mutate()
          }}
        >
          <h2 className="text-sm font-semibold">Métadonnées</h2>
          <input
            className="w-full rounded-lg border border-socle-line px-3 py-2"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <label className="block text-sm">
            <span className="text-socle-muted">Références externes (transclusion entrante)</span>
            <select
              className="mt-1 w-full rounded-lg border border-socle-line bg-white px-3 py-2"
              value={externalReference}
              onChange={(e) => setExternalReference(e.target.value as 'open' | 'restricted')}
              data-testid="external-reference-select"
            >
              <option value="open">Ouvert — les autres espaces peuvent transclure</option>
              <option value="restricted">Restreint — casse les transclusions inter déjà en place</option>
            </select>
          </label>
          <label className="block text-sm">
            <span className="text-socle-muted">Visibilité par défaut des nouveaux documents</span>
            <select
              className="mt-1 w-full rounded-lg border border-socle-line bg-white px-3 py-2"
              value={defaultVisibility}
              onChange={(e) =>
                setDefaultVisibility(
                  e.target.value as 'organisation' | 'space' | 'restricted',
                )
              }
              data-testid="default-visibility-select"
            >
              <option value="organisation">Tous les utilisateurs</option>
              <option value="space">Membres de l&apos;espace</option>
              <option value="restricted">Personnes et équipes listées</option>
            </select>
          </label>
          {externalReference === 'restricted' ? (
            <p
              className="rounded-lg border border-[#F3C9BB] bg-[#FBE2DB]/60 px-3 py-2 text-[13px] text-socle-danger"
              role="alert"
              data-testid="external-reference-warning"
            >
              Restreindre applique une rupture <strong>rétroactive</strong> : les pages d&apos;autres
              espaces qui transcluent déjà un document de cet espace afficheront « Contenu non
              accessible ». L&apos;accès direct OpenFGA aux documents n&apos;est pas modifié.
            </p>
          ) : null}
          <button type="submit" className="btn-primary" disabled={save.isPending}>
            Enregistrer
          </button>
        </form>
      )}

      {space.data.canManage && (
        <section className="mt-8">
          <h2 className="text-base font-semibold">Owners &amp; responsible</h2>
          <p className="mt-1 text-xs text-socle-muted">
            Seul un responsible ajoute/retire des owners. Les owners gèrent les accès des pages.
          </p>

          {space.data.isResponsible && (
            <form
              className="mt-4 flex flex-wrap gap-2"
              onSubmit={(e: FormEvent) => {
                e.preventDefault()
                if (ownerId.trim()) addOwner.mutate()
              }}
            >
              <input
                className="min-w-[280px] flex-1 rounded-lg border border-socle-line px-3 py-2 font-mono text-xs"
                placeholder="UUID utilisateur"
                value={ownerId}
                onChange={(e) => setOwnerId(e.target.value)}
              />
              <button type="submit" className="btn-ghost" disabled={addOwner.isPending}>
                Ajouter owner
              </button>
            </form>
          )}

          <ul className="mt-4 space-y-2">
            {owners.data?.owners.map((o) => (
              <li
                key={o.userId}
                className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-socle-line px-4 py-3"
              >
                <div>
                  <div className="text-sm font-semibold">{o.displayName}</div>
                  <div className="text-xs text-socle-muted">
                    {o.email} · {o.responsible ? 'responsible' : 'owner'}
                  </div>
                </div>
                {space.data.isResponsible && (
                  <div className="flex gap-2 text-xs">
                    <button
                      type="button"
                      className="font-semibold text-socle-accent hover:underline"
                      onClick={() =>
                        toggleResponsible.mutate({ userId: o.userId, responsible: !o.responsible })
                      }
                    >
                      {o.responsible ? 'Retirer responsible' : 'Nommer responsible'}
                    </button>
                    <button
                      type="button"
                      className="font-semibold text-socle-danger hover:underline"
                      onClick={() => remove.mutate(o.userId)}
                    >
                      Retirer
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}

      {error && <p className="mt-4 text-sm text-socle-danger">{error}</p>}
    </main>
  )
}
