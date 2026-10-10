// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import {
  fetchAuthConfig,
  getCachedAuthConfig,
  loginWithAcr,
  loginWithHint,
  loginWithSso,
  sanitizeReturnTo,
  type PublicAuthConfig,
} from '../../lib/auth'
import {
  useBrandingAccent,
  useBrandingInstanceName,
  useBrandingLogoUrl,
} from '../../lib/publicBranding'
import { AlertIcon, LockIcon, LoginErrorVisualSvg, LoginVisualSvg, PasskeyIcon } from './LoginVisuals'
import './login.css'

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function BrandMark({ size = 26, className = 'login-mark' }: { size?: number; className?: string }) {
  const logoUrl = useBrandingLogoUrl()
  const accent = useBrandingAccent()
  if (logoUrl) {
    return (
      <img
        className={`${className} login-mark--img`}
        src={logoUrl}
        alt=""
        width={size}
        height={size}
        aria-hidden="true"
      />
    )
  }
  return <div className={className} style={{ background: accent }} aria-hidden="true" />
}

function BrandName({ mockId, className = 'login-brand-name' }: { mockId?: string; className?: string }) {
  const instance = useBrandingInstanceName()
  return (
    <div className={className} data-mock-id={mockId}>
      {instance || 'Socle'}
    </div>
  )
}


function MobileBrand() {
  const logoUrl = useBrandingLogoUrl()
  const accent = useBrandingAccent()
  return (
    <div className="login-mobile-brand">
      {logoUrl ? (
        <img className="login-mobile-mark login-mark--img" src={logoUrl} alt="" width={28} height={28} aria-hidden="true" />
      ) : (
        <div className="login-mobile-mark" style={{ background: accent }} aria-hidden="true" />
      )}
      <BrandName className="login-serif login-mobile-name" mockId="mobile-brand-name" />
    </div>
  )
}

function supportMailto(config: PublicAuthConfig | null): string | null {
  const mail = config?.supportContact?.trim()
  return mail ? `mailto:${mail}` : null
}

function IdentityTeamLabel({ config }: { config: PublicAuthConfig | null }) {
  const href = supportMailto(config)
  if (href) {
    return (
      <a href={href}>
        l&apos;équipe Identité &amp; accès
      </a>
    )
  }
  return <>l&apos;équipe Identité &amp; accès</>
}

/** Same two-column chrome as the maquette, without the form (loading / redirect). */
export function LoginLoadingLayout({ message }: { message: string }) {
  return (
    <div className="login-root">
      <div className="login-shell login-desktop">
        <div className="login-form-col">
          <div className="login-brand">
            <BrandMark />
            <BrandName mockId="brand-name" />
          </div>
          <h1 className="login-serif login-title" data-mock-id="title">
            Se connecter
          </h1>
          <p className="login-loading-msg">{message}</p>
        </div>
        <div className="login-visual">
          <LoginVisualSvg />
          <div className="login-quote">
            <p className="login-serif" data-mock-id="quote">
              « La documentation qui suit la structure réelle de l&apos;organisation, pas l&apos;inverse. »
            </p>
          </div>
        </div>
      </div>
      <div className="login-mobile">
        <div className="login-mobile-body">
          <MobileBrand />
          <p className="login-mobile-note">{message}</p>
        </div>
      </div>
    </div>
  )
}

