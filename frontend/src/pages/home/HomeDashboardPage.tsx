// SPDX-License-Identifier: AGPL-3.0-or-later
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../../lib/api'
import { getHomeDashboard, homeQueryKey } from '../../lib/home'
import { formatFrInteger, formatShortDateFr } from '../../lib/relativeTime'
import { initialsFromName } from '../../components/shell/shellUtils'
import './home.css'

export function HomeDashboardPage() {
  const home = useQuery({
    queryKey: homeQueryKey,
    queryFn: () => getHomeDashboard(api),
  })

  if (home.isLoading) {
    return (
      <div className="home-page">
        <p className="home-subtitle">Chargement…</p>
      </div>
    )
  }

  if (home.isError || !home.data) {
    return (
      <div className="home-page">
        <p className="home-subtitle" role="alert">
          Impossible de charger l&apos;accueil.
        </p>
      </div>
    )
  }

  const d = home.data
  const k = d.kpis
  const reliability =
    k.averageReliabilityPercent == null ? null : Math.round(k.averageReliabilityPercent)

  return (
    <div className="home-page" data-mock-id="home-page">
      <h1 className="home-title" data-mock-id="home-greeting">
        Bonjour, {d.greetingFirstName}
      </h1>
      <p className="home-subtitle" data-mock-id="home-subtitle">
        <span className="home-subtitle-desktop">Voici où en est votre documentation aujourd&apos;hui.</span>
        <span className="home-subtitle-mobile">Voici où en est votre documentation.</span>
      </p>

      <div className="home-kpis" data-mock-id="home-kpis">
        <div className="home-kpi" data-mock-id="home-kpi-published">
          <div className="home-kpi-label">Documents publiés</div>
          <div className="home-kpi-value">{formatFrInteger(k.publishedDocuments)}</div>
        </div>
        <div className="home-kpi" data-mock-id="home-kpi-pending">
          <div className="home-kpi-label">
            <span className="home-kpi-label-desktop">En attente d&apos;approbation</span>
            <span className="home-kpi-label-mobile">En attente</span>
          </div>
          <div className="home-kpi-value home-kpi-value--warn">{formatFrInteger(k.pendingApprovals)}</div>
        </div>
        <div className="home-kpi home-kpi--desktop-only" data-mock-id="home-kpi-views">
          <div className="home-kpi-label">Vues ce mois-ci</div>
          <div className="home-kpi-value">{formatFrInteger(k.viewsThisMonth)}</div>
        </div>
        <div className="home-kpi home-kpi--desktop-only" data-mock-id="home-kpi-reliability">
          <div className="home-kpi-label">Fiabilité moyenne</div>
          <div className="home-kpi-value home-kpi-value--ok">
            {reliability == null ? '—' : `${reliability}%`}
          </div>
        </div>
      </div>

      <div className="home-columns">
        <div className="home-col-main">
          <div className="home-section-title" data-mock-id="home-section-resume">
            Reprendre
          </div>
          {d.resume.length === 0 && <p className="home-empty">Aucun brouillon récent.</p>}
          {d.resume.map((item, i) => (
            <Link
              key={item.documentId}
              to={`/docs/${item.documentId}/edit`}
              className="home-card"
              data-mock-id={i === 0 ? 'home-resume-card' : undefined}
            >
              <div>
                <div className="home-card-title">{item.title}</div>
                <div className="home-card-meta">
                  Brouillon · modifié {item.relativeLabel}
                </div>
              </div>
              <span className="home-card-action">Modifier →</span>
            </Link>
          ))}

          <div
            className="home-section-title spaced home-published-desktop"
            data-mock-id="home-section-published"
          >
            Récemment publié
          </div>
          <div className="home-published-desktop">
            {d.recentlyPublished.map((item, i) => (
              <Link
                key={item.documentId}
                to={`/docs/${item.documentId}`}
                className="home-card"
                data-mock-id={i === 0 ? 'home-published-card' : undefined}
              >
                <div>
                  <div className="home-card-title">{item.title}</div>
                  <div className="home-card-meta">
                    {item.spaceName} · publié le {formatShortDateFr(item.publishedAt)}
                  </div>
                </div>
                <span className="home-dot" aria-hidden />
              </Link>
            ))}
          </div>
        </div>

        <div className="home-col-side">
          <div className="home-section-title" data-mock-id="home-section-approvals">
            En attente de votre approbation
          </div>
          {d.pendingApprovals.length === 0 && (
            <p className="home-empty">Aucune approbation en attente.</p>
          )}
          {d.pendingApprovals.map((item, i) => {
            const detailDesktop = item.slaRemainingLabel
              ? `${item.requesterName} demande une approbation · ${item.slaRemainingLabel}`
              : item.requesterName
            const detailMobile = item.slaRemainingLabel
              ? `${item.requesterName} · ${item.slaRemainingLabel}`
              : item.requesterName
            return (
              <Link
                key={item.requestId}
                to={`/approvals`}
                className="home-card home-card--block"
                data-mock-id={i === 0 ? 'home-approval-card' : undefined}
              >
                <div className="home-card-title">{item.title}</div>
                <div className="home-card-meta home-card-meta--mb">
                  <span className="home-detail-desktop">{detailDesktop}</span>
                  <span className="home-detail-mobile">{detailMobile}</span>
                </div>
                <span className="home-card-action">Examiner →</span>
              </Link>
            )
          })}

          <div
            className="home-section-title spaced-sm activity"
            data-mock-id="home-section-activity"
          >
            Activité de l&apos;équipe
          </div>
          <div className="home-activity" data-mock-id="home-activity">
            {d.teamActivity.length === 0 && <p className="home-empty">Pas d&apos;activité récente.</p>}
            {d.teamActivity.map((a, i) => (
              <div key={`${a.documentId ?? 'x'}-${a.createdAt}-${i}`} className="home-activity-row">
                <div
                  className="home-activity-avatar"
                  style={{
                    background: '#EEEDFD',
                    color: '#3730E0',
                  }}
                  aria-hidden
                >
                  {a.you ? 'V' : initialsFromName(a.actorDisplayName)}
                </div>
                <span>
                  {a.you ? (
                    <>Vous avez {a.actionLabel}</>
                  ) : (
                    <>
                      <strong>{a.actorDisplayName}</strong> {a.actionLabel}
                    </>
                  )}
                  {a.documentTitle ? (
                    <>
                      {' '}
                      <em>{a.documentTitle}</em>
                    </>
                  ) : null}{' '}
                  — {a.relativeLabel}
                </span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
