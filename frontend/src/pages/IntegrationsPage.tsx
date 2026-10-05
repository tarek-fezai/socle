// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../auth/AuthProvider'
import { api } from '../lib/api'
import { SocleRole } from '../lib/auth'
import { apiErrorMessage } from '../lib/approvals'
import {
  createSiemConnector,
  createWebhookEndpoint,
  deleteSiemConnector,
  deleteWebhookEndpoint,
  listSiemConnectors,
  listWebhookEndpoints,
  providerLabel,
  secretFieldForProvider,
  siemStatusLabel,
  testSiemConnector,
  updateSiemConnector,
  updateWebhookEndpoint,
  type SiemConnector,
  type SiemProvider,
  type WebhookEndpoint,
} from '../lib/integrations'

const EVENT_HINT = 'document.published, document.approval_requested, tag.added'

export function IntegrationsPage() {
  const { me } = useAuth()
  const isIntegrateur = (me?.roles ?? []).includes(SocleRole.INTEGRATEUR)

  if (!isIntegrateur) {
    return (
      <main className="page-shell">
        <div className="breadcrumb mb-6">
          <Link to="/">Accueil</Link>
          <span className="text-[#DEDEE1]">→</span>
          <span className="font-medium text-socle-ink">Intégrations &amp; API</span>
        </div>
        <h1 className="serif-title">Intégrations &amp; API</h1>
        <p className="mt-4 text-socle-danger">
          Accès réservé au rôle <strong>INTEGRATEUR</strong>. La configuration des destinations
          SIEM / webhook est séparée de la supervision du journal d&apos;audit (rôle{' '}
          <strong>AUDITEUR</strong>) — séparation des tâches IAM/IGA.
        </p>
      </main>
    )
  }

  return (
    <main className="page-shell max-w-[780px]">
      <div className="breadcrumb mb-6">
        <Link to="/">Accueil</Link>
        <span className="text-[#DEDEE1]">→</span>
        <span className="font-medium text-socle-ink">Intégrations &amp; API</span>
      </div>

      <h1 className="serif-title">Intégrations &amp; API</h1>
      <p className="mt-1.5 text-sm text-socle-muted">
        Connecteurs SIEM et endpoints webhook — configuration sans écriture directe en base.
      </p>

      <SiemSection />
      <WebhookSection />
    </main>
  )
}

