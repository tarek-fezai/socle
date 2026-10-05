// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Link, useLocation } from 'react-router-dom'

const tabs = [
  {
    to: '/',
    label: 'Accueil',
    match: (p: string) => p === '/',
    icon: (
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
        <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
      </svg>
    ),
  },
  {
    to: '/spaces',
    label: 'Espaces',
    match: (p: string) => p.startsWith('/spaces') || p.startsWith('/folders'),
    icon: (
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
        <rect x="3" y="3" width="7" height="7" rx="1.5" />
        <rect x="14" y="3" width="7" height="7" rx="1.5" />
        <rect x="3" y="14" width="7" height="7" rx="1.5" />
        <rect x="14" y="14" width="7" height="7" rx="1.5" />
      </svg>
    ),
  },
  {
    to: '/favorites',
    label: 'Favoris',
    match: (p: string) => p.startsWith('/favorites'),
    icon: (
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
        <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
      </svg>
    ),
  },
  {
    to: '/team',
    label: 'Compte',
    match: (p: string) => p.startsWith('/team'),
    icon: (
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
        <circle cx="12" cy="8" r="4" />
        <path d="M4 21c0-4 4-6 8-6s8 2 8 6" />
      </svg>
    ),
  },
] as const

export function MobileTabBar() {
  const { pathname } = useLocation()
  return (
    <nav className="shell-tabbar shell-mobile-only" data-mock-id="mobile-tabbar" aria-label="Navigation mobile">
      {tabs.map((t) => {
        const active = t.match(pathname)
        return (
          <Link key={t.to} to={t.to} className={`shell-tab${active ? ' active' : ''}`}>
            {t.icon}
            <span data-mock-id={t.to === '/' ? 'mobile-tab-home' : undefined}>{t.label}</span>
          </Link>
        )
      })}
    </nav>
  )
}
