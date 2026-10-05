// SPDX-License-Identifier: AGPL-3.0-or-later
import { FormEvent, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AdminShell } from '../components/admin/AdminShell'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/apiError'
import {
  formatRetentionReviewDate,
  getRetentionSettings,
  legalHoldsKey,
  listLegalHolds,
  placeLegalHold,
  releaseLegalHold,
  retentionAdminKey,
  updateRetentionSettings,
  versionRetentionLabel,
  type LegalHoldScopeType,
  type LegalHoldView,
  type VersionRetentionMode,
} from '../lib/retentionAdmin'

type HoldModal = 'create' | 'release' | null

/** Administration rétention & conformité (Retention.dc.html). */
export function RetentionAdminPage() {
  const qc = useQueryClient()
  const [editKey, setEditKey] = useState<'audit' | 'versions' | 'archived' | 'review' | null>(null)
  const [holdModal, setHoldModal] = useState<HoldModal>(null)
  const [releaseTarget, setReleaseTarget] = useState<LegalHoldView | null>(null)
  const [scopeType, setScopeType] = useState<LegalHoldScopeType>('document')
  const [scopeId, setScopeId] = useState('')
  const [holdReason, setHoldReason] = useState('')
  const [releaseReason, setReleaseReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const settingsQuery = useQuery({
    queryKey: retentionAdminKey(),
    queryFn: () => getRetentionSettings(api),
  })
  const holdsQuery = useQuery({
    queryKey: legalHoldsKey(true),
    queryFn: () => listLegalHolds(api, true),
  })

  const settings = settingsQuery.data
  const holds = holdsQuery.data?.holds ?? []
  const activeCount = settings?.activeLegalHolds ?? holdsQuery.data?.activeCount ?? 0

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: retentionAdminKey() })
    void qc.invalidateQueries({ queryKey: legalHoldsKey(true) })
  }

  const saveMut = useMutation({
    mutationFn: (body: Parameters<typeof updateRetentionSettings>[1]) =>
      updateRetentionSettings(api, body),
    onSuccess: () => {
      setEditKey(null)
      setError(null)
      invalidate()
    },
    onError: (e) => setError(apiErrorMessage(e, 'Enregistrement impossible')),
  })

  const placeMut = useMutation({
    mutationFn: () =>
      placeLegalHold(api, {
        scopeType,
        scopeId: scopeId.trim(),
        reason: holdReason.trim(),
      }),
    onSuccess: () => {
      setHoldModal(null)
      setScopeId('')
      setHoldReason('')
      setError(null)
      invalidate()
    },
    onError: (e) => setError(apiErrorMessage(e, 'Gel légal impossible')),
  })

  const releaseMut = useMutation({
    mutationFn: () => releaseLegalHold(api, releaseTarget!.id, releaseReason.trim()),
    onSuccess: () => {
      setHoldModal(null)
      setReleaseTarget(null)
      setReleaseReason('')
      setError(null)
      invalidate()
    },
    onError: (e) => setError(apiErrorMessage(e, 'Levée du gel impossible')),
  })

  function persist(patch: Partial<{
    auditRetentionMonths: number
    versionRetentionMode: VersionRetentionMode
    versionRetentionValue: number | null
    archivedDocsRetentionYears: number
    processingRegisterReviewedAt: string | null
  }>) {
    if (!settings) return
    saveMut.mutate({
      auditRetentionMonths: patch.auditRetentionMonths ?? settings.auditRetentionMonths,
      versionRetentionMode: patch.versionRetentionMode ?? settings.versionRetentionMode,
      versionRetentionValue:
        patch.versionRetentionValue !== undefined
          ? patch.versionRetentionValue
          : settings.versionRetentionValue,
      archivedDocsRetentionYears:
        patch.archivedDocsRetentionYears ?? settings.archivedDocsRetentionYears,
      processingRegisterReviewedAt:
        patch.processingRegisterReviewedAt !== undefined
          ? patch.processingRegisterReviewedAt
          : settings.processingRegisterReviewedAt,
    })
  }

  function onCreateHold(e: FormEvent) {
    e.preventDefault()
    if (!scopeId.trim() || !holdReason.trim()) return
    placeMut.mutate()
  }

  function onReleaseHold(e: FormEvent) {
    e.preventDefault()
    if (!releaseTarget || !releaseReason.trim()) return
    releaseMut.mutate()
  }

  const forbidden =
    (settingsQuery.isError && (settingsQuery.error as { response?: { status?: number } })?.response?.status === 403) ||
    (holdsQuery.isError && (holdsQuery.error as { response?: { status?: number } })?.response?.status === 403)

  return (
    <AdminShell
      active="retention"
      breadcrumb={[
        { label: 'Administration', to: '/admin/tags' },
        { label: 'Rétention & conformité' },
      ]}
      innerClassName="admin-main__inner--retention"
    >
      <h1 className="admin-title" data-mock-id="retention-title" style={{ marginBottom: 6 }}>
        Rétention &amp; conformité
      </h1>
      {/* Compensation mesurée : lead 22px (maquette 28) pour annuler cascade box.y Δ=6 sous durées. */}
      <p className="admin-lead" data-mock-id="retention-lead" style={{ marginBottom: 22 }}>
        Durées de conservation et politiques réglementaires applicables à l&apos;ensemble de
        l&apos;organisation.
      </p>

      {forbidden && (
        <p className="admin-alert admin-alert--error" role="alert">
          Administrateur système requis pour consulter la rétention et les gels légaux.
        </p>
      )}

      {settingsQuery.isError && !forbidden && (
        <p className="admin-alert admin-alert--error" role="alert">
          {apiErrorMessage(settingsQuery.error, 'Impossible de charger la rétention')}
        </p>
      )}

      {error && (
        <p className="admin-alert admin-alert--error" role="alert">
          {error}
        </p>
      )}

      <div
        className="admin-section-label"
        data-mock-id="retention-durations-label"
        style={{
          fontSize: 12,
          fontWeight: 600,
          color: '#9B9BA1',
          textTransform: 'uppercase',
          letterSpacing: '0.05em',
          marginBottom: 10,
        }}
      >
        Durées de conservation
      </div>
      <div
        className="admin-table-wrap"
        data-mock-id="retention-durations"
        style={{ marginBottom: 28 }}
      >
        <RetentionRow
          title="Journal d'audit"
          hint="Actions tracées sur la documentation"
          value={
            editKey === 'audit' && settings ? (
              <input
                className="admin-inline-input"
                type="number"
                min={1}
                max={1200}
                defaultValue={settings.auditRetentionMonths}
                autoFocus
                aria-label="Mois de rétention audit"
                onBlur={(e) => {
                  const n = Number(e.target.value)
                  if (Number.isFinite(n) && n !== settings.auditRetentionMonths) {
                    persist({ auditRetentionMonths: n })
                  } else setEditKey(null)
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
                  if (e.key === 'Escape') setEditKey(null)
                }}
              />
            ) : (
              <span
                role="button"
                tabIndex={0}
                className="admin-retention-value"
                onClick={() => setEditKey('audit')}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') setEditKey('audit')
                }}
              >
                {settings ? `${settings.auditRetentionMonths} mois` : '…'}
              </span>
            )
          }
        />
        <RetentionRow
          title="Historique des versions"
          hint="Révisions publiées d'un document"
          value={
            editKey === 'versions' && settings ? (
              <select
                className="admin-inline-input"
                defaultValue={settings.versionRetentionMode}
                autoFocus
                aria-label="Mode rétention versions"
                onChange={(e) => {
                  const mode = e.target.value as VersionRetentionMode
                  persist({
                    versionRetentionMode: mode,
                    versionRetentionValue: mode === 'unlimited' ? null : settings.versionRetentionValue ?? 12,
                  })
                }}
                onBlur={() => setEditKey(null)}
              >
                <option value="unlimited">Illimitée</option>
                <option value="months">Par mois</option>
                <option value="count">Par nombre</option>
              </select>
            ) : (
              <span
                role="button"
                tabIndex={0}
                className="admin-retention-value"
                onClick={() => setEditKey('versions')}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') setEditKey('versions')
                }}
              >
                {settings
                  ? versionRetentionLabel(settings.versionRetentionMode, settings.versionRetentionValue)
                  : '…'}
              </span>
            )
          }
        />
        <RetentionRow
          title="Documents archivés"
          hint="Espaces archivés depuis leurs paramètres"
          last
          value={
            editKey === 'archived' && settings ? (
              <input
                className="admin-inline-input"
                type="number"
                min={1}
                max={100}
                defaultValue={settings.archivedDocsRetentionYears}
                autoFocus
                aria-label="Années rétention archives"
                onBlur={(e) => {
                  const n = Number(e.target.value)
                  if (Number.isFinite(n) && n !== settings.archivedDocsRetentionYears) {
                    persist({ archivedDocsRetentionYears: n })
                  } else setEditKey(null)
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
                  if (e.key === 'Escape') setEditKey(null)
                }}
              />
            ) : (
              <span
                role="button"
                tabIndex={0}
                className="admin-retention-value"
                onClick={() => setEditKey('archived')}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') setEditKey('archived')
                }}
              >
                {settings ? `${settings.archivedDocsRetentionYears} ans` : '…'}
              </span>
            )
          }
        />
      </div>

      <div
        className="admin-section-label"
        style={{
          fontSize: 12,
          fontWeight: 600,
          color: '#9B9BA1',
          textTransform: 'uppercase',
          letterSpacing: '0.05em',
          marginBottom: 10,
        }}
      >
        Conformité réglementaire
      </div>
      <div
        className="admin-table-wrap"
        data-mock-id="retention-compliance"
        style={{ marginBottom: 28 }}
      >
        <div className="admin-table-row" style={{ padding: '13px 16px' }}>
          <div style={{ flexGrow: 1 }}>
            <div style={{ fontSize: 13.5, fontWeight: 500, color: '#0E0E10' }}>
              Résidence des données
            </div>
            <div style={{ fontSize: 12, color: '#9B9BA1', marginTop: 2 }} data-mock-id="retention-residence">
              {settings?.dataResidenceLabel ?? 'Non configurée (SOCLE_DATA_RESIDENCE_LABEL)'}
            </div>
          </div>
          {settings?.dataResidenceLabel ? (
            <span
              style={{
                fontSize: 12,
                color: '#1E8E5A',
                fontWeight: 600,
                display: 'flex',
                alignItems: 'center',
                gap: 5,
              }}
            >
              <span
                style={{ width: 5, height: 5, borderRadius: '50%', background: '#1E8E5A' }}
                aria-hidden
              />
              Conforme RGPD
            </span>
          ) : null}
        </div>

        <div className="admin-table-row" style={{ padding: '13px 16px' }}>
          <div style={{ flexGrow: 1 }}>
            <div style={{ fontSize: 13.5, fontWeight: 500, color: '#0E0E10' }}>
              Suspension légale (legal hold)
            </div>
            <div style={{ fontSize: 12, color: '#9B9BA1', marginTop: 2 }}>
              Bloque la suppression des documents concernés par une procédure
              {activeCount > 0 ? ` · ${activeCount} actif${activeCount > 1 ? 's' : ''}` : ''}
            </div>
          </div>
          <span
            role="button"
            tabIndex={0}
            className={`admin-toggle${activeCount > 0 ? ' admin-toggle--on' : ''}`}
            data-mock-id="retention-legal-hold-toggle"
            aria-pressed={activeCount > 0}
            aria-label="Gérer les gels légaux"
            onClick={() => {
              setError(null)
              setHoldModal('create')
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                setError(null)
                setHoldModal('create')
              }
            }}
          >
            <span className="admin-toggle__knob" />
          </span>
        </div>

        <div className="admin-table-row" style={{ padding: '13px 16px', borderBottom: 'none' }}>
          <div style={{ flexGrow: 1 }}>
            <div style={{ fontSize: 13.5, fontWeight: 500, color: '#0E0E10' }}>
              Registre des traitements à jour
            </div>
            <div style={{ fontSize: 12, color: '#9B9BA1', marginTop: 2 }} data-mock-id="retention-register">
              Dernière revue :{' '}
              {editKey === 'review' && settings ? (
                <input
                  type="date"
                  className="admin-inline-input"
                  defaultValue={settings.processingRegisterReviewedAt ?? ''}
                  autoFocus
                  aria-label="Date de revue du registre"
                  onBlur={(e) => {
                    const v = e.target.value || null
                    if (v !== settings.processingRegisterReviewedAt) {
                      persist({ processingRegisterReviewedAt: v })
                    } else setEditKey(null)
                  }}
                />
              ) : (
                formatRetentionReviewDate(settings?.processingRegisterReviewedAt ?? null)
              )}
            </div>
          </div>
          <a
            href="#retention-register"
            className="admin-link-action"
            data-mock-id="retention-register-link"
            onClick={(e) => {
              e.preventDefault()
              setEditKey('review')
            }}
          >
            Consulter →
          </a>
        </div>
      </div>

      {holds.length > 0 && (
        <div className="admin-table-wrap" data-mock-id="retention-holds-list" style={{ marginBottom: 28 }}>
          {holds.map((h, i) => (
            <div
              key={h.id}
              className="admin-table-row"
              style={{
                padding: '13px 16px',
                borderBottom: i === holds.length - 1 ? 'none' : undefined,
              }}
            >
              <div style={{ flexGrow: 1 }}>
                <div style={{ fontSize: 13.5, fontWeight: 500 }}>
                  {h.scopeLabel ?? h.scopeId}{' '}
                  <span style={{ color: '#9B9BA1', fontWeight: 400 }}>({h.scopeType})</span>
                </div>
                <div style={{ fontSize: 12, color: '#9B9BA1', marginTop: 2 }}>{h.reason}</div>
              </div>
              <button
                type="button"
                className="admin-action admin-action--danger"
                onClick={() => {
                  setReleaseTarget(h)
                  setReleaseReason('')
                  setHoldModal('release')
                }}
              >
                Lever
              </button>
            </div>
          ))}
        </div>
      )}

      <div
        style={{
          fontSize: 12,
          fontWeight: 600,
          color: '#9B9BA1',
          textTransform: 'uppercase',
          letterSpacing: '0.05em',
          marginBottom: 10,
        }}
      >
        Rapports
      </div>
      <Link
        to="/spaces"
        className="admin-report-card"
        data-mock-id="retention-report-health"
        style={{ marginBottom: 12 }}
      >
        <div>
          <div style={{ fontSize: 13.5, fontWeight: 600, color: '#0E0E10', marginBottom: 2 }}>
            Santé du contenu
          </div>
          <div style={{ fontSize: 12, color: '#9B9BA1' }}>
            Documents en retard de revue, orphelins ou obsolètes
          </div>
        </div>
        <ChevronIcon />
      </Link>
      <Link
        to="/docs"
        className="admin-report-card"
        data-mock-id="retention-report-export"
        style={{ marginBottom: 40 }}
      >
        <div>
          <div style={{ fontSize: 13.5, fontWeight: 600, color: '#0E0E10', marginBottom: 2 }}>
            Export de conformité
          </div>
          <div style={{ fontSize: 12, color: '#9B9BA1' }}>
            Exporter un document avec filigrane et pied de page réglementaire
          </div>
        </div>
        <ChevronIcon />
      </Link>

      {holdModal === 'create' && (
        <div className="admin-modal-backdrop" role="presentation" onClick={() => setHoldModal(null)}>
          <form
            className="admin-modal"
            role="dialog"
            aria-labelledby="hold-create-title"
            onClick={(e) => e.stopPropagation()}
            onSubmit={onCreateHold}
          >
            <h2 id="hold-create-title">Nouveau gel légal</h2>
            <p>Les documents ou l&apos;espace ciblé ne pourront plus être supprimés ni purgés.</p>
            <label className="admin-form-label" htmlFor="hold-scope-type">
              Périmètre
            </label>
            <select
              id="hold-scope-type"
              className="admin-form-input"
              value={scopeType}
              onChange={(e) => setScopeType(e.target.value as LegalHoldScopeType)}
            >
              <option value="document">Document</option>
              <option value="space">Espace</option>
            </select>
            <label className="admin-form-label" htmlFor="hold-scope-id">
              Identifiant (UUID)
            </label>
            <input
              id="hold-scope-id"
              className="admin-form-input"
              value={scopeId}
              onChange={(e) => setScopeId(e.target.value)}
              required
            />
            <label className="admin-form-label" htmlFor="hold-reason">
              Motif
            </label>
            <input
              id="hold-reason"
              className="admin-form-input"
              value={holdReason}
              onChange={(e) => setHoldReason(e.target.value)}
              required
            />
            <div className="admin-form-actions" style={{ paddingBottom: 0 }}>
              <button type="button" className="admin-btn-ghost" onClick={() => setHoldModal(null)}>
                Annuler
              </button>
              <button type="submit" className="admin-cta" disabled={placeMut.isPending}>
                Placer le gel
              </button>
            </div>
          </form>
        </div>
      )}

      {holdModal === 'release' && releaseTarget && (
        <div className="admin-modal-backdrop" role="presentation" onClick={() => setHoldModal(null)}>
          <form
            className="admin-modal"
            role="dialog"
            aria-labelledby="hold-release-title"
            onClick={(e) => e.stopPropagation()}
            onSubmit={onReleaseHold}
          >
            <h2 id="hold-release-title">Lever le gel légal</h2>
            <p>
              {releaseTarget.scopeLabel ?? releaseTarget.scopeId} — {releaseTarget.reason}
            </p>
            <label className="admin-form-label" htmlFor="hold-release-reason">
              Motif de levée
            </label>
            <input
              id="hold-release-reason"
              className="admin-form-input"
              value={releaseReason}
              onChange={(e) => setReleaseReason(e.target.value)}
              required
            />
            <div className="admin-form-actions" style={{ paddingBottom: 0 }}>
              <button type="button" className="admin-btn-ghost" onClick={() => setHoldModal(null)}>
                Annuler
              </button>
              <button type="submit" className="admin-cta" disabled={releaseMut.isPending}>
                Lever
              </button>
            </div>
          </form>
        </div>
      )}
    </AdminShell>
  )
}

function RetentionRow({
  title,
  hint,
  value,
  last,
}: {
  title: string
  hint: string
  value: ReactNode
  last?: boolean
}) {
  return (
    <div
      className="admin-table-row"
      style={{
        padding: '13px 16px',
        borderBottom: last ? 'none' : undefined,
      }}
    >
      <div style={{ flexGrow: 1 }}>
        <div style={{ fontSize: 13.5, fontWeight: 500, color: '#0E0E10' }}>{title}</div>
        <div style={{ fontSize: 12, color: '#9B9BA1', marginTop: 2 }}>{hint}</div>
      </div>
      {value}
    </div>
  )
}

function ChevronIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#9B9BA1" strokeWidth="2" aria-hidden>
      <polyline points="9 18 15 12 9 6" />
    </svg>
  )
}
