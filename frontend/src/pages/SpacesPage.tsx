// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import { SPACE_SWATCHES } from '../lib/spaceColors'
import { createSpace, listSpaces, updateSpace } from '../lib/spaces'

function mixWithWhite(hex: string, whiteRatio: number): string {
  const h = hex.replace('#', '')
  if (h.length !== 6) return '#F1EFEA'
  const ch = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16))
  return `#${ch
    .map((c) => Math.round(c * (1 - whiteRatio) + 255 * whiteRatio).toString(16).padStart(2, '0'))
    .join('')}`
}

function memberChipStyle(
  spaceColor: string | null,
  index: number,
  namedCount: number,
): { background: string; color: string } {
  const color = spaceColor || '#3730E0'
  if (namedCount >= 2 && index === 0) {
    return { background: '#0E0E10', color: '#FFFFFF' }
  }
  return { background: mixWithWhite(color, 0.88), color }
}

/**
 * Espaces — grille 3 colonnes de Spaces.dc.html.
 * Description, aperçu membres et documentCount viennent de SpaceView (filtrés OpenFGA).
 */
export function SpacesPage() {
  const qc = useQueryClient()
  const spaces = useQuery({ queryKey: ['spaces'], queryFn: () => listSpaces(api) })
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [color, setColor] = useState<(typeof SPACE_SWATCHES)[number]>('#3730E0')
  const [visibility, setVisibility] = useState<'organisation' | 'space'>('organisation')
  const [error, setError] = useState<string | null>(null)
  const [formOpen, setFormOpen] = useState(false)

  const create = useMutation({
    mutationFn: async () => {
      const space = await createSpace(api, {
        name: name.trim(),
        color,
        description: description.trim() || null,
      })
      if (visibility !== 'organisation') {
        await updateSpace(api, space.id, {
          name: space.name,
          color: space.color,
          defaultVisibility: visibility,
        })
      }
      return space
    },
    onSuccess: () => {
      setName('')
      setDescription('')
      setColor('#3730E0')
      setVisibility('organisation')
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
  const docs = (spaces.data ?? []).reduce((n, s) => n + (s.documentCount ?? 0), 0)
  const stats = spaces.data
    ? `${count} espace${count > 1 ? 's' : ''} · ${docs} document${docs > 1 ? 's' : ''} au total`
    : '\u00A0'

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
      >
        {stats}
      </p>

      {formOpen &&
        createPortal(
          <div
            style={{
              position: 'absolute',
              left: 0,
              top: 0,
              width: 1440,
              height: 900,
              zIndex: 50,
            }}
          >
            <div
              style={{
                position: 'relative',
                width: 1440,
                height: 900,
                overflow: 'hidden',
              }}
            >
          <button
            type="button"
            className="absolute inset-0 bg-[rgba(14,14,16,0.45)]"
            aria-label="Fermer"
            onClick={() => setFormOpen(false)}
          />
          <div
            data-mock-id="create-modal"
            className="absolute top-[120px] w-[480px] rounded-2xl bg-white px-[30px] pb-[26px] pt-[30px] leading-[normal] shadow-[0_24px_60px_rgba(14,14,16,0.28)]"
            style={{
              left: '50%',
              transform: 'translateX(-50%)',
            }}
          >
          <form
            onSubmit={onSubmit}
            aria-label="Créer un espace"
          >
            <div className="mb-[22px] flex items-start justify-between">
              <h1
                className="m-0 font-display text-[24px] font-normal leading-[normal] text-socle-ink"
                data-mock-id="create-title"
              >
                Créer un espace
              </h1>
              <button
                type="button"
                aria-label="Fermer"
                data-mock-id="create-close"
                onClick={() => setFormOpen(false)}
                className="flex h-7 w-7 items-center justify-center rounded-[7px] hover:bg-[#F5F5F7]"
              >
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#6B6B72" strokeWidth="2">
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
              </button>
            </div>
            <div className="mb-4">
              <label
                className="mb-2 block text-[12px] font-semibold uppercase leading-[normal] tracking-[0.05em] text-[#9B9BA1]"
                data-mock-id="create-name-label"
                htmlFor="create-space-name"
              >
                Nom de l&apos;espace
              </label>
              <div className="rounded-[9px] border border-socle-line px-[13px] py-[11px]">
                <input
                  id="create-space-name"
                  className="w-full border-0 bg-transparent p-0 text-[14.5px] text-socle-ink outline-none placeholder:text-[#C2C2C6]"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Ex. Sécurité & SOC"
                  required
                  autoFocus
                />
              </div>
            </div>
            <div className="mb-[18px]">
              <label
                className="mb-2 block text-[12px] font-semibold uppercase leading-[normal] tracking-[0.05em] text-[#9B9BA1]"
                data-mock-id="create-desc-label"
                htmlFor="create-space-desc"
              >
                Description
              </label>
              <div className="rounded-[9px] border border-socle-line px-[13px] py-[11px]">
                <input
                  id="create-space-desc"
                  className="w-full border-0 bg-transparent p-0 text-[13.5px] text-socle-ink outline-none placeholder:text-[#C2C2C6]"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="De quoi cet espace regroupe-t-il la documentation ?"
                />
              </div>
            </div>
            <div
              className="mb-2.5 text-[12px] font-semibold uppercase leading-[normal] tracking-[0.05em] text-[#9B9BA1]"
              data-mock-id="create-color-label"
            >
              Couleur
            </div>
            <div className="mb-5 flex gap-3" data-mock-id="create-swatches">
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
            <div
              className="mb-2.5 text-[12px] font-semibold uppercase leading-[normal] tracking-[0.05em] text-[#9B9BA1]"
              data-mock-id="create-vis-label"
            >
              Visibilité par défaut
            </div>
            <div className="mb-[26px] flex flex-col gap-2" data-mock-id="create-vis-field">
              {(
                [
                  ['organisation', 'Organisation entière'],
                  ['space', 'Membres invités uniquement'],
                ] as const
              ).map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  onClick={() => setVisibility(value)}
                  className={`flex items-center gap-2.5 rounded-[9px] px-[13px] py-2.5 text-left text-[13.5px] font-medium text-socle-ink ${
                    visibility === value
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
                      borderWidth: visibility === value ? 4.5 : 1.5,
                      borderStyle: 'solid',
                      borderColor: visibility === value ? '#3730E0' : '#C7C6C6',
                    }}
                  />
                  {label}
                </button>
              ))}
            </div>
            <div className="flex justify-end gap-2.5">
              <button
                type="button"
                data-mock-id="create-cancel"
                onClick={() => setFormOpen(false)}
                className="hover:bg-[#F5F5F7]"
                style={{
                  appearance: 'none',
                  boxSizing: 'content-box',
                  padding: '9px 16px',
                  margin: 0,
                  borderRadius: 7,
                  border: '1px solid #ECECEE',
                  background: 'none',
                  color: '#43434A',
                  font: 'inherit',
                  fontSize: 13,
                  fontWeight: 500,
                  lineHeight: 'normal',
                  letterSpacing: 'inherit',
                  cursor: 'pointer',
                }}
              >
                Annuler
              </button>
              <button
                type="submit"
                data-mock-id="create-submit"
                disabled={create.isPending}
                className="hover:bg-[#2C27C7] disabled:opacity-50"
                style={{
                  appearance: 'none',
                  display: 'flex',
                  alignItems: 'flex-start',
                  justifyContent: 'center',
                  boxSizing: 'content-box',
                  padding: '9px 20px',
                  margin: 0,
                  borderRadius: 7,
                  border: 0,
                  background: '#3730E0',
                  color: '#FFFFFF',
                  font: 'inherit',
                  fontSize: 13,
                  fontWeight: 600,
                  lineHeight: 'normal',
                  letterSpacing: 'inherit',
                  textAlign: 'center',
                  cursor: 'pointer',
                }}
              >
                {create.isPending ? 'Création…' : "Créer l'espace"}
              </button>
            </div>
          </form>
          </div>
            </div>
          </div>,
          document.body,
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
            <div
              className="mb-[18px] min-h-[17px] text-[12.5px] text-socle-muted"
              data-mock-id={`spaces-card-${i}-desc`}
            >
              {s.description?.trim() || '\u00A0'}
            </div>
            <div
              className="relative z-10 flex h-[25px] items-center justify-between"
              data-mock-id={`spaces-card-${i}-meta`}
            >
              <div className="flex" aria-hidden>
                {(s.memberPreview ?? []).map((m, mi) => (
                  <div
                    key={`${s.id}-m-${mi}`}
                    className="flex h-[22px] w-[22px] items-center justify-center rounded-full border-2 border-white text-[9px] font-semibold"
                    style={{
                      marginLeft: mi === 0 ? 0 : -6,
                      ...memberChipStyle(s.color, mi, s.memberPreview?.length ?? 0),
                    }}
                  >
                    {m.initials}
                  </div>
                ))}
                {(s.memberExtraCount ?? 0) > 0 && (
                  <div
                    className="ml-[-6px] flex h-[22px] w-[22px] items-center justify-center rounded-full border-2 border-white bg-[#F1EFEA] text-[9px] font-semibold text-[#6B6862]"
                  >
                    +{s.memberExtraCount}
                  </div>
                )}
              </div>
              <span className="text-xs text-socle-muted">
                {s.documentCount ?? 0} document{(s.documentCount ?? 0) > 1 ? 's' : ''}
              </span>
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
          <div className="text-[12px] text-[#75757C]" data-mock-id="spaces-new-sub">
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
