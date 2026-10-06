// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../auth/AuthProvider'
import { api } from '../lib/api'
import { SocleRole } from '../lib/auth'
import { apiErrorMessage } from '../lib/approvals'
import {
  deliveryStatusBadgeClass,
  deliveryStatusLabel,
  formatDeliveryTimestamp,
  httpStatusLabel,
  listWebhookDeliveries,
  type WebhookDelivery,
  type WebhookDeliveryStatus,
} from '../lib/webhooks'

type StatusTab = '' | 'failed' | 'retrying'

const TABS: Array<{ label: string; value: StatusTab }> = [
  { label: 'Tout', value: '' },
  { label: 'Échecs', value: 'failed' },
  { label: 'En attente de réessai', value: 'retrying' },
]

export function WebhookDeliveriesPage() {
  const { me } = useAuth()
  const roles = me?.roles ?? []
  const canRead =
    roles.includes(SocleRole.AUDITEUR) || roles.includes(SocleRole.INTEGRATEUR)

  const [status, setStatus] = useState<StatusTab>('')

  const page = useQuery({
    queryKey: ['webhook-deliveries', status],
    queryFn: () =>
      listWebhookDeliveries(api, {
        status: (status || undefined) as WebhookDeliveryStatus | undefined,
        limit: 50,
        offset: 0,
      }),
    enabled: canRead,
  })

  if (!canRead) {
    return (
      <main className="page-shell">
        <div className="breadcrumb mb-6">
          <Link to="/">Accueil</Link>
          <span className="text-[#DEDEE1]">→</span>
          <span className="font-medium text-socle-ink">Historique des livraisons</span>
        </div>
        <h1 className="serif-title">Historique des livraisons</h1>
        <p className="mt-4 text-socle-danger">
          Lecture réservée aux rôles <strong>AUDITEUR</strong> (supervision) ou{' '}
          <strong>INTEGRATEUR</strong> (configuration) — pas au CONTRIBUTEUR seul.
        </p>
      </main>
    )
  }

  const total = page.data?.total ?? 0
  const endpoints = [...new Set((page.data?.items ?? []).map((d) => d.endpointUrl))]

  return (
    <main className="page-shell-wide">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div className="breadcrumb">
          <Link to="/">Accueil</Link>
          <span className="text-[#DEDEE1]">→</span>
          <span className="text-socle-muted">Intégrations &amp; API</span>
          <span className="text-[#DEDEE1]">→</span>
          <span className="font-medium text-socle-ink">Historique des livraisons</span>
        </div>
      </div>

      <h1 className="serif-title">Historique des livraisons</h1>
      <p className="mt-1.5 text-sm text-socle-muted">
        Tentatives de livraison webhook — lecture seule
        {page.isSuccess ? ` · ${total} résultat${total === 1 ? '' : 's'}` : ''}
      </p>
      {endpoints.length === 1 && (
        <p className="mt-1 font-mono text-[12.5px] text-socle-muted">{endpoints[0]}</p>
      )}

      <div className="mt-7 flex flex-wrap items-center gap-5 border-b border-socle-line">
        {TABS.map((tab) => {
          const active = status === tab.value
          return (
            <button
              key={tab.value || 'all'}
              type="button"
              onClick={() => setStatus(tab.value)}
              className={`pb-2 text-[13px] ${
                active
                  ? 'border-b-2 border-socle-accent font-semibold text-socle-ink'
                  : 'text-socle-muted hover:text-socle-ink'
              }`}
            >
              {tab.label}
              {active && page.isSuccess ? ` · ${total}` : ''}
            </button>
          )
        })}
      </div>

      {page.isLoading && <p className="mt-8 text-socle-muted">Chargement…</p>}
      {page.isError && (
        <p className="mt-8 text-socle-danger">
          {apiErrorMessage(page.error, 'Impossible de charger les livraisons.')}
        </p>
      )}

      {page.isSuccess && page.data.items.length === 0 && (
        <p className="mt-8 rounded-xl border border-dashed border-[#DEDEE1] px-4 py-10 text-center text-sm text-socle-muted">
          Aucune livraison pour ce filtre.
        </p>
      )}

      {page.isSuccess && page.data.items.length > 0 && (
        <div className="mt-4 overflow-x-auto rounded-[10px] border border-socle-line">
          <div className="flex min-w-[720px] border-b border-socle-line bg-[#FAFAFB] px-4 py-2.5 text-[11px] font-semibold uppercase tracking-[0.05em] text-socle-faint">
            <div className="w-[18%]">Événement</div>
            <div className="w-[22%]">Endpoint</div>
            <div className="w-[14%]">Horodatage</div>
            <div className="w-[10%]">Tentative</div>
            <div className="w-[14%]">Statut</div>
            <div className="w-[12%]">Réponse</div>
            <div className="w-[10%]">Date livraison</div>
          </div>
          <ul className="min-w-[720px]">
            {page.data.items.map((d, idx) => (
              <DeliveryRow
                key={d.id}
                delivery={d}
                last={idx === page.data.items.length - 1}
              />
            ))}
          </ul>
        </div>
      )}

      <div className="mt-6 mb-8 flex items-start gap-2.5 rounded-[10px] border border-socle-line px-[18px] py-3.5">
        <svg
          width="15"
          height="15"
          viewBox="0 0 24 24"
          fill="none"
          stroke="#9B9BA1"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="mt-0.5 shrink-0"
          aria-hidden
        >
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="16" x2="12" y2="12" />
          <line x1="12" y1="8" x2="12.01" y2="8" />
        </svg>
        <p className="text-[12.5px] leading-relaxed text-[#6B6B72]">
          Les livraisons échouées sont réessayées automatiquement par le worker. La relance manuelle
          (« Relancer ») n&apos;est pas encore exposée par l&apos;API — voir backlog produit.
        </p>
      </div>
    </main>
  )
}

function DeliveryRow({ delivery: d, last }: { delivery: WebhookDelivery; last: boolean }) {
  const failed = d.status === 'failed'
  const stamp = d.deliveredAt ?? d.createdAt
  return (
    <li
      className={`flex items-center px-4 py-3 text-[13px] ${
        last ? '' : 'border-b border-[#F5F5F7]'
      } ${failed ? 'bg-[#FDF6F0]' : 'hover:bg-[#FAFAFB]'}`}
    >
      <div className="w-[18%] truncate font-mono text-socle-ink">{d.eventType}</div>
      <div className="w-[22%] truncate font-mono text-[11.5px] text-socle-muted" title={d.endpointUrl}>
        {d.endpointUrl}
      </div>
      <div className="w-[14%] text-socle-muted">{formatDeliveryTimestamp(stamp)}</div>
      <div
        className={`w-[10%] ${
          failed ? 'font-semibold text-socle-danger' : 'text-[#6B6B72]'
        }`}
      >
        {d.attemptCount}
      </div>
      <div className="w-[14%]">
        <span
          className={`inline-block rounded-[5px] px-1.5 py-0.5 text-[11.5px] font-semibold ${deliveryStatusBadgeClass(d.status)}`}
        >
          {deliveryStatusLabel(d.status)}
        </span>
      </div>
      <div
        className={`w-[12%] font-mono text-[11.5px] ${
          failed ? 'text-socle-danger' : 'text-socle-muted'
        }`}
      >
        {httpStatusLabel(d.lastResponseCode, d.status)}
      </div>
      <div className="w-[10%] text-[11.5px] text-socle-muted">
        {d.deliveredAt ? formatDeliveryTimestamp(d.deliveredAt) : '—'}
      </div>
    </li>
  )
}
