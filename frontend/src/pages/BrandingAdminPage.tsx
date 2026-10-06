// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { FormEvent, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AdminShell } from '../components/admin/AdminShell'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/apiError'
import {
  BRANDING_ACCENT_SWATCHES,
  DEFAULT_ACCENT,
  brandingAdminKey,
  displayPublicBaseUrl,
  getAdminBranding,
  normalizeAccent,
  removeBrandingFavicon,
  removeBrandingLogo,
  sendBrandingTestEmail,
  updateAdminBranding,
  uploadBrandingFavicon,
  uploadBrandingLogo,
} from '../lib/brandingAdmin'
import { publicBrandingKey } from '../lib/brandingAdmin'

/** Administration branding (Branding.dc.html — sans plan Entreprise / DNS / socle.app). */
export function BrandingAdminPage() {
  const qc = useQueryClient()
  const logoInput = useRef<HTMLInputElement>(null)
  const faviconInput = useRef<HTMLInputElement>(null)
  const customColor = useRef<HTMLInputElement>(null)
  const [senderName, setSenderName] = useState<string | null>(null)
  const [senderEmail, setSenderEmail] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [testMsg, setTestMsg] = useState<string | null>(null)

  const brandingQuery = useQuery({
    queryKey: brandingAdminKey(),
    queryFn: async () => {
      const data = await getAdminBranding(api)
      setSenderName(data.senderName)
      setSenderEmail(data.senderEmail)
      return data
    },
  })

  const b = brandingQuery.data
  const accent = normalizeAccent(b?.accentColor)
  const name = senderName ?? b?.senderName ?? ''
  const email = senderEmail ?? b?.senderEmail ?? ''

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: brandingAdminKey() })
    void qc.invalidateQueries({ queryKey: publicBrandingKey() })
  }

  const saveMut = useMutation({
    mutationFn: (body: Parameters<typeof updateAdminBranding>[1]) => updateAdminBranding(api, body),
    onSuccess: (data) => {
      setSenderName(data.senderName)
      setSenderEmail(data.senderEmail)
      setError(null)
      invalidate()
    },
    onError: (e) => setError(apiErrorMessage(e, 'Enregistrement impossible')),
  })

  const logoMut = useMutation({
    mutationFn: (file: File) => uploadBrandingLogo(api, file),
    onSuccess: () => {
      setError(null)
      invalidate()
    },
    onError: (e) => setError(apiErrorMessage(e, 'Upload du logo impossible')),
  })

  const favMut = useMutation({
    mutationFn: (file: File) => uploadBrandingFavicon(api, file),
    onSuccess: () => {
      setError(null)
      invalidate()
    },
    onError: (e) => setError(apiErrorMessage(e, 'Upload du favicon impossible')),
  })

  const removeLogoMut = useMutation({
    mutationFn: () => removeBrandingLogo(api),
    onSuccess: () => invalidate(),
    onError: (e) => setError(apiErrorMessage(e, 'Suppression du logo impossible')),
  })

  const removeFavMut = useMutation({
    mutationFn: () => removeBrandingFavicon(api),
    onSuccess: () => invalidate(),
    onError: (e) => setError(apiErrorMessage(e, 'Suppression du favicon impossible')),
  })

  const testMut = useMutation({
    mutationFn: () => sendBrandingTestEmail(api),
    onSuccess: (res) => {
      setTestMsg(`E-mail de test envoyé à ${res.to} (${res.channel}).`)
      setError(null)
    },
    onError: (e) => setError(apiErrorMessage(e, "Envoi de l'e-mail de test impossible")),
  })

  function persist(patch: {
    accentColor?: string | null
    hidePoweredBy?: boolean
    senderName?: string | null
    senderEmail?: string | null
  }) {
    if (!b) return
    saveMut.mutate({
      accentColor: patch.accentColor !== undefined ? patch.accentColor : b.accentColor,
      hidePoweredBy: patch.hidePoweredBy ?? b.hidePoweredBy,
      senderName: patch.senderName !== undefined ? patch.senderName : name.trim() || null,
      senderEmail: patch.senderEmail !== undefined ? patch.senderEmail : email.trim() || null,
    })
  }

  function onSaveSender(e: FormEvent) {
    e.preventDefault()
    persist({
      senderName: name.trim() || null,
      senderEmail: email.trim() || null,
    })
  }

  const forbidden =
    brandingQuery.isError &&
    (brandingQuery.error as { response?: { status?: number } })?.response?.status === 403

  const orgLabel = b?.instanceName?.trim() || 'Organisation Démo'

  return (
    <AdminShell
      active="branding"
      breadcrumb={[
        { label: 'Compte', to: '/' },
        { label: 'Administration', to: '/admin/tags' },
        { label: 'Personnalisation de marque' },
      ]}
      mainClassName="admin-main--fields"
      innerWide
      rightRail={
        <aside className="admin-rail admin-rail--fields" data-mock-id="branding-rail">
          <div data-mock-id="branding-rail-hosting">
            <div className="admin-rail__label">Instance auto-hébergée</div>
            <p className="admin-rail__para">
              La personnalisation de marque s&apos;applique à cette instance. L&apos;URL publique est
              définie par reverse-proxy via{' '}
              <span className="admin-mono">SOCLE_PUBLIC_BASE_URL</span> (non modifiable ici).
            </p>
          </div>
          <div data-mock-id="branding-rail-where">
            <div className="admin-rail__label">Où la marque apparaît</div>
            <div className="admin-rail__limits">
              <span>· Écran de connexion &amp; SSO</span>
              <span>· En-tête de l&apos;application</span>
              <span>· Documentation produit intégrée</span>
              <span>· E-mails automatiques</span>
              <span>· Exports PDF</span>
            </div>
          </div>
          <div className="admin-rail__divider" data-mock-id="branding-rail-seealso">
            <div className="admin-rail__label">Voir aussi</div>
            <Link to="/admin/tags" className="admin-rail__link">
              Administration →
            </Link>
            <span className="admin-rail__link" style={{ color: '#9B9BA1', cursor: 'default' }}>
              Identité &amp; SSO →
            </span>
          </div>
        </aside>
      }
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
        <h1 className="admin-title" data-mock-id="branding-title" style={{ margin: 0 }}>
          Personnalisation de marque
        </h1>
      </div>
      <p
        className="admin-lead"
        data-mock-id="branding-lead"
        style={{ margin: '0 0 28px' }}
      >
        Adaptez Socle à l&apos;identité visuelle de {orgLabel} — logo, couleurs, URL publique et
        expéditeur des e-mails.
      </p>

      {forbidden && (
        <p className="admin-alert admin-alert--error" role="alert">
          Administrateur système requis pour personnaliser la marque.
        </p>
      )}

      {brandingQuery.isError && !forbidden && (
        <p className="admin-alert admin-alert--error" role="alert">
          {apiErrorMessage(brandingQuery.error, 'Impossible de charger le branding')}
        </p>
      )}

      {error && (
        <p className="admin-alert admin-alert--error" role="alert">
          {error}
        </p>
      )}
      {testMsg && (
        <p className="admin-alert" style={{ background: '#E7F5EA', color: '#1E8E5A' }} role="status">
          {testMsg}
        </p>
      )}

      <div className="admin-builder-label" data-mock-id="branding-logo-label">
        Logo &amp; identité visuelle
      </div>
      <div className="admin-branding-identity" data-mock-id="branding-identity">
        <div style={{ flexShrink: 0 }}>
          <div style={{ fontSize: 12, color: '#9B9BA1', marginBottom: 8 }}>Logo principal</div>
          <div className="admin-branding-preview" data-mock-id="branding-logo-preview" style={{ background: accent }}>
            {b?.logoUrl ? (
              <img src={b.logoUrl} alt="" style={{ maxWidth: '100%', maxHeight: '100%' }} />
            ) : (
              <span className="admin-branding-preview__letter">S</span>
            )}
          </div>
          <input
            ref={logoInput}
            type="file"
            accept="image/png,image/webp"
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0]
              if (f) logoMut.mutate(f)
              e.target.value = ''
            }}
          />
          <a
            href="#branding-logo"
            className="admin-link-action"
            onClick={(e) => {
              e.preventDefault()
              logoInput.current?.click()
            }}
          >
            Remplacer →
          </a>
          {b?.hasLogo ? (
            <button
              type="button"
              className="admin-action admin-action--danger"
              style={{ display: 'block', marginTop: 6 }}
              onClick={() => removeLogoMut.mutate()}
            >
              Retirer
            </button>
          ) : null}
        </div>

        <div style={{ flexShrink: 0 }}>
          <div style={{ fontSize: 12, color: '#9B9BA1', marginBottom: 8 }}>
            Icône compacte (favicon)
          </div>
          <div
            className="admin-branding-preview admin-branding-preview--border"
            data-mock-id="branding-favicon-preview"
          >
            {b?.faviconUrl ? (
              <img src={b.faviconUrl} alt="" width={22} height={22} style={{ borderRadius: 6 }} />
            ) : (
              <div
                style={{ width: 22, height: 22, borderRadius: 6, background: accent }}
                aria-hidden
              />
            )}
          </div>
          <input
            ref={faviconInput}
            type="file"
            accept="image/png,image/webp"
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0]
              if (f) favMut.mutate(f)
              e.target.value = ''
            }}
          />
          <a
            href="#branding-favicon"
            className="admin-link-action"
            onClick={(e) => {
              e.preventDefault()
              faviconInput.current?.click()
            }}
          >
            Remplacer →
          </a>
          {b?.hasFavicon ? (
            <button
              type="button"
              className="admin-action admin-action--danger"
              style={{ display: 'block', marginTop: 6 }}
              onClick={() => removeFavMut.mutate()}
            >
              Retirer
            </button>
          ) : null}
        </div>

        <div
          style={{ flexGrow: 1, borderLeft: '1px solid #ECECEE', paddingLeft: 32 }}
          data-mock-id="branding-accent"
        >
          <div style={{ fontSize: 12, color: '#9B9BA1', marginBottom: 10 }}>Couleur d&apos;accent</div>
          <div style={{ display: 'flex', gap: 10, marginBottom: 16 }}>
            {BRANDING_ACCENT_SWATCHES.map((c) => {
              const on = accent.toUpperCase() === c
              return (
                <button
                  key={c}
                  type="button"
                  className="admin-swatch"
                  style={{
                    background: c,
                    border: on ? '2px solid #0E0E10' : '1px solid #ECECEE',
                  }}
                  aria-label={`Accent ${c}`}
                  aria-pressed={on}
                  onClick={() => persist({ accentColor: c === DEFAULT_ACCENT ? c : c })}
                />
              )
            })}
            <button
              type="button"
              className="admin-swatch admin-swatch--custom"
              aria-label="Couleur personnalisée"
              onClick={() => customColor.current?.click()}
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#9B9BA1" strokeWidth="2.4" aria-hidden>
                <line x1="12" y1="5" x2="12" y2="19" />
                <line x1="5" y1="12" x2="19" y2="12" />
              </svg>
            </button>
            <input
              ref={customColor}
              type="color"
              hidden
              value={accent}
              onChange={(e) => persist({ accentColor: e.target.value.toUpperCase() })}
            />
          </div>
          <div style={{ fontSize: 12, color: '#9B9BA1', marginBottom: 8 }}>
            Appliquée aux boutons, liens, badges de statut et à l&apos;écran de connexion.
          </div>
          <label className="admin-toggle-row" style={{ marginBottom: 0 }}>
            <span
              className={`admin-toggle admin-toggle--sm${b?.hidePoweredBy ? ' admin-toggle--on' : ''}`}
              role="switch"
              aria-checked={Boolean(b?.hidePoweredBy)}
              data-mock-id="branding-hide-powered"
              tabIndex={0}
              onClick={() => persist({ hidePoweredBy: !b?.hidePoweredBy })}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  persist({ hidePoweredBy: !b?.hidePoweredBy })
                }
              }}
            >
              <span className="admin-toggle__knob" />
            </span>
            Masquer la mention « Propulsé par Socle »
          </label>
        </div>
      </div>

      <div className="admin-builder-label" data-mock-id="branding-domain-label">
        URL publique
      </div>
      <div className="admin-branding-domain" data-mock-id="branding-domain">
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div>
            <div
              style={{ fontSize: 14, fontWeight: 600, color: '#0E0E10', marginBottom: 3 }}
              data-mock-id="branding-public-url"
            >
              {displayPublicBaseUrl(b?.publicBaseUrl)}
            </div>
            <div style={{ fontSize: 12.5, color: '#6B6B72' }} data-mock-id="branding-public-note">
              {b?.publicBaseUrlNote ??
                'Défini par reverse-proxy (SOCLE_PUBLIC_BASE_URL) — non modifiable depuis l’interface.'}
            </div>
          </div>
          {b?.publicBaseUrl ? (
            <span className="admin-branding-verified">
              <CheckIcon />
              Configurée
            </span>
          ) : null}
        </div>
      </div>

      <div className="admin-builder-label" data-mock-id="branding-email-label">
        E-mails sortants
      </div>
      <form
        className="admin-branding-email"
        data-mock-id="branding-email"
        onSubmit={onSaveSender}
      >
        <div style={{ flex: 1 }}>
          <label className="admin-form-label" htmlFor="branding-sender-name">
            Nom de l&apos;expéditeur
          </label>
          <input
            id="branding-sender-name"
            className="admin-form-input"
            style={{ fontSize: 13.5, color: '#43434A' }}
            value={name}
            onChange={(e) => setSenderName(e.target.value)}
            onBlur={() => {
              if (b && name !== (b.senderName ?? '')) {
                persist({ senderName: name.trim() || null, senderEmail: email.trim() || null })
              }
            }}
          />
          <label className="admin-form-label" htmlFor="branding-sender-email">
            Adresse d&apos;envoi
          </label>
          <input
            id="branding-sender-email"
            className="admin-form-input admin-mono"
            style={{ fontSize: 13, color: '#43434A', marginBottom: 0 }}
            value={email}
            onChange={(e) => setSenderEmail(e.target.value)}
            onBlur={() => {
              if (b && email !== (b.senderEmail ?? '')) {
                persist({ senderName: name.trim() || null, senderEmail: email.trim() || null })
              }
            }}
          />
          {email ? (
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                marginTop: 8,
                fontSize: 11.5,
                color: '#1E8E5A',
                fontWeight: 600,
              }}
              data-mock-id="branding-spf"
            >
              <CheckIcon size={11} />
              SPF &amp; DKIM vérifiés
            </div>
          ) : null}
          <button
            type="button"
            className="admin-link-action"
            style={{
              marginTop: 12,
              display: 'inline-block',
              background: 'none',
              border: 'none',
              padding: 0,
              cursor: 'pointer',
              font: 'inherit',
            }}
            data-mock-id="branding-test-email"
            disabled={testMut.isPending || !b}
            onClick={() => testMut.mutate()}
          >
            Envoyer un e-mail de test →
          </button>
        </div>
        <div style={{ flex: 1, borderLeft: '1px solid #ECECEE', paddingLeft: 32 }}>
          <div
            style={{
              fontSize: 11.5,
              fontWeight: 600,
              color: '#9B9BA1',
              textTransform: 'uppercase',
              letterSpacing: '0.04em',
              marginBottom: 10,
            }}
          >
            Aperçu
          </div>
          <div
            style={{ border: '1px solid #ECECEE', borderRadius: 10, padding: '14px 16px' }}
            data-mock-id="branding-email-preview"
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                marginBottom: 10,
                paddingBottom: 10,
                borderBottom: '1px solid #ECECEE',
              }}
            >
              <div
                style={{
                  width: 26,
                  height: 26,
                  borderRadius: 7,
                  background: accent,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  flexShrink: 0,
                  overflow: 'hidden',
                }}
              >
                {b?.logoUrl ? (
                  <img src={b.logoUrl} alt="" width={26} height={26} />
                ) : (
                  <span
                    style={{
                      fontSize: 12,
                      fontWeight: 700,
                      color: '#FFFFFF',
                      fontFamily: "'Instrument Serif', serif",
                    }}
                  >
                    S
                  </span>
                )}
              </div>
              <div>
                <div style={{ fontSize: 12.5, fontWeight: 600, color: '#0E0E10' }}>
                  {name.trim() || `${orgLabel} — Socle`}
                </div>
                <div className="admin-mono" style={{ fontSize: 10.5, color: '#9B9BA1' }}>
                  {email.trim() || 'notifications@example.com'}
                </div>
              </div>
            </div>
            <div style={{ fontSize: 12.5, color: '#43434A', fontWeight: 600, marginBottom: 4 }}>
              Rappel : attestation en attente
            </div>
            <div style={{ fontSize: 11.5, color: '#9B9BA1', lineHeight: 1.5 }}>
              La Politique de gestion des accès requiert votre accusé de lecture avant le 3 octobre
              2026…
            </div>
          </div>
        </div>
      </form>
    </AdminShell>
  )
}

function CheckIcon({ size = 12 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="#1E8E5A"
      strokeWidth="2.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <polyline points="20 6 9 17 4 12" />
    </svg>
  )
}
