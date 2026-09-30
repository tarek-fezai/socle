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

function dayGroupLabel(iso: string, now = new Date()): string {
  const d = new Date(iso)
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const startThat = new Date(d.getFullYear(), d.getMonth(), d.getDate())
  const diffDays = Math.round((startToday.getTime() - startThat.getTime()) / 86_400_000)
  if (diffDays === 0) return "Aujourd'hui"
  if (diffDays === 1) return 'Hier'
  if (diffDays < 7) return 'Cette semaine'
  return 'Plus ancien'
}

function groupByDay(items: NotificationItem[]): Array<{ label: string; items: NotificationItem[] }> {
  const order: string[] = []
  const map = new Map<string, NotificationItem[]>()
  for (const n of items) {
    const label = dayGroupLabel(n.createdAt)
    if (!map.has(label)) {
      map.set(label, [])
      order.push(label)
    }
    map.get(label)!.push(n)
  }
  return order.map((label) => ({ label, items: map.get(label)! }))
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

  const groups = page.data ? groupByDay(page.data.items) : []

  return (
    <main className="page-shell max-w-[620px]">
      <div className="breadcrumb mb-6">
        <Link to="/">Accueil</Link>
        <span className="text-[#DEDEE1]">→</span>
        <span className="font-medium text-socle-ink">Notifications</span>
      </div>

      <div className="mb-6 flex flex-wrap items-baseline justify-between gap-3">
        <h1 className="serif-title">Notifications</h1>
        {page.data && (
          <p className="text-sm text-socle-muted">
            {page.data.unreadCount} non lue{page.data.unreadCount === 1 ? '' : 's'}
          </p>
        )}
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

      <div className="space-y-6">
        {groups.map((g) => (
          <section key={g.label}>
            <div className="section-label mb-2">{g.label}</div>
            <ul className="space-y-1">
              {g.items.map((n) => {
                const href = notificationResourceLink(n)
                const unread = !n.readAt
                const isApproval = n.type.includes('approval')
                return (
                  <li
                    key={n.id}
                    className={`relative rounded-xl px-3 py-3 pl-5 ${
                      unread ? 'bg-[#FAFAFE]' : 'hover:bg-socle-soft'
                    }`}
                  >
                    {unread && (
                      <span
                        className={`absolute left-1.5 top-5 h-1.5 w-1.5 rounded-full ${
                          isApproval ? 'bg-socle-warn' : 'bg-socle-accent'
                        }`}
                        aria-hidden
                      />
                    )}
                    <p className={`text-[13.5px] ${unread ? 'text-socle-ink' : 'text-socle-slate'}`}>
                      {formatNotificationMessage(n)}
                    </p>
                    <p className="mt-1 text-[11.5px] text-socle-faint">
                      {n.type}
                      {' · '}
                      {new Date(n.createdAt).toLocaleString('fr-FR')}
                      {unread ? ' · non lue' : ' · lue'}
                    </p>
                    <div className="mt-2 flex flex-wrap gap-3 text-sm">
                      {href && (
                        <Link
                          to={href}
                          onClick={() => void onOpen(n)}
                          className="font-semibold text-socle-accent underline-offset-4 hover:underline"
                        >
                          Ouvrir
                        </Link>
                      )}
                      {unread && (
                        <button
                          type="button"
                          disabled={markRead.isPending}
                          onClick={() => markRead.mutate(n.id)}
                          className="text-socle-slate underline-offset-4 hover:underline disabled:opacity-60"
                        >
                          Marquer comme lue
                        </button>
                      )}
                    </div>
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
