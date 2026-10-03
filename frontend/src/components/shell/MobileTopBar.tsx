// SPDX-License-Identifier: AGPL-3.0-or-later
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthProvider'
import { api } from '../../lib/api'
import { listNotifications } from '../../lib/notifications'

type Props = {
  onOpenMenu: () => void
}

export function MobileTopBar({ onOpenMenu }: Props) {
  const { authenticated } = useAuth()
  const unread = useQuery({
    queryKey: ['notifications', 'unread-count'],
    queryFn: () => listNotifications(api, { unreadOnly: true, limit: 1 }),
    enabled: authenticated,
    refetchInterval: 30_000,
  })
  const count = unread.data?.unreadCount ?? 0

  return (
    <div className="shell-mobile-top shell-mobile-only" data-mock-id="mobile-topbar">
      <button
        type="button"
        className="shell-mobile-icon"
        aria-label="Ouvrir le menu"
        onClick={onOpenMenu}
        data-mock-id="mobile-menu-btn"
        data-testid="mobile-menu-btn"
      >
        <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="#0E0E10" strokeWidth="2" strokeLinecap="round" aria-hidden>
          <line x1="3" y1="6" x2="21" y2="6" />
          <line x1="3" y1="12" x2="21" y2="12" />
          <line x1="3" y1="18" x2="21" y2="18" />
        </svg>
      </button>
      <div className="shell-mobile-brand" data-mock-id="mobile-brand">
        Socle
      </div>
      <Link
        to="/notifications"
        className="shell-mobile-icon"
        aria-label={count > 0 ? `Notifications, ${count} non lues` : 'Notifications'}
        data-mock-id="mobile-notifications"
      >
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#43434A" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
          <path d="M13.73 21a2 2 0 0 1-3.46 0" />
        </svg>
        {count > 0 && (
          <span
            className="shell-icon-dot shell-icon-dot--warn"
            style={{ top: 7, right: 8 }}
            aria-hidden
          />
        )}
      </Link>
    </div>
  )
}