export function LoginPage() {
  const [params] = useSearchParams()
  const returnTo = sanitizeReturnTo(params.get('returnTo'))
  const [config, setConfig] = useState<PublicAuthConfig | null>(() => getCachedAuthConfig())
  const [email, setEmail] = useState('')
  const [emailError, setEmailError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    void fetchAuthConfig()
      .then(setConfig)
      .catch(() => setConfig(getCachedAuthConfig()))
  }, [])

  const passkeyAcr = config?.passkeyAcrValues?.trim() || ''
  const orgName = config?.organizationName?.trim() || config?.displayName?.trim() || ''

  async function run(action: () => Promise<void>) {
    setBusy(true)
    try {
      await action()
    } catch {
      setBusy(false)
    }
  }

  function onContinueEmail(e: FormEvent) {
    e.preventDefault()
    const trimmed = email.trim()
    if (!EMAIL_RE.test(trimmed)) {
      setEmailError('Indiquez une adresse professionnelle valide.')
      return
    }
    setEmailError(null)
    void run(() => loginWithHint(trimmed, returnTo))
  }

  if (busy) {
    return <LoginLoadingLayout message="Redirection vers votre fournisseur d'identité…" />
  }

  return (
    <div className="login-root">
      {/* Desktop = Login.dc.html */}
      <div className="login-shell login-desktop">
        <div className="login-form-col">
          <div className="login-brand" data-mock-id="login-brand">
            <BrandMark />
            <BrandName mockId="brand-name" />
          </div>

          <h1 className="login-serif login-title" data-mock-id="title">
            Se connecter
          </h1>
          <p className="login-subtitle" data-mock-id="subtitle">
            Accédez à la documentation de votre organisation.
          </p>

          <form onSubmit={onContinueEmail} noValidate>
            <label className="login-label" htmlFor="login-email" data-mock-id="label-email">
              Adresse professionnelle
            </label>
            <div className="login-email-wrap" data-mock-id="email-field">
              <input
                id="login-email"
                className="login-email-input"
                type="email"
                name="email"
                autoComplete="username"
                inputMode="email"
                value={email}
                onChange={(ev) => {
                  setEmail(ev.target.value)
                  if (emailError) setEmailError(null)
                }}
                placeholder="prenom.nom@organisation.example"
                aria-invalid={emailError ? true : undefined}
                aria-describedby={emailError ? 'login-email-err' : undefined}
              />
            </div>
            {emailError ? (
              <p id="login-email-err" style={{ fontSize: 12, color: '#B54708', margin: '-8px 0 12px' }}>
                {emailError}
              </p>
            ) : null}

            <button type="submit" className="login-cta" data-mock-id="cta-continue">
              Continuer
            </button>
          </form>

          <div className="login-divider" data-mock-id="login-divider">
            <div className="login-divider-line" />
            <span className="login-divider-text" data-mock-id="divider-ou">
              ou
            </span>
            <div className="login-divider-line" />
          </div>

          <button
            type="button"
            className="login-sso"
            data-mock-id="sso-org-btn"
            onClick={() => void run(() => loginWithSso(returnTo))}
          >
            <LockIcon />
            <span className="login-sso-label" data-mock-id="sso-org">
              Continuer avec le SSO de l&apos;organisation
            </span>
          </button>

          {passkeyAcr ? (
            <button
              type="button"
              className="login-sso"
              data-mock-id="sso-passkey-btn"
              onClick={() => void run(() => loginWithAcr(passkeyAcr, returnTo))}
            >
              <PasskeyIcon />
              <span className="login-sso-label" data-mock-id="sso-passkey">
                Continuer avec une clé de sécurité
              </span>
            </button>
          ) : null}

          <p className="login-footer" data-mock-id="footer">
            Réservé aux comptes provisionnés par votre organisation. Un problème d&apos;accès ? Contactez{' '}
            <IdentityTeamLabel config={config} />.
          </p>
        </div>

        <div className="login-visual" data-mock-id="login-visual">
          <LoginVisualSvg />
          <div className="login-quote">
            <p className="login-serif" data-mock-id="quote">
              « La documentation qui suit la structure réelle de l&apos;organisation, pas l&apos;inverse. »
            </p>
          </div>
        </div>
      </div>

      {/* Mobile = MobileLogin.dc.html */}
      <div className="login-mobile">
        <div className="login-mobile-body">
          <div className="login-mobile-brand" data-mock-id="mobile-brand-block">
            <BrandMark size={44} className="login-mobile-mark" />
            <BrandName className="login-serif login-mobile-name" mockId="mobile-brand-name" />
            {orgName ? (
              <div className="login-mobile-org" data-mock-id="mobile-org">
                {orgName}
              </div>
            ) : null}
          </div>

          <button
            type="button"
            className="login-mobile-sso"
            data-mock-id="mobile-sso-btn"
            onClick={() => void run(() => loginWithSso(returnTo))}
          >
            <LockIcon stroke="#FFFFFF" />
            <span className="login-mobile-sso-label" data-mock-id="mobile-sso">
              Continuer avec le SSO de l&apos;organisation
            </span>
          </button>

          <p className="login-mobile-note" data-mock-id="mobile-note">
            Réservé aux comptes provisionnés par votre organisation.
          </p>
        </div>

        {supportMailto(config) ? (
          <div className="login-mobile-help" data-mock-id="mobile-help">
            Besoin d&apos;aide ?{' '}
            <a href={supportMailto(config)!}>Centre d&apos;aide</a>
          </div>
        ) : null}
      </div>
    </div>
  )
}

const DOMAIN_NOT_ALLOWED_CAUSE = 'Le domaine de votre adresse n\'est pas autorisé sur cette instance.'

/** Causes possibles de LoginError.dc.html ; le nom de l'IdP vient de la configuration publique. */
function loginErrorCauses(reason: string, config: PublicAuthConfig | null): string[] {
  const idp = config?.idpDisplayName?.trim() || 'votre fournisseur d\'identité'
  const causes = [
    `La synchronisation SCIM depuis ${idp} n'a pas encore provisionné ce compte.`,
    'Le compte a été désactivé ou retiré d\'un groupe autorisé.',
  ]
  return reason === 'email_domain_not_allowed' ? [DOMAIN_NOT_ALLOWED_CAUSE, ...causes] : causes
}

