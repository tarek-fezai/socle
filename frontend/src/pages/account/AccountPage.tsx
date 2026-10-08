// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/AuthProvider'
import { api } from '../../lib/api'
import { getCachedAuthConfig } from '../../lib/auth'
import {
  applyDevicePreferences,
  loadDevicePreferences,
  saveDevicePreferences,
  type ColorScheme,
  type DevicePreferences,
  type FontScale,
} from '../../lib/devicePreferences'
import { idpAccountConsoleUrl } from '../../lib/idpAccountUrl'
import { formatPlatformRoleLabels, isPlatformAdmin } from '../../lib/platformRoles'
import { initialsFromName } from '../../components/shell/shellUtils'
import './account-page.css'

function Toggle({
  on,
  onChange,
  disabled,
  label,
  visualMask,
}: {
  on: boolean
  onChange: (v: boolean) => void
  disabled?: boolean
  label: string
  visualMask?: string
}) {
  return (
    <button
      type="button"
      className={`account-toggle${on ? ' account-toggle--on' : ''}`}
      aria-pressed={on}
      aria-label={label}
      disabled={disabled}
      data-visual-mask={visualMask}
      onClick={() => onChange(!on)}
    >
      <span className="account-toggle-knob" aria-hidden />
    </button>
  )
}

