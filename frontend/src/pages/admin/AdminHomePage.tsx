// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { AdminShell } from '../../components/admin/AdminShell'
import { api } from '../../lib/api'
import { adminOverviewKey, fetchAdminOverview } from '../../lib/adminOverview'
import { AdminForbiddenPage } from './AdminForbiddenPage'

function formatPlanLabel(edition: string | null | undefined, evaluationMode: boolean): string {
  if (evaluationMode || !edition?.trim()) return 'Évaluation'
  return `Licence ${edition.trim()}`
}

function formatExpiry(iso: string | null | undefined): string {
  if (!iso) return ''
  try {
    return new Intl.DateTimeFormat('fr-FR', {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
    }).format(new Date(iso))
  } catch {
    return ''
  }
}

/** Accueil administration — Identité & SSO (Admin.dc.html, lecture seule). */
export function AdminHomePage() {
  const query = useQuery({
    queryKey: adminOverviewKey(),
    queryFn: () => fetchAdminOverview(api),
  })

  const forbidden =
    query.isError && (query.error as { response?: { status?: number } })?.response?.status === 403

  if (forbidden) {
    return <AdminForbiddenPage />
  }

  const data = query.data
  const oidc = data?.oidc
  const connected = oidc?.status === 'connected'
  const planLabel = data ? formatPlanLabel(data.plan?.edition, data.plan?.evaluationMode ?? true) : '—'
  const expiry = data?.plan?.expiresAt ? formatExpiry(data.plan.expiresAt) : ''
  const planDetail =
    data?.plan?.evaluationMode || !expiry
      ? planLabel
      : `${planLabel} · échéance ${expiry}`

  return (
    <AdminShell
      active="identity"
      breadcrumb={[{ label: 'Compte', to: '/account' }, { label: 'Administration' }]}
      rightRail={
        data ? (
          <aside className="admin-rail admin-rail--home" data-mock-id="admin-home-rail">
            <div className="admin-rail__block" data-mock-id="admin-stats-users">
              <div className="admin-rail__label">Utilisateurs</div>
              <div className="admin-rail__value admin-rail__value--serif">{data.userCount}</div>
            </div>
            <div className="admin-rail__block" data-mock-id="admin-stats-spaces">
              <div className="admin-rail__label">Espaces</div>
              <div className="admin-rail__value admin-rail__value--serif">{data.spaceCount}</div>
            </div>
            <Link to="/admin/licence" className="admin-rail__block" data-mock-id="admin-stats-plan">
              <div className="admin-rail__label">Licence</div>
              <div className="admin-rail__text">{planDetail}</div>
            </Link>
          </aside>
        ) : undefined
      }
      innerWide
      innerClassName="admin-home"
    >
      <h1 className="admin-title" data-mock-id="admin-home-title" style={{ marginBottom: 6 }}>
        Identité &amp; SSO
      </h1>
      <p className="admin-lead" data-mock-id="admin-home-lead" style={{ marginBottom: 22 }}>
        Configuration de l&apos;authentification pour l&apos;ensemble de l&apos;organisation.
      </p>

      {query.isError && !forbidden ? (
        <p className="admin-alert admin-alert--error" role="alert">
          Impossible de charger la vue d&apos;ensemble administration.
        </p>
      ) : null}

      <div
        className="admin-home-oidc"
        data-mock-id="admin-sso-provider"
        style={{
          border: '1px solid #ececee',
          borderRadius: 12,
          padding: '16px 18px',
          marginBottom: 14,
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'flex-start',
            justifyContent: 'space-between',
            marginBottom: 12,
          }}
        >
          <div>
            <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 3 }}>Fournisseur OIDC</div>
            <div style={{ fontSize: 12, color: '#9b9ba1' }}>OpenID Connect · client public</div>
          </div>
          {connected ? (
            <span className="account-status-ok">
              <span className="account-status-dot" aria-hidden />
              Connecté
            </span>
          ) : (
            <span style={{ fontSize: 12, color: '#9b9ba1', fontWeight: 600 }}>Indisponible</span>
          )}
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 12 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span
              style={{
                fontSize: 11,
                fontWeight: 600,
                color: '#9b9ba1',
                width: 72,
                flexShrink: 0,
              }}
            >
              Issuer
            </span>
            <span className="admin-mono" style={{ fontSize: 12, wordBreak: 'break-all' }}>
              {oidc?.issuer || '—'}
            </span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span
              style={{
                fontSize: 11,
                fontWeight: 600,
                color: '#9b9ba1',
                width: 72,
                flexShrink: 0,
              }}
            >
              Client ID
            </span>
            <span className="admin-mono" style={{ fontSize: 12 }}>
              {oidc?.clientId || '—'}
            </span>
          </div>
        </div>
        <div
          style={{
            fontSize: 12,
            color: '#6b6b72',
            background: '#fafafb',
            borderRadius: 7,
            padding: '8px 11px',
          }}
        >
          SAML via un broker (Keycloak, Zitadel, Authentik…)
        </div>
      </div>

      <div className="admin-home-section-label">Domaines autorisés</div>
      <div className="admin-home-disabled-block" data-mock-id="admin-domains">
        <span className="account-soon" data-visual-ignore>
          Bientôt
        </span>
      </div>

      <div
        style={{
          background: '#f0effc',
          border: '1px solid #d9d7f5',
          borderRadius: 10,
          padding: '12px 14px',
          marginBottom: 14,
          fontSize: 12.5,
          color: '#3730e0',
          lineHeight: 1.45,
        }}
      >
        Politiques d&apos;authentification gérées par votre fournisseur d&apos;identité
      </div>

      <div className="admin-home-section-label">Politique de session</div>
      <div className="admin-home-disabled-block" data-mock-id="admin-session-policy">
        <span className="account-soon" data-visual-ignore>
          Bientôt
        </span>
      </div>

      <div className="admin-home-section-label">Langues de l&apos;organisation</div>
      <div className="admin-home-disabled-block" data-mock-id="admin-languages">
        <span className="account-soon" data-visual-ignore>
          Bientôt
        </span>
      </div>

      <div className="admin-home-section-label">Sessions</div>
      <div className="admin-home-disabled-block" data-mock-id="admin-revoke-sessions">
        <span className="account-soon" data-visual-ignore>
          Bientôt
        </span>
      </div>
    </AdminShell>
  )
}