export function LoginErrorPage() {
  const [params] = useSearchParams()
  const reason = params.get('reason') ?? 'not_provisioned'
  const [config, setConfig] = useState<PublicAuthConfig | null>(() => getCachedAuthConfig())
  const [email, setEmail] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    void (async () => {
      try {
        const c = await fetchAuthConfig()
        setConfig(c)
      } catch {
        setConfig(getCachedAuthConfig())
      }
      try {
        const { getUserManager } = await import('../../lib/auth')
        const um = await getUserManager()
        const user = await um.getUser()
        const idEmail =
          (user?.profile?.email as string | undefined) ||
          (user?.profile?.preferred_username as string | undefined) ||
          ''
        setEmail(idEmail)
      } catch {
        setEmail('')
      }
    })()
  }, [])

  async function onRetry() {
    setBusy(true)
    try {
      const { clearLocalSession } = await import('../../lib/auth')
      await clearLocalSession()
      window.location.assign('/login')
    } catch {
      window.location.assign('/login')
    }
  }

  if (busy) {
    return <LoginLoadingLayout message="Retour à la connexion…" />
  }

  const support = supportMailto(config)
  const org = config?.organizationName?.trim() || config?.displayName?.trim() || ''
  const causes = loginErrorCauses(reason, config)

  return (
    <div className="login-root">
      <div className="login-shell login-desktop">
        <div className="login-form-col">
          <div className="login-brand login-brand--error" data-mock-id="login-brand">
            <BrandMark />
            <BrandName mockId="brand-name" />
          </div>

          <div className="login-alert-icon" data-mock-id="login-alert-icon">
            <AlertIcon />
          </div>

          <h1 className="login-serif login-title login-title--error" data-mock-id="title">
            Connexion refusée
          </h1>
          <p className="login-subtitle login-subtitle--error" data-mock-id="subtitle">
            {reason === 'not_provisioned' ? (
              <>L&apos;authentification SSO a réussi, mais aucun compte Socle n&apos;est associé à </>
            ) : (
              <>L&apos;authentification SSO a réussi, mais l&apos;accès à Socle est refusé pour </>
            )}
            <strong style={{ color: '#0E0E10' }}>{email || 'votre compte'}</strong>.
          </p>

          <div className="login-causes" data-testid="login-error-causes" data-mock-id="causes">
            <div className="login-causes-title" data-mock-id="causes-title">
              Causes possibles
            </div>
            {causes.map((c, i) => (
              <div className="login-cause" key={c} data-mock-id={`cause-${i}`}>
                <span className="login-cause-dot" aria-hidden="true" />
                <span className="login-cause-text">{c}</span>
              </div>
            ))}
          </div>

          <button
            type="button"
            className="login-cta login-cta--error"
            data-mock-id="cta-retry"
            onClick={() => void onRetry()}
          >
            Réessayer
          </button>

          <Link className="login-ghost" to="/integrations" data-mock-id="contact-support">
            Voir l&apos;état du provisioning (admin)
          </Link>

          <p className="login-footer login-footer--error" data-mock-id="footer">
            Un problème persiste ? Contactez <IdentityTeamLabel config={config} />
            {org ? <> de {org}</> : null}.
          </p>
        </div>

        <div className="login-visual login-visual--error" data-mock-id="login-visual">
          <LoginErrorVisualSvg />
          <div className="login-quote">
            <p className="login-serif" data-mock-id="quote">
              « L&apos;accès suit la structure de l&apos;organisation — pas l&apos;inverse, y compris quand elle
              change. »
            </p>
          </div>
        </div>
      </div>

      {/* Mobile fallback: same messaging, stacked */}
      <div className="login-mobile">
        <div className="login-mobile-body">
          <MobileBrand />
          <h1 className="login-serif" style={{ fontSize: 26, fontWeight: 400, margin: '0 0 12px', textAlign: 'center' }}>
            Connexion refusée
          </h1>
          <p className="login-mobile-note" style={{ marginBottom: 16 }}>
            L&apos;accès à Socle est refusé pour <strong>{email || 'votre compte'}</strong>.
          </p>
          {causes.map((c) => (
            <p key={c} className="login-mobile-note" style={{ marginBottom: 12, textAlign: 'left' }}>
              {c}
            </p>
          ))}
          <button type="button" className="login-mobile-sso" onClick={() => void onRetry()}>
            <span className="login-mobile-sso-label">Réessayer</span>
          </button>
        </div>
        {support ? (
          <div className="login-mobile-help">
            <a href={support}>Contacter l&apos;équipe Identité &amp; accès</a>
          </div>
        ) : null}
      </div>
    </div>
  )
}
