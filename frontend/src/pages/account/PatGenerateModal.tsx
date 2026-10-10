// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useEffect, useRef, useState } from 'react'
import {
  createPersonalToken,
  PAT_DEFAULT_EXPIRY_DAYS,
  PAT_DEFAULT_NAME,
  PAT_EXPIRY_PRESETS,
  problemCode,
  type PersonalAccessTokenCreated,
  type PersonalAccessTokenScope,
} from '../../lib/personalTokens'

const SCOPES: { value: PersonalAccessTokenScope; title: string; desc: string }[] = [
  {
    value: 'read',
    title: 'Lecture seule',
    desc: 'Consultation des documents auxquels vous avez accès',
  },
  {
    value: 'read_write',
    title: 'Lecture + écriture',
    desc: 'Créer et modifier des documents en votre nom',
  },
]

function errorMessage(error: unknown): string {
  switch (problemCode(error)) {
    case 'pat_limit_reached':
      return 'Nombre maximal de jetons actifs atteint (10) : révoquez un jeton existant.'
    case 'pat_expiry_invalid':
      return 'Expiration obligatoire : entre 1 et 90 jours.'
    default:
      return 'Génération impossible pour le moment.'
  }
}

/** Génération d'un jeton d'accès personnel (GeneratePersonalToken.dc.html) + affichage unique. */
export function PatGenerateModal({
  onClose,
  onCreated,
}: {
  onClose: () => void
  onCreated: () => void
}) {
  const [name, setName] = useState(PAT_DEFAULT_NAME)
  const [scope, setScope] = useState<PersonalAccessTokenScope>('read')
  const [days, setDays] = useState<number>(PAT_DEFAULT_EXPIRY_DAYS)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [created, setCreated] = useState<PersonalAccessTokenCreated | null>(null)
  const [copied, setCopied] = useState(false)
  const dialogRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    dialogRef.current?.focus()
  }, [created])

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  async function submit() {
    const trimmed = name.trim()
    if (!trimmed) {
      setError('Nom du jeton obligatoire.')
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      const result = await createPersonalToken({ name: trimmed, scope, expiresInDays: days })
      setCreated(result)
      onCreated()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setSubmitting(false)
    }
  }

  async function copy() {
    if (!created?.plaintext) return
    try {
      await navigator.clipboard.writeText(created.plaintext)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div className="pat-modal-backdrop" data-testid="pat-modal-backdrop">
      <div
        ref={dialogRef}
        className="pat-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="pat-modal-title"
        tabIndex={-1}
        data-mock-id="pat-generate-modal"
        data-testid="pat-generate-modal"
      >
        {created ? (
          <>
            <h2 id="pat-modal-title" className="pat-modal__title" data-mock-id="pat-generate-title">
              Jeton généré
            </h2>
            <p className="pat-modal__desc">
              « {created.token?.name} » est actif jusqu&apos;au{' '}
              {created.token?.expiresAt
                ? new Date(created.token.expiresAt).toLocaleDateString('fr-FR', {
                    day: 'numeric',
                    month: 'short',
                    year: 'numeric',
                  })
                : '—'}
              .
            </p>
            <div className="pat-modal__secret" data-testid="pat-plaintext">
              {created.plaintext}
            </div>
            <p className="pat-modal__warning" role="alert">
              Copiez ce jeton maintenant : il ne sera plus affiché.
            </p>
            <div className="pat-modal__actions">
              <button type="button" className="pat-modal__ghost" onClick={() => void copy()}>
                {copied ? 'Copié' : 'Copier'}
              </button>
              <button type="button" className="pat-modal__cta" onClick={onClose}>
                Terminé
              </button>
            </div>
          </>
        ) : (
          <>
            <h2 id="pat-modal-title" className="pat-modal__title" data-mock-id="pat-generate-title">
              Générer un jeton d&apos;accès personnel
            </h2>
            <p className="pat-modal__desc" data-mock-id="pat-generate-desc">
              Ce jeton agit avec vos permissions personnelles. Il n&apos;est affiché qu&apos;une seule
              fois — conservez-le dans un gestionnaire de secrets.
            </p>

            <label
              className="pat-modal__label"
              htmlFor="pat-name"
              data-mock-id="pat-generate-name-label"
            >
              Nom du jeton
            </label>
            <input
              id="pat-name"
              type="text"
              className="pat-modal__input"
              value={name}
              maxLength={100}
              onChange={(e) => setName(e.target.value)}
              data-mock-id="pat-generate-name-input"
            />

            <label className="pat-modal__label pat-modal__label--mb8" data-mock-id="pat-generate-scope-label">
              Portée d&apos;accès
            </label>
            <div className="pat-modal__scopes" role="radiogroup" data-mock-id="pat-generate-scopes">
              {SCOPES.map((s) => (
                <label
                  key={s.value}
                  className={`pat-modal__scope${scope === s.value ? ' pat-modal__scope--on' : ''}`}
                >
                  <input
                    type="radio"
                    name="pat-scope"
                    checked={scope === s.value}
                    onChange={() => setScope(s.value)}
                  />
                  <div>
                    <div className="pat-modal__scope-title">{s.title}</div>
                    <div className="pat-modal__scope-desc">{s.desc}</div>
                  </div>
                </label>
              ))}
            </div>

            <label className="pat-modal__label pat-modal__label--mb8" data-mock-id="pat-generate-expiry-label">
              Expiration
            </label>
            <div
              className="pat-modal__presets"
              role="radiogroup"
              aria-label="Expiration"
              data-mock-id="pat-generate-expiry"
              data-visual-mask="pat-generate-expiry-presets"
            >
              {PAT_EXPIRY_PRESETS.map((d) => (
                <button
                  key={d}
                  type="button"
                  role="radio"
                  aria-checked={days === d}
                  className={`pat-modal__preset${days === d ? ' pat-modal__preset--on' : ''}`}
                  onClick={() => setDays(d)}
                >
                  {d} jours
                </button>
              ))}
            </div>

            {error ? (
              <p className="pat-modal__error" role="alert">
                {error}
              </p>
            ) : null}

            <div className="pat-modal__actions" data-mock-id="pat-generate-actions">
              <button type="button" className="pat-modal__ghost" onClick={onClose}>
                Annuler
              </button>
              <button
                type="button"
                className="pat-modal__cta"
                disabled={submitting}
                onClick={() => void submit()}
              >
                {submitting ? 'Génération…' : 'Générer le jeton'}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
