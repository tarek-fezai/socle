// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import { createSpace, listSpaces, type Space } from '../lib/spaces'

function roleLabel(s: Space): string {
  if (s.isResponsible) return 'Responsible'
  if (s.isOwner) return 'Owner'
  if (s.membership === 'public-only') return 'Lecture publique'
  return 'Membre'
}

/**
 * Espaces — grille 3 colonnes de Spaces.dc.html.
 * Description, avatars des membres et nombre de documents n'existent pas dans `SpaceView` :
 * zones réservées (`data-visual-mask`, NOT_IMPLEMENTED côté spec), jamais de valeur inventée.
 */
export function SpacesPage() {
  const qc = useQueryClient()
  const spaces = useQuery({ queryKey: ['spaces'], queryFn: () => listSpaces(api) })
  const [name, setName] = useState('')
  const [color, setColor] = useState('#3730E0')
  const [error, setError] = useState<string | null>(null)
  const [formOpen, setFormOpen] = useState(false)

  const create = useMutation({
    mutationFn: () => createSpace(api, { name: name.trim(), color }),
    onSuccess: () => {
      setName('')
      setError(null)
      setFormOpen(false)
      void qc.invalidateQueries({ queryKey: ['spaces'] })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Création impossible')),
  })

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) return
    create.mutate()
  }

  const count = spaces.data?.length ?? 0

  return (
    <main className="px-6 pt-8 leading-[normal] md:px-12 md:pt-11">
      <h1
        className="mb-1.5 font-display text-[40px] font-normal leading-[normal] text-socle-ink"
        data-mock-id="spaces-title"
      >
        Espaces
      </h1>
      <p
        className="mb-8 text-[14.5px] text-socle-muted"
        data-mock-id="spaces-stats"
        data-visual-mask="spaces-stats"
      >
        {spaces.data ? `${count} espace${count > 1 ? 's' : ''}` : '\u00A0'}
      </p>

      {formOpen && (
        <form
          onSubmit={onSubmit}
          className="mb-8 flex max-w-[780px] flex-wrap items-end gap-3 rounded-xl border border-socle-line bg-[#FAFAFB] p-4"
          aria-label="Créer un espace"
        >
          <label className="min-w-[200px] flex-1 text-sm">
            Nom
            <input
              className="mt-1 w-full rounded-lg border border-socle-line bg-white px-3 py-2"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Identité &amp; accès"
              required
              autoFocus
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
          <button type="button" className="btn-ghost" onClick={() => setFormOpen(false)}>
            Annuler
          </button>
        </form>
      )}
      {error && <p className="mb-4 text-sm text-socle-danger">{error}</p>}

      {spaces.isLoading && <p className="text-sm text-socle-muted">Chargement…</p>}
      {spaces.isError && (
        <p className="text-sm text-socle-danger">
          {apiErrorMessage(spaces.error, 'Impossible de charger les espaces')}
        </p>
      )}

      <div
        className="grid grid-cols-1 gap-[18px] pb-10 md:grid-cols-2 lg:grid-cols-3 spaces-grid-3"
        data-mock-id="spaces-grid"
      >
        {spaces.data?.map((s, i) => (
          <div
            key={s.id}
            data-mock-id={`spaces-card-${i}`}
            className="relative block rounded-[14px] border border-socle-line p-[22px] hover:border-[#C7C6F5]"
          >
            <div
              aria-hidden
              data-mock-id={`spaces-card-${i}-tile`}
              className="mb-4 h-[38px] w-[38px] rounded-[10px]"
              style={{ background: s.color ?? '#3730E0' }}
            />
            <div
              className="mb-1 text-[16px] font-bold text-socle-ink"
              data-mock-id={`spaces-card-${i}-name`}
            >
              <Link
                to={`/spaces/${s.id}/tree`}
                className="after:absolute after:inset-0 after:rounded-[14px]"
              >
                {s.name}
              </Link>
            </div>
            {/* Description absente de SpaceView → zone réservée (NOT_IMPLEMENTED spaces-card-desc). */}
            <div
              aria-hidden
              className="mb-[18px] text-[12.5px] text-socle-muted"
              data-visual-mask="spaces-card-desc"
            >
              {'\u00A0'}
            </div>
            {/* Avatars + nombre de documents absents de SpaceView → rôle réel + lien réglages. */}
            <div
              className="relative z-10 flex h-[26px] items-center justify-between"
              data-visual-mask="spaces-card-meta"
            >
              <span className="text-xs text-socle-muted">{roleLabel(s)}</span>
              <Link
                to={`/spaces/${s.id}`}
                className="text-xs font-semibold text-socle-accent hover:underline"
                aria-label={`Paramètres de ${s.name}`}
              >
                Paramètres
              </Link>
            </div>
          </div>
        ))}

        <button
          type="button"
          onClick={() => setFormOpen((v) => !v)}
          aria-expanded={formOpen}
          data-mock-id="spaces-new-card"
          className="flex flex-col items-center justify-center gap-2.5 rounded-[14px] leading-[normal] border border-dashed border-[#DEDEE1] bg-transparent p-[22px] text-center hover:border-[#9B9BA1] hover:bg-socle-soft"
        >
          <svg
            width="22"
            height="22"
            viewBox="0 0 24 24"
            fill="none"
            stroke="#9B9BA1"
            strokeWidth="2"
            strokeLinecap="round"
            aria-hidden
            data-mock-id="spaces-new-icon"
          >
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          <div className="text-[13.5px] font-semibold text-[#6B6B72]" data-mock-id="spaces-new-title">
            Nouvel espace
          </div>
          <div className="text-[12px] text-[#B0B0B5]" data-mock-id="spaces-new-sub">
            Regrouper des documents par domaine ou équipe
          </div>
        </button>
      </div>
      {spaces.data?.length === 0 && (
        <p className="mt-2 text-sm text-socle-muted">Aucun espace visible — créez-en un.</p>
      )}
    </main>
  )
}
