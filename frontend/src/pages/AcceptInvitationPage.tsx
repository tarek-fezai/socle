// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { getAccessToken, login } from '../lib/auth'
import { acceptInvitation } from '../lib/invitations'
import { api } from '../lib/api'
import { apiErrorCode, apiErrorMessage } from '../lib/apiError'
import { useBrandingInstanceName } from '../lib/publicBranding'
import { LoginLoadingLayout } from './login/LoginPage'
import './login/login.css'

const TOKEN_KEY = 'socle.invite.token'

function readStoredToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

function storeToken(token: string) {
  try {
    sessionStorage.setItem(TOKEN_KEY, token)
  } catch {
    // ignore
  }
}

function clearToken() {
  try {
    sessionStorage.removeItem(TOKEN_KEY)
  } catch {
    // ignore
  }
}

function messageForCode(code: string | undefined, fallback: string): string {
  if (code === 'invitation_expired') return 'Cette invitation a expiré.'
  if (code === 'invitation_already_used') return 'Cette invitation a déjà été utilisée.'
  if (code === 'invitation_email_mismatch') {
    return 'L’e-mail de votre compte ne correspond pas à l’invitation.'
  }
  if (code === 'invitation_email_unverified') {
    return 'Votre e-mail n’est pas vérifié auprès de l’identité. Vérifiez-le puis réessayez.'
  }
  if (code === 'licence_user_limit') {
    return 'Aucun siège disponible. Contactez l’administrateur.'
  }
  if (code === 'identity_conflict') {
    return 'Identité déjà associée à un autre compte : contactez l’administrateur.'
  }
  return fallback
}

export function AcceptInvitationPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const org = useBrandingInstanceName() || 'Socle'
  const [status, setStatus] = useState<'ready' | 'working' | 'ok' | 'error'>('ready')
  const [error, setError] = useState<string | null>(null)
  const started = useRef(false)

  const tokenFromUrl = params.get('token')?.trim() || ''
  const token = tokenFromUrl || readStoredToken() || ''

  useEffect(() => {
    if (tokenFromUrl) {
      storeToken(tokenFromUrl)
      navigate('/invitations/accept', { replace: true })
    }
  }, [tokenFromUrl, navigate])

  useEffect(() => {
    if (status !== 'ok') return
    // Peindre « Invitation acceptée », la laisser lisible, puis ouvrir l’accueil.
    let inner = 0
    let timer = 0
    const outer = requestAnimationFrame(() => {
      inner = requestAnimationFrame(() => {
        timer = window.setTimeout(() => navigate('/', { replace: true }), 800)
      })
    })
    return () => {
      cancelAnimationFrame(outer)
      cancelAnimationFrame(inner)
      window.clearTimeout(timer)
    }
  }, [status, navigate])

  useEffect(() => {
    if (started.current) return
    const stored = tokenFromUrl || readStoredToken()
    if (!stored) return
    started.current = true
    void (async () => {
      const access = await getAccessToken()
      if (!access) return
      setStatus('working')
      try {
        await acceptInvitation(api, stored)
        clearToken()
        setStatus('ok')
      } catch (e) {
        const code = apiErrorCode(e)
        if (
          code === 'invitation_expired' ||
          code === 'invitation_already_used' ||
          code === 'invitation_email_mismatch' ||
          code === 'invitation_email_unverified'
        ) {
          clearToken()
        }
        setError(messageForCode(code, apiErrorMessage(e, 'Invitation refusée')))
        setStatus('error')
      }
    })()
  }, [tokenFromUrl, navigate])

  if (!token && status === 'ready') {
    return (
      <div className="login-root">
        <div className="login-shell" style={{ justifyContent: 'center', alignItems: 'center' }}>
          <div style={{ width: 440, padding: 32 }}>
            <h1 className="login-serif" style={{ fontSize: 28, fontWeight: 400, margin: '0 0 12px' }}>
              Invitation
            </h1>
            <p style={{ fontSize: 14, color: '#6B6B72' }}>Lien d’invitation manquant ou déjà utilisé.</p>
          </div>
        </div>
      </div>
    )
  }

  if (status === 'working') {
    return <LoginLoadingLayout message="Acceptation de l’invitation…" />
  }

  if (status === 'ok') {
    return (
      <div className="login-root">
        <div className="login-shell" style={{ justifyContent: 'center', alignItems: 'center' }}>
          <div style={{ width: 440, padding: 32 }}>
            <h1
              className="login-serif"
              data-testid="invitation-accepted"
              style={{ fontSize: 28, fontWeight: 400, margin: '0 0 12px' }}
            >
              Invitation acceptée
            </h1>
            <p style={{ fontSize: 14, color: '#6B6B72', margin: 0, lineHeight: 1.5 }}>
              Votre compte est créé. Ouverture de l’accueil.
            </p>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="login-root">
      <div className="login-shell" style={{ justifyContent: 'center', alignItems: 'center' }}>
        <div style={{ width: 440, padding: 32 }}>
          <h1 className="login-serif" style={{ fontSize: 28, fontWeight: 400, margin: '0 0 12px' }}>
            Rejoindre {org}
          </h1>
          <p style={{ fontSize: 14, color: '#6B6B72', margin: '0 0 24px', lineHeight: 1.5 }}>
            Vous avez été invité(e) à rejoindre {org}. Connectez-vous avec le même e-mail que
            l’invitation (adresse vérifiée) pour créer votre compte.
          </p>
          {error && (
            <p role="alert" style={{ fontSize: 13.5, color: '#B54708', margin: '0 0 20px' }}>
              {error}
            </p>
          )}
          {status !== 'error' && (
            <button
              type="button"
              className="login-cta"
              onClick={() => {
                if (token) storeToken(token)
                void login('/invitations/accept')
              }}
            >
              Se connecter
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
