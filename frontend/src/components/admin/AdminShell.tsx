// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/AuthProvider'
import './admin-page.css'

export type AdminNavKey =
  | 'identity'
  | 'branding'
  | 'tags'
  | 'custom-fields'
  | 'templates'
  | 'workflows'
  | 'retention'
  | 'licence'
  | 'audit'
  | 'integrations'

type NavItem =
  | { kind: 'link'; key: AdminNavKey; label: string; to: string; mockId?: string }
  | { kind: 'placeholder'; label: string; soon?: boolean }

/** Ordre Admin.dc.html */
const NAV_ITEMS: NavItem[] = [
  { kind: 'link', key: 'identity', label: 'Identité & SSO', to: '/admin', mockId: 'admin-nav-identity' },
  {
    kind: 'link',
    key: 'branding',
    label: 'Personnalisation de marque',
    to: '/admin/branding',
    mockId: 'admin-nav-branding',
  },
  { kind: 'placeholder', label: 'Membres & équipes', soon: true },
  { kind: 'placeholder', label: 'Identités utilisateurs', soon: true },
  { kind: 'placeholder', label: 'Rôles globaux', soon: true },
  { kind: 'link', key: 'integrations', label: 'Intégrations & API', to: '/integrations' },
  { kind: 'placeholder', label: 'Santé du contenu', soon: true },
  { kind: 'placeholder', label: 'Analytique', soon: true },
  { kind: 'link', key: 'tags', label: 'Tags', to: '/admin/tags', mockId: 'admin-nav-tags' },
  {
    kind: 'link',
    key: 'custom-fields',
    label: 'Champs personnalisés',
    to: '/admin/custom-fields',
    mockId: 'admin-nav-custom-fields',
  },
  { kind: 'link', key: 'templates', label: 'Modèles', to: '/admin/templates' },
  {
    kind: 'link',
    key: 'workflows',
    label: "Workflows d'approbation",
    to: '/admin/workflows',
  },
  {
    kind: 'link',
    key: 'retention',
    label: 'Rétention & conformité',
    to: '/admin/retention',
    mockId: 'admin-nav-retention',
  },
  { kind: 'placeholder', label: 'Attestations', soon: true },
  { kind: 'link', key: 'audit', label: "Journal d'audit", to: '/audit' },
  {
    kind: 'link',
    key: 'licence',
    label: 'Licence',
    to: '/admin/licence',
    mockId: 'admin-nav-licence',
  },
]

export type AdminShellProps = {
  active: AdminNavKey
  /** Segments after optional « Compte » — last segment is current page title. */
  breadcrumb: { label: string; to?: string }[]
  children: ReactNode
  rightRail?: ReactNode
  mainClassName?: string
  innerWide?: boolean
  /** Extra class on the inner content wrapper (ex. max-width 700 pour Retention). */
  innerClassName?: string
}

/** Layout admin — fil d'Ariane 60px, sous-nav 240px, contenu, rail optionnel. */
export function AdminShell({
  active,
  breadcrumb,
  children,
  rightRail,
  mainClassName = '',
  innerWide = false,
  innerClassName = '',
}: AdminShellProps) {
  const { organizationName } = useAuth()

  return (
    <div className="admin-page" data-mock-id="admin-page">
      <div className="admin-page__breadcrumb" data-mock-id="admin-breadcrumb">
        <div className="admin-page__breadcrumb-trail">
          {breadcrumb.map((seg, i) => {
            const last = i === breadcrumb.length - 1
            return (
              <span key={`${seg.label}-${i}`} style={{ display: 'contents' }}>
                {i > 0 && <span style={{ color: '#dedee1' }}> → </span>}
                {last ? (
                  <span className="admin-page__breadcrumb-current">{seg.label}</span>
                ) : seg.to ? (
                  <Link to={seg.to}>{seg.label}</Link>
                ) : (
                  <span>{seg.label}</span>
                )}
              </span>
            )
          })}
        </div>
        <Link to="/" className="admin-page__home-link">
          Retour à l&apos;accueil
        </Link>
      </div>

      <div className="admin-page__body">
        <nav className="admin-subnav" aria-label="Administration" data-mock-id="admin-subnav">
          <div className="admin-subnav__org">{organizationName}</div>
          {NAV_ITEMS.map((item) => {
            if (item.kind === 'placeholder') {
              return (
                <span
                  key={item.label}
                  className="admin-nav-item admin-nav-item--muted"
                  aria-disabled
                >
                  {item.label}
                  {item.soon ? (
                    <span
                      className="admin-nav-soon"
                      data-visual-ignore
                      data-visual-mask="admin-subnav-bientot"
                    >
                      Bientôt
                    </span>
                  ) : null}
                </span>
              )
            }
            const on = item.key === active
            return (
              <Link
                key={item.to}
                to={item.to}
                className={`admin-nav-item${on ? ' admin-nav-item--on' : ''}`}
                data-mock-id={item.mockId}
                aria-current={on ? 'page' : undefined}
              >
                {item.label}
              </Link>
            )
          })}
        </nav>

        <div className={`admin-fields-split${rightRail ? '' : ' admin-fields-split--solo'}`}>
          <main className={`admin-main ${mainClassName}`.trim()}>
            <div
              className={`${innerWide ? 'admin-main__inner--wide' : 'admin-main__inner'}${innerClassName ? ` ${innerClassName}` : ''}`.trim()}
            >
              {children}
            </div>
          </main>
          {rightRail}
        </div>
      </div>
    </div>
  )
}