function SiemSection() {
  const qc = useQueryClient()
  const list = useQuery({
    queryKey: ['siem-connectors'],
    queryFn: () => listSiemConnectors(api),
  })
  const [showForm, setShowForm] = useState(false)
  const [editing, setEditing] = useState<SiemConnector | null>(null)
  const [testMsg, setTestMsg] = useState<{ id: string; ok: boolean; text: string } | null>(null)

  const create = useMutation({
    mutationFn: (body: { provider: SiemProvider; config: Record<string, unknown> }) =>
      createSiemConnector(api, body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['siem-connectors'] })
      setShowForm(false)
    },
  })
  const update = useMutation({
    mutationFn: ({ id, body }: { id: string; body: Parameters<typeof updateSiemConnector>[2] }) =>
      updateSiemConnector(api, id, body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['siem-connectors'] })
      setEditing(null)
    },
  })
  const remove = useMutation({
    mutationFn: (id: string) => deleteSiemConnector(api, id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['siem-connectors'] }),
  })
  const test = useMutation({
    mutationFn: (id: string) => testSiemConnector(api, id),
    onSuccess: (res, id) => {
      setTestMsg({ id, ok: res.ok, text: res.message || (res.ok ? 'Connexion OK' : 'Échec') })
    },
    onError: (err, id) => {
      setTestMsg({ id, ok: false, text: apiErrorMessage(err, 'Test impossible') })
    },
  })

  return (
    <section className="mt-10">
      <div className="mb-2.5 flex items-center justify-between gap-3">
        <div className="section-label">Diffusion du journal d&apos;audit vers un SIEM</div>
        <Link to="/audit" className="text-xs font-semibold text-socle-accent">
          Voir le journal →
        </Link>
      </div>
      <p className="mb-3.5 text-[12.5px] leading-relaxed text-socle-muted">
        Diffuse en continu les événements du journal d&apos;audit vers votre outil de supervision.
        Le statut passe à « Connecté » uniquement après activation explicite (pas à la création ni
        au simple test).
      </p>

      {list.isLoading && <p className="text-sm text-socle-muted">Chargement…</p>}
      {list.isError && (
        <p className="text-sm text-socle-danger">
          {apiErrorMessage(list.error, 'Impossible de charger les connecteurs.')}
        </p>
      )}

      {list.isSuccess && (
        <ul className="overflow-hidden rounded-[10px] border border-socle-line">
          {list.data.length === 0 && (
            <li className="px-4 py-6 text-center text-sm text-socle-muted">
              Aucun connecteur SIEM configuré.
            </li>
          )}
          {list.data.map((c, idx) => (
            <li
              key={c.id}
              className={`flex flex-wrap items-center gap-3 px-4 py-3.5 ${
                idx < list.data.length - 1 ? 'border-b border-[#F5F5F7]' : ''
              }`}
            >
              <div className="min-w-0 flex-grow">
                <div className="text-[13.5px] font-semibold text-socle-ink">
                  {providerLabel(c.provider)}
                </div>
                <div className="mt-0.5 truncate font-mono text-[11.5px] text-socle-muted">
                  {String(c.config.endpoint ?? '—')}
                  {c.config.format ? ` · format ${String(c.config.format)}` : ''}
                </div>
                {testMsg?.id === c.id && (
                  <p
                    className={`mt-1 text-[12px] font-medium ${
                      testMsg.ok ? 'text-socle-success' : 'text-socle-danger'
                    }`}
                    data-testid={`siem-test-${c.id}`}
                  >
                    {testMsg.ok ? '✓ ' : '✕ '}
                    {testMsg.text}
                  </p>
                )}
              </div>
              <StatusDot status={c.status} />
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  className="text-[12.5px] font-semibold text-socle-accent"
                  disabled={test.isPending}
                  onClick={() => {
                    setTestMsg(null)
                    test.mutate(c.id)
                  }}
                >
                  Tester
                </button>
                {c.status !== 'connected' && (
                  <button
                    type="button"
                    className="text-[12.5px] font-semibold text-socle-accent"
                    onClick={() => update.mutate({ id: c.id, body: { status: 'connected' } })}
                  >
                    Activer
                  </button>
                )}
                {c.status === 'connected' && (
                  <button
                    type="button"
                    className="text-[12.5px] font-semibold text-socle-muted"
                    onClick={() => update.mutate({ id: c.id, body: { status: 'disconnected' } })}
                  >
                    Désactiver
                  </button>
                )}
                <button
                  type="button"
                  className="text-[12.5px] font-semibold text-socle-accent"
                  onClick={() => {
                    setEditing(c)
                    setShowForm(false)
                  }}
                >
                  Configurer
                </button>
                <button
                  type="button"
                  className="text-[12px] font-medium text-socle-danger"
                  onClick={() => {
                    if (window.confirm('Supprimer ce connecteur ?')) remove.mutate(c.id)
                  }}
                >
                  Supprimer
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {!showForm && !editing && (
        <button
          type="button"
          className="btn-primary mt-4"
          onClick={() => {
            setShowForm(true)
            setEditing(null)
          }}
        >
          + Ajouter un connecteur SIEM
        </button>
      )}

      {(showForm || editing) && (
        <SiemForm
          initial={editing}
          pending={create.isPending || update.isPending}
          error={
            create.isError
              ? apiErrorMessage(create.error, 'Création impossible')
              : update.isError
                ? apiErrorMessage(update.error, 'Mise à jour impossible')
                : null
          }
          onCancel={() => {
            setShowForm(false)
            setEditing(null)
          }}
          onSubmit={(payload) => {
            if (editing) {
              update.mutate({
                id: editing.id,
                body: { config: payload.config },
              })
            } else {
              create.mutate(payload)
            }
          }}
        />
      )}
    </section>
  )
}

function SiemForm({
  initial,
  pending,
  error,
  onCancel,
  onSubmit,
}: {
  initial: SiemConnector | null
  pending: boolean
  error: string | null
  onCancel: () => void
  onSubmit: (payload: { provider: SiemProvider; config: Record<string, unknown> }) => void
}) {
  const [provider, setProvider] = useState<SiemProvider>(
    (initial?.provider as SiemProvider) || 'splunk',
  )
  const [endpoint, setEndpoint] = useState(String(initial?.config.endpoint ?? ''))
  const [format, setFormat] = useState(String(initial?.config.format ?? ''))
  const [secret, setSecret] = useState('')
  const secretMeta = secretFieldForProvider(provider)

  function submit(e: FormEvent) {
    e.preventDefault()
    const config: Record<string, unknown> = { endpoint: endpoint.trim() }
    if (format.trim()) config.format = format.trim()
    if (secret.trim()) config[secretMeta.key] = secret.trim()
    onSubmit({ provider, config })
    setSecret('') // ne pas garder le secret en mémoire formulaire
  }

  return (
    <form
      onSubmit={submit}
      className="mt-4 space-y-3 rounded-xl border border-socle-line bg-white p-4"
      autoComplete="off"
    >
      <h3 className="text-sm font-semibold text-socle-ink">
        {initial ? 'Configurer le connecteur' : 'Nouveau connecteur SIEM'}
      </h3>
      <label className="block text-xs text-socle-muted">
        Provider
        <select
          className="field-input mt-1"
          value={provider}
          disabled={!!initial}
          onChange={(e) => setProvider(e.target.value as SiemProvider)}
        >
          <option value="splunk">Splunk</option>
          <option value="datadog">Datadog</option>
          <option value="sentinel">Microsoft Sentinel</option>
        </select>
      </label>
      <label className="block text-xs text-socle-muted">
        Endpoint
        <input
          className="field-input mt-1 font-mono"
          value={endpoint}
          onChange={(e) => setEndpoint(e.target.value)}
          required
          placeholder="https://…"
        />
      </label>
      <label className="block text-xs text-socle-muted">
        Format (optionnel)
        <input
          className="field-input mt-1"
          value={format}
          onChange={(e) => setFormat(e.target.value)}
          placeholder="hec | datadog | sentinel | json"
        />
      </label>
      <label className="block text-xs text-socle-muted">
        {secretMeta.label}
        {initial && (
          <span className="ml-1 font-normal text-socle-faint">
            (laisser vide pour conserver{' '}
            {String(initial.config[`${secretMeta.key}_prefix`] ?? '••••')})
          </span>
        )}
        <input
          className="field-input mt-1 font-mono"
          type="password"
          value={secret}
          onChange={(e) => setSecret(e.target.value)}
          required={!initial}
          autoComplete="new-password"
          placeholder={initial ? '••••••••' : ''}
        />
      </label>
      {error && <p className="text-sm text-socle-danger">{error}</p>}
      <div className="flex gap-2">
        <button type="submit" className="btn-primary" disabled={pending}>
          {pending ? 'Enregistrement…' : 'Enregistrer'}
        </button>
        <button type="button" className="btn-ghost" onClick={onCancel}>
          Annuler
        </button>
      </div>
    </form>
  )
}

function WebhookSection() {
  const qc = useQueryClient()
  const list = useQuery({
    queryKey: ['webhook-endpoints'],
    queryFn: () => listWebhookEndpoints(api),
  })
  const [showForm, setShowForm] = useState(false)
  const [oneShotSecret, setOneShotSecret] = useState<string | null>(null)

  const create = useMutation({
    mutationFn: (body: { url: string; subscribedEvents: string[]; status?: 'active' | 'disabled' }) =>
      createWebhookEndpoint(api, body),
    onSuccess: (res) => {
      setOneShotSecret(res.secret)
      setShowForm(false)
      void qc.invalidateQueries({ queryKey: ['webhook-endpoints'] })
    },
  })
  const update = useMutation({
    mutationFn: ({
      id,
      body,
    }: {
      id: string
      body: Parameters<typeof updateWebhookEndpoint>[2]
    }) => updateWebhookEndpoint(api, id, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['webhook-endpoints'] }),
  })
  const remove = useMutation({
    mutationFn: (id: string) => deleteWebhookEndpoint(api, id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['webhook-endpoints'] }),
  })

  return (
    <section className="mt-12 mb-16">
      <div className="mb-2.5 flex items-center justify-between gap-3">
        <div className="section-label">Webhooks</div>
        <Link
          to="/integrations/webhooks/deliveries"
          className="text-xs font-semibold text-socle-accent"
        >
          Voir l&apos;historique des livraisons →
        </Link>
      </div>

      {oneShotSecret && (
        <div
          className="mb-4 rounded-xl border border-[#C7C6F5] bg-socle-mist px-4 py-3"
          data-testid="webhook-secret-oneshot"
        >
          <p className="text-sm font-semibold text-socle-ink">
            Secret webhook — copiez-le maintenant
          </p>
          <p className="mt-1 text-[12.5px] text-socle-slate">
            Il ne sera plus jamais affiché. Stockez-le dans votre gestionnaire de secrets.
          </p>
          <code className="mt-2 block break-all font-mono text-[13px] text-socle-accent">
            {oneShotSecret}
          </code>
          <button
            type="button"
            className="btn-ghost mt-3"
            onClick={() => setOneShotSecret(null)}
          >
            J&apos;ai copié le secret
          </button>
        </div>
      )}

      {list.isLoading && <p className="text-sm text-socle-muted">Chargement…</p>}
      {list.isError && (
        <p className="text-sm text-socle-danger">
          {apiErrorMessage(list.error, 'Impossible de charger les webhooks.')}
        </p>
      )}

      {list.isSuccess && (
        <ul className="overflow-hidden rounded-[10px] border border-socle-line">
          {list.data.length === 0 && (
            <li className="px-4 py-6 text-center text-sm text-socle-muted">
              Aucun endpoint webhook.
            </li>
          )}
          {list.data.map((ep, idx) => (
            <WebhookRow
              key={ep.id}
              ep={ep}
              last={idx === list.data.length - 1}
              onToggle={() =>
                update.mutate({
                  id: ep.id,
                  body: { status: ep.status === 'active' ? 'disabled' : 'active' },
                })
              }
              onDelete={() => {
                if (window.confirm('Supprimer cet endpoint ?')) remove.mutate(ep.id)
              }}
            />
          ))}
        </ul>
      )}

      {!showForm && (
        <button type="button" className="btn-primary mt-4" onClick={() => setShowForm(true)}>
          + Ajouter un endpoint webhook
        </button>
      )}

      {showForm && (
        <WebhookForm
          pending={create.isPending}
          error={create.isError ? apiErrorMessage(create.error, 'Création impossible') : null}
          onCancel={() => setShowForm(false)}
          onSubmit={(body) => create.mutate(body)}
        />
      )}
    </section>
  )
}

function WebhookRow({
  ep,
  last,
  onToggle,
  onDelete,
}: {
  ep: WebhookEndpoint
  last: boolean
  onToggle: () => void
  onDelete: () => void
}) {
  return (
    <li
      className={`flex flex-wrap items-center justify-between gap-3 px-4 py-3.5 ${
        last ? '' : 'border-b border-[#F5F5F7]'
      }`}
    >
      <div className="min-w-0">
        <div className="text-[13.5px] font-semibold text-socle-ink">
          {ep.subscribedEvents.join(', ')}
        </div>
        <div className="mt-0.5 truncate font-mono text-[11.5px] text-socle-muted">{ep.url}</div>
      </div>
      <div className="flex items-center gap-3">
        <span
          className={`text-xs font-semibold ${
            ep.status === 'active' ? 'text-socle-success' : 'text-socle-muted'
          }`}
        >
          {ep.status === 'active' ? 'Actif' : 'Désactivé'}
        </span>
        <Link
          to="/integrations/webhooks/deliveries"
          className="text-[12.5px] font-semibold text-socle-accent"
        >
          Voir l&apos;historique →
        </Link>
        <button type="button" className="text-[12.5px] font-semibold text-socle-accent" onClick={onToggle}>
          {ep.status === 'active' ? 'Désactiver' : 'Activer'}
        </button>
        <button type="button" className="text-[12px] font-medium text-socle-danger" onClick={onDelete}>
          Supprimer
        </button>
      </div>
    </li>
  )
}

function WebhookForm({
  pending,
  error,
  onCancel,
  onSubmit,
}: {
  pending: boolean
  error: string | null
  onCancel: () => void
  onSubmit: (body: { url: string; subscribedEvents: string[] }) => void
}) {
  const [url, setUrl] = useState('')
  const [events, setEvents] = useState(EVENT_HINT)

  function submit(e: FormEvent) {
    e.preventDefault()
    const subscribedEvents = events
      .split(/[,\s]+/)
      .map((s) => s.trim())
      .filter(Boolean)
    onSubmit({ url: url.trim(), subscribedEvents })
  }

  return (
    <form
      onSubmit={submit}
      className="mt-4 space-y-3 rounded-xl border border-socle-line bg-white p-4"
      autoComplete="off"
    >
      <h3 className="text-sm font-semibold text-socle-ink">Nouvel endpoint webhook</h3>
      <label className="block text-xs text-socle-muted">
        URL
        <input
          className="field-input mt-1 font-mono"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          required
          placeholder="https://hooks.example.com/socle"
        />
      </label>
      <label className="block text-xs text-socle-muted">
        Événements (séparés par des virgules)
        <input
          className="field-input mt-1 font-mono"
          value={events}
          onChange={(e) => setEvents(e.target.value)}
          required
        />
      </label>
      <p className="text-[11.5px] text-socle-faint">
        Un secret sera généré à la création et affiché une seule fois.
      </p>
      {error && <p className="text-sm text-socle-danger">{error}</p>}
      <div className="flex gap-2">
        <button type="submit" className="btn-primary" disabled={pending}>
          {pending ? 'Création…' : 'Créer'}
        </button>
        <button type="button" className="btn-ghost" onClick={onCancel}>
          Annuler
        </button>
      </div>
    </form>
  )
}

function StatusDot({ status }: { status: string }) {
  const color =
    status === 'connected'
      ? 'bg-socle-success text-socle-success'
      : status === 'error'
        ? 'bg-socle-danger text-socle-danger'
        : 'bg-[#C2C2C6] text-socle-muted'
  return (
    <span className={`mr-2 inline-flex items-center gap-1.5 text-xs font-semibold ${color.split(' ')[1]}`}>
      <span className={`h-[5px] w-[5px] rounded-full ${color.split(' ')[0]}`} aria-hidden />
      {siemStatusLabel(status)}
    </span>
  )
}
