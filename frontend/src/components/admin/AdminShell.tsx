// SPDX-License-Identifier: AGPL-3.0-or-later
import { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/AuthProvider'
import './admin-page.css'

export type AdminNavKey = 'tags' | 'custom-fields' | 'templates' | 'retention' | 'branding'

type NavItem =
  | { kind: 'link'; key?: AdminNavKey; label: string; to: string; mockId?: string }
  | { kind: 'placeholder'; label: string }

const NAV_ITEMS: NavItem[] = [
  { kind: 'placeholder', label: 'Identité & SSO' },
  {
    kind: 'link',
    key: 'branding',
    label: 'Personnalisation de marque',
    to: '/admin/branding',
    mockId: 'admin-nav-branding',
  },
  { kind: 'placeholder', label: 'Membres & équipes' },
  { kind: 'placeholder', label: 'Identités utilisateurs' },
  { kind: 'placeholder', label: 'Rôles globaux' },
  { kind: 'placeholder', label: 'Intégrations & API' },
  { kind: 'placeholder', label: 'Santé du contenu' },
  { kind: 'placeholder', label: 'Analytique' },
  { kind: 'link', key: 'tags', label: 'Tags', to: '/admin/tags', mockId: 'admin-nav-tags' },
  {
    kind: 'link',
    key: 'custom-fields',
    label: 'Champs personnalisés',
    to: '/admin/custom-fields',
    mockId: 'admin-nav-custom-fields',
  },
  { kind: 'link', key: 'templates', label: 'Modèles', to: '/admin/templates' },
  { kind: 'placeholder', label: "Workflows d'approbation" },
  {
    kind: 'link',
    key: 'retention',
    label: 'Rétention & conformité',
    to: '/admin/retention',
    mockId: 'admin-nav-retention',
  },
  { kind: 'placeholder', label: 'Attestations' },
  { kind: 'placeholder', label: "Journal d'audit" },
  { kind: 'placeholder', label: 'Licence' },
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

/** Layout admin (TagsAdmin / CustomFields) — fil d'Ariane 60px, sous-nav 240px, contenu, rail optionnel. */
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
                <span key={item.label} className="admin-nav-item admin-nav-item--muted">
                  {item.label}
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
