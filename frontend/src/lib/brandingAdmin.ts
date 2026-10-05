// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'

export type BrandingAdminView = {
  instanceName: string
  accentColor: string | null
  hidePoweredBy: boolean
  senderName: string | null
  senderEmail: string | null
  hasLogo: boolean
  hasFavicon: boolean
  logoUrl: string | null
  faviconUrl: string | null
  publicBaseUrl: string | null
  publicBaseUrlNote: string | null
  updatedAt: string | null
}

export type UpdateBrandingRequest = {
  accentColor: string | null
  hidePoweredBy: boolean
  senderName: string | null
  senderEmail: string | null
}

export type PublicBrandingView = {
  instanceName: string
  accentColor: string | null
  logoUrl: string | null
  faviconUrl: string | null
  hidePoweredBy: boolean
}

export type TestEmailResponse = {
  status: string
  to: string
  from: string
  channel: string
}

/** Nuancier Branding.dc.html (+ personnalisé via color picker). */
export const BRANDING_ACCENT_SWATCHES = [
  '#3730E0',
  '#0D8A7C',
  '#B54708',
  '#7C3AED',
  '#0E0E10',
] as const

export const DEFAULT_ACCENT = '#3730E0'

export function brandingAdminKey() {
  return ['admin', 'branding'] as const
}

export function publicBrandingKey() {
  return ['public', 'branding'] as const
}

export async function getAdminBranding(api: Pick<AxiosInstance, 'get'>) {
  const { data } = await api.get<BrandingAdminView>('/api/v1/admin/branding')
  return data
}

export async function updateAdminBranding(
  api: Pick<AxiosInstance, 'put'>,
  body: UpdateBrandingRequest,
) {
  const { data } = await api.put<BrandingAdminView>('/api/v1/admin/branding', body)
  return data
}

export async function uploadBrandingLogo(api: Pick<AxiosInstance, 'post'>, file: File) {
  const form = new FormData()
  form.append('file', file)
  const { data } = await api.post<BrandingAdminView>('/api/v1/admin/branding/logo', form)
  return data
}

export async function removeBrandingLogo(api: Pick<AxiosInstance, 'delete'>) {
  const { data } = await api.delete<BrandingAdminView>('/api/v1/admin/branding/logo')
  return data
}

export async function uploadBrandingFavicon(api: Pick<AxiosInstance, 'post'>, file: File) {
  const form = new FormData()
  form.append('file', file)
  const { data } = await api.post<BrandingAdminView>('/api/v1/admin/branding/favicon', form)
  return data
}

export async function removeBrandingFavicon(api: Pick<AxiosInstance, 'delete'>) {
  const { data } = await api.delete<BrandingAdminView>('/api/v1/admin/branding/favicon')
  return data
}

export async function sendBrandingTestEmail(
  api: Pick<AxiosInstance, 'post'>,
  to?: string | null,
) {
  const { data } = await api.post<TestEmailResponse>(
    '/api/v1/admin/branding/test-email',
    to ? { to } : {},
  )
  return data
}

export async function getPublicBranding(api: Pick<AxiosInstance, 'get'>) {
  const { data } = await api.get<PublicBrandingView>('/api/v1/public/branding')
  return data
}

export function normalizeAccent(color: string | null | undefined): string {
  if (!color) return DEFAULT_ACCENT
  const t = color.trim().toUpperCase()
  return /^#[0-9A-F]{6}$/.test(t) ? t : DEFAULT_ACCENT
}

export function displayPublicBaseUrl(url: string | null | undefined): string {
  if (!url) return 'Non configurée'
  return url.replace(/^https?:\/\//i, '').replace(/\/$/, '')
}
