// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import {
  formatNotificationMessage,
  listNotifications,
  markNotificationRead,
  notificationResourceLink,
  type NotificationItem,
} from '../lib/notifications'

/** Horloge injectable (tests visuels : `Date.now` figé). */
function nowDate(): Date {
  return new Date(Date.now())
}

function startOfDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}

function dayGroupLabel(iso: string, now = nowDate()): string {
  const diffDays = Math.round((startOfDay(now) - startOfDay(new Date(iso))) / 86_400_000)
  if (diffDays <= 0) return "Aujourd'hui"
  if (diffDays === 1) return 'Hier'
  if (diffDays < 7) return 'Cette semaine'
  return 'Plus ancien'
}

function groupByDay(
  items: NotificationItem[],
  now = nowDate(),
): Array<{ label: string; items: NotificationItem[] }> {
  const order: string[] = []
  const map = new Map<string, NotificationItem[]>()
  for (const n of items) {
    const label = dayGroupLabel(n.createdAt, now)
    if (!map.has(label)) {
      map.set(label, [])
      order.push(label)
    }
    map.get(label)!.push(n)
  }
  return order.map((label) => ({ label, items: map.get(label)! }))
}

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)

/** « Il y a 5 minutes », « Hier à 17:42 », « Lundi à 14:02 » (fr-FR, heure locale). */
export function relativeTimeLabel(iso: string, now = nowDate()): string {
  const at = new Date(iso)
  const diffMs = now.getTime() - at.getTime()
  const hhmm = at.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
  const days = Math.round((startOfDay(now) - startOfDay(at)) / 86_400_000)
  if (diffMs < 60_000) return "À l'instant"
  if (days <= 0) {
    const min = Math.floor(diffMs / 60_000)
    if (min < 60) return `Il y a ${min} minute${min > 1 ? 's' : ''}`
    const h = Math.floor(min / 60)
    return `Il y a ${h} heure${h > 1 ? 's' : ''}`
  }
  if (days === 1) return `Hier à ${hhmm}`
  if (days < 7) return `${cap(at.toLocaleDateString('fr-FR', { weekday: 'long' }))} à ${hhmm}`
  return `${at.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })} à ${hhmm}`
}

const ICON_PROPS = {
  width: 13,
  height: 13,
  viewBox: '0 0 24 24',
  fill: 'none',
  strokeWidth: 2,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  'aria-hidden': true,
}

/** Pastille d'icône par type réellement produit par le backend. */
function typeIcon(type: string): { bg: string; icon: ReactNode } {
  switch (type) {
    case 'approval_chain_exhausted':
      return {
        bg: '#FBF3E4',
        icon: (
          <svg {...ICON_PROPS} stroke="#B7791F">
            <rect x="3" y="11" width="18" height="10" rx="2" />
            <path d="M7 11V7a5 5 0 0 1 10 0v4" />
          </svg>
        ),
      }
    case 'comment_mention':
      return {
        bg: '#EEEDFD',
        icon: (
          <svg {...ICON_PROPS} stroke="#3730E0">
            <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" />
          </svg>
        ),
      }
    case 'pat_expiring':
      return {
        bg: '#FCEEEA',
        icon: (
          <svg {...ICON_PROPS} stroke="#B54708">
            <path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.78 7.78 5.5 5.5 0 0 1 7.78-7.78zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4" />
          </svg>
        ),
      }
    case 'external_reference_first':
      return {
        bg: '#F0EFFC',
        icon: (
          <svg {...ICON_PROPS} stroke="#3730E0">
            <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
            <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
          </svg>
        ),
      }
    default:
      return {
        bg: '#F1EFEA',
        icon: (
          <svg {...ICON_PROPS} stroke="#6B6862">
            <circle cx="12" cy="12" r="9" />
            <line x1="12" y1="8" x2="12" y2="12" />
            <line x1="12" y1="16" x2="12.01" y2="16" />
          </svg>
        ),
      }
  }
}

