// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import { SPACE_SWATCHES } from '../lib/spaceColors'
import { listTrash } from '../lib/trash'
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
  const [color, setColor] = useState<(typeof SPACE_SWATCHES)[number] | string>('#3730E0')
  const [description, setDescription] = useState('')
  const [externalReference, setExternalReference] = useState<'open' | 'restricted'>('open')
  const [defaultVisibility, setDefaultVisibility] = useState<
    'organisation' | 'space' | 'restricted'
  >('organisation')
  const [commentPolicy, setCommentPolicy] = useState<'members' | 'all_readers'>('members')
  const [ownerId, setOwnerId] = useState('')
  const [error, setError] = useState<string | null>(null)

  const space = useQuery({
    queryKey: ['space', spaceId],
    queryFn: async () => {
      const s = await getSpace(api, spaceId)
      setName(s.name)
      setColor(s.color || '#3730E0')
      setDescription(s.description ?? '')
      setExternalReference(s.externalReference === 'restricted' ? 'restricted' : 'open')
      const dv = s.defaultVisibility
      setDefaultVisibility(
        dv === 'space' || dv === 'restricted' ? dv : 'organisation',
      )
      setCommentPolicy(s.commentPolicy === 'all_readers' ? 'all_readers' : 'members')
      return s
    },
    enabled: Boolean(spaceId),
  })

  const owners = useQuery({
    queryKey: ['space-owners', spaceId],
    queryFn: () => listSpaceOwners(api, spaceId),
    enabled: Boolean(spaceId) && Boolean(space.data?.canManage),
  })

  const trash = useQuery({
    queryKey: ['trash', 'settings-count'],
    queryFn: () => listTrash(api, { limit: 50, offset: 0 }),
    enabled: Boolean(space.data?.canManage),
  })

  const save = useMutation({
    mutationFn: () =>
      updateSpace(api, spaceId, {
        name: name.trim(),
        color: space.data?.canManage ? color : undefined,
        description: space.data?.canManage ? description : undefined,
        externalReference: space.data?.canManage ? externalReference : undefined,
        defaultVisibility: space.data?.canManage ? defaultVisibility : undefined,
        commentPolicy: space.data?.canManage ? commentPolicy : undefined,
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
    <main className="mx-auto max-w-[621px] px-0 pt-9 leading-[normal]" style={{ width: 621 }}>
      <div className="breadcrumb mb-6">
        <Link to="/">Accueil</Link>
        <span className="text-[#DEDEE1]">→</span>
        <Link to="/spaces">Espaces</Link>
        <span className="text-[#DEDEE1]">→</span>
        <span className="font-medium text-socle-ink">{space.data.name}</span>
      </div>

      <h1
        className="mb-7 font-display text-[32px] font-normal leading-[normal] text-socle-ink"
        data-mock-id="settings-title"
      >
        Paramètres de l&apos;espace
      </h1>

      {space.data.canManage && (
        <form
          className="mt-0 space-y-0"
          onSubmit={(e: FormEvent) => {
            e.preventDefault()
            save.mutate()
          }}
        >
          <div className="mb-[26px]">
            <label className="mb-1.5 block text-[12.5px] font-semibold leading-[normal] text-[#43434A]" htmlFor="settings-name">
              Nom de l&apos;espace
            </label>
            <div
              data-mock-id="settings-name"
              className="rounded-lg border border-socle-line px-3 py-[9px] text-[14px] leading-[normal] text-socle-ink"
            >
              <input
                id="settings-name"
                className="w-full border-0 bg-transparent p-0 text-[14px] leading-[normal] text-socle-ink outline-none"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </div>
          </div>
          <div className="mb-[26px]" data-mock-id="settings-color">
            <div className="mb-2.5 text-[12.5px] font-semibold leading-[normal] text-[#43434A]">Couleur</div>
            <div className="flex gap-2.5">
              {SPACE_SWATCHES.map((swatch) => (
                <button
                  key={swatch}
                  type="button"
                  aria-label={`Couleur ${swatch}`}
                  aria-pressed={color === swatch}
                  onClick={() => setColor(swatch)}
                  className="h-7 w-7 rounded-lg"
                  style={{
                    background: swatch,
                    boxShadow:
                      color === swatch ? '0 0 0 2px #FFFFFF, 0 0 0 4px #3730E0' : undefined,
                  }}
                />
              ))}
            </div>
          </div>
          <div className="mb-[22px]" data-mock-id="settings-visibility">
            <div className="mb-2 text-[12.5px] font-semibold leading-[normal] text-[#43434A]">
              Visibilité par défaut des nouveaux documents
            </div>
            <div className="flex flex-col gap-1.5" data-testid="default-visibility-select">
              {(
                [
                  ['organisation', 'Tous les utilisateurs'],
                  ['space', "Membres de l'espace"],
                  ['restricted', 'Personnes et équipes listées'],
                ] as const
              ).map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  onClick={() => setDefaultVisibility(value)}
                  className={`flex items-center gap-2.5 rounded-lg px-3 py-[9px] text-left text-[13.5px] font-medium leading-[normal] text-socle-ink ${
                    defaultVisibility === value
                      ? 'border-[1.5px] border-socle-accent bg-[#FAFAFE]'
                      : 'border border-socle-line'
                  }`}
                >
                  <span
                    className="shrink-0 rounded-full"
                    style={{
                      width: 14,
                      height: 14,
                      boxSizing: 'content-box',
                      borderWidth: defaultVisibility === value ? 4.5 : 1.5,
                      borderStyle: 'solid',
                      borderColor: defaultVisibility === value ? '#3730E0' : '#C7C6C6',
                    }}
                  />
                  {label}
                </button>
              ))}
            </div>
          </div>
        </form>
      )}

      <Link
        to="/audit"
        data-mock-id="settings-audit"
        className="mb-3 flex items-center justify-between rounded-[10px] border border-socle-line px-4 pt-[14px] pb-[15px]"
      >
        <div>
          <div className="mb-0.5 text-[13.5px] font-semibold leading-[normal] text-socle-ink">Journal d&apos;audit</div>
          <div className="text-[12px] leading-[normal] text-[#9B9BA1]">Toutes les actions effectuées sur cet espace</div>
        </div>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#9B9BA1" strokeWidth="2">
          <polyline points="9 18 15 12 9 6" />
        </svg>
      </Link>
      <Link
        to="/trash"
        data-mock-id="settings-trash"
        className="mb-5 flex items-center justify-between rounded-[10px] border border-socle-line px-4 pt-[14px] pb-[15px]"
      >
        <div>
          <div className="mb-0.5 text-[13.5px] font-semibold leading-[normal] text-socle-ink">Corbeille</div>
          <div className="text-[12px] leading-[normal] text-[#9B9BA1]">
            {trash.data
              ? `${trash.data.total} document${trash.data.total === 1 ? '' : 's'} supprimé${trash.data.total === 1 ? '' : 's'} · conservés 30 jours`
              : 'Documents supprimés · conservés 30 jours'}
          </div>
        </div>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#9B9BA1" strokeWidth="2">
          <polyline points="9 18 15 12 9 6" />
        </svg>
      </Link>

      {space.data.canManage && (
        <form
          className="mt-0 space-y-0"
          onSubmit={(e: FormEvent) => {
            e.preventDefault()
            save.mutate()
          }}
        >
          <label className="mb-[22px] block text-sm">
            <span className="text-socle-muted">Description</span>
            <textarea
              className="mt-1 w-full rounded-lg border border-socle-line bg-white px-3 py-2"
              rows={3}
              maxLength={500}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </label>
          <label className="mb-[22px] block text-sm">
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
            <span className="text-socle-muted">Qui peut commenter</span>
            <select
              className="mt-1 w-full rounded-lg border border-socle-line bg-white px-3 py-2"
              value={commentPolicy}
              onChange={(e) =>
                setCommentPolicy(e.target.value as 'members' | 'all_readers')
              }
              data-testid="comment-policy-select"
            >
              <option value="members">Membres de l&apos;espace uniquement</option>
              <option value="all_readers">Tous les lecteurs du document</option>
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

      <p className="mt-1.5 font-mono text-xs text-socle-muted">{spaceId}</p>
      <div className="mt-4 flex flex-wrap gap-3 text-sm">
        <Link to={`/spaces/${spaceId}/tree`} className="font-semibold text-socle-accent hover:underline">
          Parcourir l&apos;arborescence →
        </Link>
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
