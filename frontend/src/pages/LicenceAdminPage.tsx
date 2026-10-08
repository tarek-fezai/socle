// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AdminShell } from '../components/admin/AdminShell'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/apiError'
import {
  getLicence,
  importLicence,
  licenceAdminKey,
  type LicenceStatus,
  type LicenceView,
} from '../lib/licenceAdmin'

function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleDateString('fr-FR', {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
    })
  } catch {
    return iso
  }
}

function statusLabel(s: LicenceStatus): string {
  switch (s) {
    case 'valide':
      return 'Valide'
    case 'expire_bientot':
      return 'Expire bientôt'
    case 'expiree':
      return 'Expirée'
    case 'absente':
      return 'Aucune licence'
    default:
      return s
  }
}

function statusColor(s: LicenceStatus): string {
  switch (s) {
    case 'valide':
      return '#1E8E5A'
    case 'expire_bientot':
      return '#C47A00'
    case 'expiree':
    case 'absente':
      return '#C0392B'
    default:
      return '#9B9BA1'
  }
}

/** Administration Licence (Licence.dc.html). */
export function LicenceAdminPage() {
  const qc = useQueryClient()
  const fileRef = useRef<HTMLInputElement>(null)
  const [error, setError] = useState<string | null>(null)

  const query = useQuery({
    queryKey: licenceAdminKey(),
    queryFn: () => getLicence(api),
  })

  const importMut = useMutation({
    mutationFn: (licenceJson: string) => importLicence(api, licenceJson),
    onSuccess: () => {
      setError(null)
      void qc.invalidateQueries({ queryKey: licenceAdminKey() })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Import de licence impossible')),
  })

  function onPickFile(file: File | null) {
    if (!file) return
    const reader = new FileReader()
    reader.onload = () => {
      const text = String(reader.result ?? '')
      importMut.mutate(text)
    }
    reader.onerror = () => setError('Lecture du fichier impossible')
    reader.readAsText(file)
  }

  const lic: LicenceView | undefined = query.data
  const forbidden =
    query.isError && (query.error as { response?: { status?: number } })?.response?.status === 403

  return (
    <AdminShell
      active="licence"
      breadcrumb={[
        { label: 'Administration', to: '/admin' },
        { label: 'Licence' },
      ]}
      innerClassName="admin-main__inner--retention"
    >
      <h1 className="admin-title" data-mock-id="licence-title" style={{ marginBottom: 6 }}>
        Licence
      </h1>
      <p className="admin-lead" data-mock-id="licence-lead" style={{ marginBottom: 28 }}>
        Édition, capacité et échéance de la licence de l&apos;instance.
      </p>

      {forbidden && (
        <p className="admin-alert admin-alert--error" role="alert">
          Administrateur système requis pour consulter la licence.
        </p>
      )}

      {query.isError && !forbidden && (
        <p className="admin-alert admin-alert--error" role="alert">
          {apiErrorMessage(query.error, 'Impossible de charger la licence')}
        </p>
      )}

      {error && (
        <p className="admin-alert admin-alert--error" role="alert">
          {error}
        </p>
      )}

      {lic?.bannerMessage && (
        <p
          className="admin-alert"
          role="status"
          data-mock-id="licence-banner"
          style={{
            marginBottom: 16,
            borderColor: statusColor(lic.status),
            color: statusColor(lic.status),
          }}
        >
          {lic.bannerMessage}
        </p>
      )}

      <div
        style={{
          border: '1px solid #ECECEE',
          borderRadius: 12,
          padding: '20px 22px',
          marginBottom: 16,
        }}
        data-mock-id="licence-edition"
      >
        <div
          style={{
            fontSize: 12,
            fontWeight: 600,
            color: '#9B9BA1',
            textTransform: 'uppercase',
            letterSpacing: '0.05em',
            marginBottom: 6,
          }}
        >
          Édition
        </div>
        <div style={{ fontSize: 20, fontWeight: 700, color: '#0E0E10' }}>
          {lic?.status === 'absente'
            ? 'Aucune licence'
            : (lic?.edition ?? '…')}
        </div>
        {lic && (
          <div style={{ fontSize: 12, color: statusColor(lic.status), marginTop: 8, fontWeight: 600 }}>
            {lic.status === 'absente' ? 'Aucune licence' : statusLabel(lic.status)}
            {lic.evaluationMode && lic.status !== 'absente' ? ' · sans licence valide' : ''}
          </div>
        )}
      </div>

      <div style={{ display: 'flex', gap: 16, marginBottom: 16 }} data-mock-id="licence-stats">
        <StatCard label="Utilisateurs autorisés" value={lic ? String(lic.effectiveMaxUsers) : '…'} />
        <StatCard label="Utilisateurs utilisés" value={lic ? String(lic.activeUsers) : '…'} serif />
        <StatCard label="Date d'échéance" value={formatDate(lic?.expiresAt)} serif smaller />
      </div>

      <div
        style={{
          border: '1px solid #ECECEE',
          borderRadius: 12,
          padding: '18px 20px',
          marginBottom: 28,
        }}
        data-mock-id="licence-id"
      >
        <div
          style={{
            fontSize: 11,
            fontWeight: 600,
            color: '#9B9BA1',
            textTransform: 'uppercase',
            letterSpacing: '0.05em',
            marginBottom: 8,
          }}
        >
          Identifiant de licence
        </div>
        <div className="admin-mono" style={{ fontSize: 15, color: '#0E0E10', letterSpacing: '0.02em' }}>
          {lic?.licenseId ?? '—'}
        </div>
      </div>

      <input
        ref={fileRef}
        type="file"
        accept="application/json,.json"
        style={{ display: 'none' }}
        onChange={(e) => {
          onPickFile(e.target.files?.[0] ?? null)
          e.target.value = ''
        }}
      />
      <button
        type="button"
        className="admin-cta"
        data-mock-id="licence-import"
        disabled={importMut.isPending}
        onClick={() => fileRef.current?.click()}
      >
        Importer un fichier de licence
      </button>
    </AdminShell>
  )
}

function StatCard({
  label,
  value,
  serif,
  smaller,
}: {
  label: string
  value: string
  serif?: boolean
  smaller?: boolean
}) {
  return (
    <div
      style={{
        flex: 1,
        border: '1px solid #ECECEE',
        borderRadius: 12,
        padding: '16px 18px',
      }}
    >
      <div style={{ fontSize: 11, color: '#9B9BA1', marginBottom: 6 }}>{label}</div>
      <div
        style={{
          fontSize: smaller ? 22 : 26,
          color: '#0E0E10',
          fontFamily: serif ? 'var(--admin-serif, Georgia, serif)' : undefined,
        }}
      >
        {value}
      </div>
    </div>
  )
}