export function NotificationsPage() {
  const queryClient = useQueryClient()

  const page = useQuery({
    queryKey: ['notifications', 'list'],
    queryFn: () => listNotifications(api, { limit: 50 }),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
  })

  const markRead = useMutation({
    mutationFn: (id: string) => markNotificationRead(api, id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['notifications'] })
    },
  })

  async function onOpen(n: NotificationItem) {
    if (!n.readAt) {
      try {
        await markRead.mutateAsync(n.id)
      } catch {
        // L'erreur s'affiche via markRead.isError ci-dessous ; on n'empêche pas la navigation.
      }
    }
  }

  const now = nowDate()
  const groups = page.data ? groupByDay(page.data.items, now) : []
  let index = -1

  return (
    <main className="mx-auto max-w-[700px] px-6 pt-8 leading-[normal] md:px-10 md:pt-11">
      <div className="mb-6 flex items-center justify-between gap-4">
        <div className="breadcrumb gap-[7px] text-[13px]" data-mock-id="notif-breadcrumb">
          <Link to="/">Accueil</Link>{' '}
          <span className="text-[#DEDEE1]">→</span>{' '}
          <span className="font-medium text-socle-ink">Notifications</span>
        </div>
        <div className="flex items-center gap-4">
          {page.data && (
            <span className="text-xs text-socle-muted" data-testid="notif-unread-count">
              {page.data.unreadCount} non lue{page.data.unreadCount === 1 ? '' : 's'}
            </span>
          )}
          <Link
            to="/account"
            className="text-[12.5px] font-medium text-socle-accent"
            data-mock-id="notif-prefs"
          >
            Préférences →
          </Link>
        </div>
      </div>

      <div className="mb-6 flex items-baseline justify-between">
        <h1
          className="font-display text-[34px] font-normal leading-[normal] text-socle-ink"
          data-mock-id="notif-title"
        >
          Notifications
        </h1>
        {/* Pas d'API « tout marquer comme lu » → placeholder désactivé (NOT_IMPLEMENTED notif-read-all). */}
        <button
          type="button"
          disabled
          title="Bientôt disponible"
          data-mock-id="notif-read-all"
          data-visual-mask="notif-read-all"
          className="cursor-not-allowed border-0 bg-transparent p-0 text-[12.5px] font-semibold text-socle-faint"
        >
          Tout marquer comme lu
        </button>
      </div>

      {page.isLoading && <p className="text-socle-muted">Chargement…</p>}
      {page.isError && (
        <p className="text-socle-danger">
          {apiErrorMessage(page.error, 'Impossible de charger les notifications.')}
        </p>
      )}
      {markRead.isError && (
        <p className="mb-4 text-sm text-socle-danger">
          {apiErrorMessage(markRead.error, 'Impossible de marquer comme lue.')}
        </p>
      )}
      {page.isSuccess && page.data.items.length === 0 && (
        <p className="rounded-xl border border-dashed border-[#DEDEE1] px-4 py-10 text-center text-sm text-socle-muted">
          Aucune notification pour le moment.
        </p>
      )}

      <div>
        {groups.map((g, gi) => (
          <section key={g.label} data-mock-id={`notif-section-${gi}`}>
            <div
              className={`mb-[10px] text-[11px] font-semibold uppercase tracking-[0.06em] text-socle-faint ${
                gi === 0 ? '' : 'mt-[22px]'
              }`}
              data-mock-id={`notif-group-${gi}`}
            >
              {g.label}
            </div>
            <ul className="m-0 list-none p-0">
              {g.items.map((n) => {
                index += 1
                const i = index
                const href = notificationResourceLink(n)
                const unread = !n.readAt
                const isApproval = n.type.includes('approval')
                const { bg, icon } = typeIcon(n.type)
                const message = formatNotificationMessage(n)
                return (
                  <li
                    key={n.id}
                    data-mock-id={`notif-item-${i}`}
                    className={`group relative flex gap-3 rounded-[10px] px-[10px] py-3 ${
                      unread ? 'bg-[#FAFAFE]' : 'hover:bg-socle-soft'
                    }`}
                  >
                    {unread && (
                      <span
                        className={`absolute left-0 top-[18px] h-1.5 w-1.5 rounded-full ${
                          isApproval ? 'bg-socle-warn' : 'bg-socle-accent'
                        }`}
                        aria-hidden
                        data-mock-id={`notif-item-${i}-dot`}
                      />
                    )}
                    <div
                      aria-hidden
                      className="ml-[14px] flex h-[30px] w-[30px] shrink-0 items-center justify-center rounded-full"
                      style={{ background: bg }}
                      data-mock-id={`notif-item-${i}-icon`}
                      // Hors approbation : la maquette montre l'avatar de l'auteur (NOT_IMPLEMENTED).
                      data-visual-mask={isApproval ? undefined : 'notif-item-icon'}
                    >
                      {icon}
                    </div>
                    <div className="grow">
                      <div
                        className={`text-[13.5px] ${unread ? 'text-socle-ink' : 'text-socle-slate'}`}
                        data-mock-id={`notif-item-${i}-text`}
                      >
                        {href ? (
                          <Link
                            to={href}
                            onClick={() => void onOpen(n)}
                            className="after:absolute after:inset-0 after:rounded-[10px]"
                          >
                            {message}
                          </Link>
                        ) : (
                          message
                        )}
                      </div>
                      <div
                        className="mt-1 text-[11.5px] text-socle-faint"
                        data-mock-id={`notif-item-${i}-time`}
                      >
                        {relativeTimeLabel(n.createdAt, now)}
                      </div>
                    </div>
                    {unread && (
                      <button
                        type="button"
                        disabled={markRead.isPending}
                        onClick={() => markRead.mutate(n.id)}
                        className="absolute right-2 top-2 z-10 rounded-md bg-white px-1.5 py-0.5 text-xs text-socle-slate opacity-0 underline-offset-4 hover:underline focus:opacity-100 group-hover:opacity-100 disabled:opacity-60"
                      >
                        Marquer comme lue
                      </button>
                    )}
                  </li>
                )
              })}
            </ul>
          </section>
        ))}
      </div>
    </main>
  )
}
