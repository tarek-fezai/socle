// SPDX-License-Identifier: AGPL-3.0-or-later
import { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import { createSpace, listSpaces } from '../lib/spaces'

export function SpacesPage() {
  const qc = useQueryClient()
  const spaces = useQuery({ queryKey: ['spaces'], queryFn: () => listSpaces(api) })
  const [name, setName] = useState('')
  const [color, setColor] = useState('#3730E0')
  const [error, setError] = useState<string | null>(null)

  const create = useMutation({
    mutationFn: () => createSpace(api, { name: name.trim(), color }),
    onSuccess: () => {
      setName('')
      setError(null)
      void qc.invalidateQueries({ queryKey: ['spaces'] })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Création impossible')),
  })

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) return
    create.mutate()
  }

  return (
    <main className="page-shell max-w-[780px]">
      <div className="breadcrumb mb-6">
        <Link to="/">Accueil</Link>
        <span className="text-[#DEDEE1]">→</span>
        <span className="font-medium text-socle-ink">Espaces</span>
      </div>

      <h1 className="serif-title">Espaces</h1>
      <p className="mt-1.5 text-sm text-socle-muted">
        Créez des workspaces distincts ; le créateur devient responsible (et owner).
      </p>

      <form onSubmit={onSubmit} className="mt-8 flex flex-wrap items-end gap-3 rounded-xl border border-socle-line bg-[#FAFAFB] p-4">
        <label className="min-w-[200px] flex-1 text-sm">
          Nom
          <input
            className="mt-1 w-full rounded-lg border border-socle-line bg-white px-3 py-2"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Identité &amp; accès"
            required
          />
        </label>
        <label className="text-sm">
          Couleur
          <input
            type="color"
            className="mt-1 block h-10 w-14 cursor-pointer rounded border border-socle-line"
            value={color}
            onChange={(e) => setColor(e.target.value)}
          />
        </label>
        <button type="submit" className="btn-primary" disabled={create.isPending || !name.trim()}>
          {create.isPending ? 'Création…' : 'Créer un espace'}
        </button>
      </form>
      {error && <p className="mt-2 text-sm text-socle-danger">{error}</p>}

      {spaces.isLoading && <p className="mt-8 text-sm text-socle-muted">Chargement…</p>}
      {spaces.isError && (
        <p className="mt-8 text-sm text-socle-danger">
          {apiErrorMessage(spaces.error, 'Impossible de charger les espaces')}
        </p>
      )}

      <ul className="mt-8 space-y-2">
        {spaces.data?.map((s) => (
          <li key={s.id} className="flex items-center gap-2">
            <Link
              to={`/spaces/${s.id}/tree`}
              className="flex min-w-0 flex-1 items-center justify-between rounded-xl border border-socle-line bg-white px-4 py-3 hover:border-[#C7C6F5] hover:bg-socle-mist/40"
            >
              <div className="flex items-center gap-3">
                <span
                  className="h-3 w-3 rounded-full"
                  style={{ background: s.color ?? '#3730E0' }}
                  aria-hidden
                />
                <div>
                  <div className="font-semibold text-socle-ink">{s.name}</div>
                  <div className="font-mono text-[11px] text-socle-muted">{s.id}</div>
                </div>
              </div>
              <span className="text-xs text-socle-slate">
                {s.isResponsible ? 'Responsible' : s.isOwner ? 'Owner' : 'Membre'}
              </span>
            </Link>
            <Link
              to={`/spaces/${s.id}`}
              className="shrink-0 text-xs font-semibold text-socle-accent hover:underline"
              aria-label={`Paramètres de ${s.name}`}
            >
              Paramètres
            </Link>
          </li>
        ))}
      </ul>
      {spaces.data?.length === 0 && (
        <p className="mt-6 text-sm text-socle-muted">Aucun espace visible — créez-en un.</p>
      )}
    </main>
  )
}
