// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { createContext, useContext, useEffect, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from './api'
import {
  DEFAULT_ACCENT,
  getPublicBranding,
  normalizeAccent,
  publicBrandingKey,
  type PublicBrandingView,
} from './brandingAdmin'
import { apiBaseUrl } from './urls'

const PublicBrandingContext = createContext<PublicBrandingView | null>(null)

function resolveAssetUrl(path: string | null | undefined): string | null {
  if (!path) return null
  if (/^https?:\/\//i.test(path)) return path
  const base = apiBaseUrl()
  return `${base}${path.startsWith('/') ? path : `/${path}`}`
}

function applyFavicon(href: string | null) {
  const existing = document.querySelector<HTMLLinkElement>('link[data-socle-favicon="1"]')
  if (!href) {
    existing?.remove()
    return
  }
  const link = existing ?? document.createElement('link')
  link.rel = 'icon'
  link.href = href
  link.setAttribute('data-socle-favicon', '1')
  if (!existing) document.head.appendChild(link)
}

function applyAccent(color: string) {
  document.documentElement.style.setProperty('--socle-accent', color)
}

/** Charge le branding public et l'expose (favicon, accent CSS, logo). */
export function PublicBrandingProvider({ children }: { children: ReactNode }) {
  const query = useQuery({
    queryKey: publicBrandingKey(),
    queryFn: () => getPublicBranding(api),
    staleTime: 60_000,
    retry: false,
  })

  const branding = query.data ?? null

  useEffect(() => {
    const accent = normalizeAccent(branding?.accentColor)
    applyAccent(accent)
    applyFavicon(resolveAssetUrl(branding?.faviconUrl ?? null))
  }, [branding])

  return (
    <PublicBrandingContext.Provider value={branding}>{children}</PublicBrandingContext.Provider>
  )
}

export function usePublicBranding(): PublicBrandingView | null {
  return useContext(PublicBrandingContext)
}

export function useBrandingAccent(): string {
  const b = usePublicBranding()
  return normalizeAccent(b?.accentColor)
}

export function useBrandingLogoUrl(): string | null {
  const b = usePublicBranding()
  return resolveAssetUrl(b?.logoUrl ?? null)
}

export function useBrandingInstanceName(): string | null {
  const b = usePublicBranding()
  const name = b?.instanceName?.trim()
  return name || null
}

export function useHidePoweredBy(): boolean {
  const b = usePublicBranding()
  // Tant que non chargé : ne pas afficher (évite de casser les maquettes login).
  if (!b) return true
  return b.hidePoweredBy
}

export { DEFAULT_ACCENT, resolveAssetUrl }
