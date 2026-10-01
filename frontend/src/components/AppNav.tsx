// SPDX-License-Identifier: AGPL-3.0-or-later
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../auth/AuthProvider'
import { api } from '../lib/api'
import { SocleRole } from '../lib/auth'
import { listNotifications } from '../lib/notifications'

/** Navigation globale — barre haute alignée maquettes (logo indigo + onglets). */
export function AppNav() {
  const { authenticated, me, logout, organizationName } = useAuth()

  const unread = useQuery({
    queryKey: ['notifications', 'unread-count'],
    queryFn: () => listNotifications(api, { unreadOnly: true, limit: 1 }),
    enabled: authenticated,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
  })

  if (!authenticated) return null

  const count = unread.data?.unreadCount ?? 0

  return (
    <header className="sticky top-0 z-10 border-b border-socle-line bg-white">
      <div className="mx-auto flex h-[60px] max-w-5xl items-center justify-between gap-4 px-6 md:px-10">
        <div className="flex min-w-0 items-center gap-6">
          <Link to="/" className="flex shrink-0 items-center gap-2">
            <span className="block h-[22px] w-[22px] rounded-md bg-socle-accent" aria-hidden />
            <span className="text-[14.5px] font-bold tracking-tight text-socle-ink">{organizationName}</span>
          </Link>
          <nav className="flex flex-wrap items-center gap-5 text-[13px] font-medium">
            <Link to="/docs" className="text-socle-muted hover:text-socle-ink">
              Documents
            </Link>
            <Link to="/search" className="text-socle-muted hover:text-socle-ink">
              Recherche
            </Link>
            <Link to="/spaces" className="text-socle-muted hover:text-socle-ink">
              Espaces
            </Link>
            <Link to="/team" className="text-socle-muted hover:text-socle-ink">
              Équipes
            </Link>
            <Link to="/approvals" className="text-socle-muted hover:text-socle-ink">
              Approbations
            </Link>
            <Link to="/trash" className="text-socle-muted hover:text-socle-ink">
              Corbeille
            </Link>
            <Link to="/admin/workflows" className="text-socle-muted hover:text-socle-ink">
              Workflows
            </Link>
            {me?.roles?.includes(SocleRole.ADMINISTRATEUR_SYSTEME) && (
              <Link to="/admin/templates" className="text-socle-muted hover:text-socle-ink">
                Modèles
              </Link>
            )}
            {me?.roles?.includes(SocleRole.INTEGRATEUR) && (
              <Link to="/integrations" className="text-socle-muted hover:text-socle-ink">
                Intégrations
              </Link>
            )}
            {me?.roles?.includes(SocleRole.AUDITEUR) && (
              <Link to="/audit" className="text-socle-muted hover:text-socle-ink">
                Audit
              </Link>
            )}
          </nav>
        </div>
        <div className="flex items-center gap-3">
          <Link
            to="/docs/new"
            className="rounded-lg bg-socle-accent px-3 py-1.5 text-[13px] font-semibold text-white hover:bg-socle-accent-hover"
          >
            Nouveau
          </Link>
          <Link
            to="/notifications"
            className="relative flex h-[34px] w-[34px] items-center justify-center rounded-lg border border-socle-line text-[#43434A] hover:bg-[#F5F5F7]"
            aria-label={count > 0 ? `Notifications, ${count} non lues` : 'Notifications'}
          >
            <svg
              width="15"
              height="15"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden
            >
              <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
              <path d="M13.73 21a2 2 0 0 1-3.46 0" />
            </svg>
            {count > 0 && (
              <span
                data-testid="notif-badge"
                className="absolute -right-1.5 -top-1.5 inline-flex min-w-[1.15rem] items-center justify-center rounded-full bg-socle-danger px-1 text-[10px] font-semibold leading-4 text-white"
              >
                {count > 99 ? '99+' : count}
              </span>
            )}
          </Link>
          <div className="hidden items-center gap-3 text-xs text-socle-slate sm:flex">
            {me && <span>{me.displayName}</span>}
            <button
              type="button"
              onClick={() => void logout()}
              className="underline-offset-4 hover:underline"
            >
              Déconnexion
            </button>
          </div>
        </div>
      </div>
    </header>
  )
}
