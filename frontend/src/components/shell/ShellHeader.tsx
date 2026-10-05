// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthProvider'
import { api } from '../../lib/api'
import { getCachedAuthConfig } from '../../lib/auth'
import { listNotifications } from '../../lib/notifications'

export function ShellHeader() {
  const { authenticated } = useAuth()
  // Relire après chargement d'auth-config (useAuth force le re-render).
  const support = getCachedAuthConfig()?.supportContact?.trim() || ''
  const unread = useQuery({
    queryKey: ['notifications', 'unread-count'],
    queryFn: () => listNotifications(api, { unreadOnly: true, limit: 1 }),
    enabled: authenticated,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
  })
  const count = unread.data?.unreadCount ?? 0

  return (
    <header className="shell-header shell-desktop-only" data-mock-id="shell-header">
      <Link
        to="/docs"
        className="shell-icon-btn"
        aria-label="Nouveautés"
        title="Nouveautés"
        data-mock-id="shell-header-changelog"
      >
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#43434A" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <polygon points="20 12 20 22 4 22 4 12" />
          <rect x="2" y="7" width="20" height="5" />
          <line x1="12" y1="22" x2="12" y2="7" />
          <path d="M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7z" />
          <path d="M12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z" />
        </svg>
        <span className="shell-icon-dot shell-icon-dot--accent" aria-hidden />
      </Link>
      <a
        href="https://docs.example.com"
        className="shell-icon-btn"
        aria-label="Centre d'aide"
        title="Centre d'aide"
        data-mock-id="shell-header-help"
        target="_blank"
        rel="noreferrer"
      >
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#6B6B72" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <circle cx="12" cy="12" r="10" />
          <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
          <line x1="12" y1="17" x2="12.01" y2="17" />
        </svg>
      </a>
      {support ? (
        <a
          href={`mailto:${support}`}
          className="shell-icon-btn shell-icon-btn--muted"
          aria-label="Contacter le support"
          title="Contacter le support"
          data-mock-id="shell-header-shortcuts"
        >
          ?
        </a>
      ) : null}
      <Link
        to="/notifications"
        className="shell-icon-btn"
        aria-label={count > 0 ? `Notifications, ${count} non lues` : 'Notifications'}
        data-mock-id="shell-header-notifications"
        data-testid="shell-header-notifications"
      >
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#43434A" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
          <path d="M13.73 21a2 2 0 0 1-3.46 0" />
        </svg>
        {count > 0 && (
          <span
            className="shell-icon-dot shell-icon-dot--warn"
            data-testid="notif-badge"
            aria-hidden
          />
        )}
      </Link>
      <Link to="/docs/new" className="shell-cta" data-mock-id="shell-header-new-doc">
        + Nouveau document
      </Link>
    </header>
  )
}