/** Paramètres du compte (Account.dc.html). */
export function AccountPage() {
  const { me, logout, organizationName } = useAuth()
  const [prefs, setPrefs] = useState<DevicePreferences>(() => loadDevicePreferences())
  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState<string | null>(null)

  const displayName = me?.displayName ?? ''
  const email = me?.email ?? ''
  const initials = me?.avatarInitials || initialsFromName(displayName)
  const roleLine = formatPlatformRoleLabels(me?.roles)
  const metaLine = [email, roleLine].filter(Boolean).join(' · ')

  const issuer = me?.issuer ?? getCachedAuthConfig()?.authority
  const idpName = getCachedAuthConfig()?.idpDisplayName?.trim() || 'votre fournisseur d\'identité'
  const idpAccount = idpAccountConsoleUrl(issuer)
  const showOrgAdmin = isPlatformAdmin(me?.roles)
  const orgLabel = organizationName?.trim() || 'l\'organisation'

  function patchPrefs(patch: Partial<DevicePreferences>) {
    saveDevicePreferences(patch)
    const next = loadDevicePreferences()
    setPrefs(next)
    applyDevicePreferences(next)
  }

  async function requestExport() {
    setExportError(null)
    setExporting(true)
    try {
      const { data } = await api.get<Record<string, unknown>>('/api/v1/me/export')
      if (!data || typeof data !== 'object') {
        throw new Error('Réponse d\'export vide')
      }
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `socle-export-${me?.id ?? 'me'}.json`
      document.body.appendChild(a)
      a.click()
      a.remove()
      // Chromium peut annuler le téléchargement si l'URL est révoquée dans la même tâche que le clic.
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch {
      setExportError('Export impossible pour le moment.')
    } finally {
      setExporting(false)
    }
  }

  return (
    <div className="account-page" data-mock-id="account-page" data-testid="account-page">
      <header className="account-page__top">
        <div className="account-page__trail">
          <Link to="/">Accueil</Link>
          <span style={{ color: '#dedee1' }}>→</span>
          <span className="account-page__trail-current">Paramètres du compte</span>
        </div>
        <div className="account-page__top-actions">
          <button type="button" className="account-page__top-link" disabled>
            Centre d&apos;aide{' '}
            <span className="account-soon" data-visual-ignore>
              Bientôt
            </span>
          </button>
          <button
            type="button"
            className="account-page__top-link account-page__top-link--logout"
            onClick={() => void logout()}
          >
            Se déconnecter
          </button>
        </div>
      </header>

      <div className="account-page__body">
        <div className="account-page__inner">
          <h1 className="account-title" data-mock-id="account-title">
            Paramètres du compte
          </h1>

          <div className="account-profile" data-mock-id="account-profile">
            <div className="account-avatar" aria-hidden>
              {initials}
            </div>
            <div>
              <div className="account-profile-name">{displayName}</div>
              <div className="account-profile-meta">{metaLine}</div>
            </div>
          </div>

          <div className="account-section-head">
            <div className="account-section-title">Apparence</div>
            <span className="account-saved-hint">Enregistré sur cet appareil</span>
          </div>
          <div className="account-theme-grid" data-mock-id="account-appearance">
            {(
              [
                ['light', 'Clair', 'account-theme-preview--light'],
                ['dark', 'Sombre', 'account-theme-preview--dark'],
                ['system', 'Système', 'account-theme-preview--system'],
              ] as const
            ).map(([value, label, previewClass]) => (
              <button
                key={value}
                type="button"
                className={`account-theme-opt${prefs.colorScheme === value ? ' account-theme-opt--on' : ''}`}
                onClick={() => patchPrefs({ colorScheme: value as ColorScheme })}
              >
                <div className={`account-theme-preview ${previewClass}`} aria-hidden />
                <div className="account-theme-label">{label}</div>
              </button>
            ))}
          </div>

          <div className="account-section-head">
            <div className="account-section-title">Accessibilité</div>
            <span className="account-saved-hint">Enregistré sur cet appareil</span>
          </div>
          <div className="account-card-row" data-mock-id="account-accessibility">
            <div className="account-row">
              <div className="account-row-label">
                <div className="account-row-title">Contraste élevé</div>
                <div className="account-row-desc">
                  Renforce les bordures et le contraste du texte sur fond clair et sombre
                </div>
              </div>
              <Toggle
                label="Contraste élevé"
                on={prefs.highContrast}
                onChange={(highContrast) => patchPrefs({ highContrast })}
              />
            </div>
            <div className="account-row account-row--stack">
              <div className="account-row-title">Taille de police</div>
              <div className="account-font-scale">
                {(
                  [
                    ['small', 'Petite', '12px'],
                    ['normal', 'Normale', '13.5px'],
                    ['large', 'Grande', '15px'],
                  ] as const
                ).map(([value, label, size]) => (
                  <button
                    key={value}
                    type="button"
                    className={`account-font-btn${prefs.fontScale === value ? ' account-font-btn--on' : ''}`}
                    style={{ fontSize: size }}
                    onClick={() => patchPrefs({ fontScale: value as FontScale })}
                  >
                    {label}
                  </button>
                ))}
              </div>
            </div>
            <div className="account-row">
              <div className="account-row-label">
                <div className="account-row-title">Réduire les animations</div>
                <div className="account-row-desc">
                  Supprime les transitions et animations d&apos;interface non essentielles
                </div>
              </div>
              <Toggle
                label="Réduire les animations"
                on={prefs.reduceMotion}
                onChange={(reduceMotion) => patchPrefs({ reduceMotion })}
              />
            </div>
          </div>

          <div className="account-section-title">Langue</div>
          <div className="account-card-row" data-mock-id="account-language">
            <div className="account-row">
              <div className="account-row-label">
                <div className="account-row-title account-row-title--mb2">
                  Langue de l&apos;interface
                </div>
                <div className="account-row-desc">Menus, boutons et notifications système</div>
              </div>
              <div className="account-select-mock account-select-mock--disabled" aria-disabled>
                Français
              </div>
            </div>
            <div className="account-row">
              <div className="account-row-label">
                <div className="account-row-title account-row-title--mb2">
                  Traduction automatique des documents
                </div>
                <div className="account-row-desc">
                  Propose une traduction quand un document n&apos;existe pas dans votre langue
                  d&apos;interface
                </div>
              </div>
              <Toggle label="Traduction automatique" on={false} onChange={() => {}} disabled />
              <span className="account-soon" data-visual-ignore>
                Bientôt
              </span>
            </div>
          </div>

          <div className="account-section-title">Notifications</div>
          <div className="account-card-row" data-mock-id="account-notifications">
            {[
              'Nouveau commentaire sur mes documents',
              "Demande de revue ou d'approbation",
              "Résumé hebdomadaire de l'activité",
            ].map((title) => (
              <div className="account-row" key={title}>
                <div className="account-row-title">{title}</div>
                <Toggle
                  label={title}
                  on={false}
                  onChange={() => {}}
                  disabled
                  visualMask="account-notifications-toggles"
                />
                <span className="account-soon" data-visual-ignore>
                  Bientôt
                </span>
              </div>
            ))}
          </div>

          <div className="account-section-title">Sécurité</div>
          <div className="account-card-row" data-mock-id="account-security">
            <div className="account-row account-row--security">
              <div className="account-row-label">
                <div className="account-row-title account-row-title--mb2">Authentification</div>
                <div className="account-row-desc" data-visual-mask="account-security-idp">
                  Gérée par votre fournisseur d&apos;identité ({idpName})
                </div>
              </div>
              {idpAccount ? (
                <a
                  href={idpAccount}
                  className="account-link"
                  target="_blank"
                  rel="noreferrer"
                  data-visual-mask="account-security-idp"
                >
                  Console compte →
                </a>
              ) : null}
            </div>
            <div
              className="account-row account-row--security"
              data-visual-mask="account-security-2fa"
              data-visual-ignore
            >
              <div className="account-row-label">
                <div className="account-row-title account-row-title--mb4">
                  Vérification en deux étapes
                </div>
                <div className="account-row-desc">Gérée par le fournisseur d&apos;identité</div>
              </div>
            </div>
          </div>

          <div className="account-section-title">Sessions actives</div>
          <div
            className="account-muted-box"
            data-mock-id="account-sessions"
            data-visual-mask="account-sessions-list"
          >
            Gérées par le fournisseur d&apos;identité
          </div>

          <div className="account-section-head" data-mock-id="account-pat">
            <div className="account-section-title">Jetons d&apos;accès personnels</div>
            <span
              className="account-link account-link--disabled"
              data-visual-mask="account-pat-generate"
            >
              + Générer un jeton{' '}
              <span className="account-soon" data-visual-ignore>
                Bientôt
              </span>
            </span>
          </div>
          <p className="account-row-desc" style={{ margin: '0 0 10px', lineHeight: 1.5 }}>
            Agissent en votre nom avec vos propres permissions — à distinguer des clés API de
            l&apos;organisation, gérées séparément dans{' '}
            <Link to="/integrations" style={{ color: '#6b6b72', textDecoration: 'underline' }}>
              Intégrations &amp; API
            </Link>
            .
          </p>
          <div
            className="account-empty-pat"
            data-mock-id="account-pat-list"
            data-visual-mask="account-pat-list"
          >
            Aucun jeton personnel
          </div>

          <div className="account-section-title">Confidentialité &amp; données personnelles</div>
          <div className="account-card-row account-card-row--privacy" data-mock-id="account-privacy">
            <div className="account-row account-row--privacy">
              <div className="account-row-label">
                <div className="account-row-title account-row-title--semibold">
                  Télécharger mes données
                </div>
                <div className="account-row-desc account-row-desc--privacy">
                  Exportez une archive de vos données personnelles : profil, documents dont vous
                  êtes auteur, commentaires, historique d&apos;activité et attestations —
                  conformément au RGPD (droit à la portabilité).
                </div>
                {exportError ? (
                  <p className="account-row-desc" style={{ color: '#b54708' }} role="alert">
                    {exportError}
                  </p>
                ) : null}
              </div>
              <button
                type="button"
                className="account-export-cta"
                data-testid="account-export-btn"
                disabled={exporting}
                onClick={() => void requestExport()}
              >
                {exporting ? 'Export…' : 'Demander mon export'}
              </button>
            </div>
          </div>

          {showOrgAdmin ? (
            <>
              <div className="account-section-title">Organisation</div>
              <Link
                to="/admin"
                className="account-org-card"
                data-mock-id="account-org-admin"
                data-testid="account-org-admin"
              >
                <div>
                  <div className="account-row-title account-row-title--mb2">
                    Administration de {orgLabel}
                  </div>
                  <div className="account-row-desc">
                    SSO, domaines autorisés, rôles globaux, politique de rétention
                  </div>
                </div>
                <span className="account-link">Ouvrir →</span>
              </Link>
            </>
          ) : null}
        </div>
      </div>
    </div>
  )
}
