// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { components } from './api-types'
import { api } from './api'

export type PersonalAccessToken = components['schemas']['PersonalAccessToken']
export type PersonalAccessTokenScope = components['schemas']['PersonalAccessTokenCreateRequest']['scope']
export type PersonalAccessTokenCreated = components['schemas']['PersonalAccessTokenCreated']

/** Décision produit : expiration obligatoire, 90 jours maximum (pas d'option « sans expiration »). */
export const PAT_EXPIRY_PRESETS = [7, 30, 60, 90] as const
export const PAT_DEFAULT_EXPIRY_DAYS = 90
export const PAT_DEFAULT_NAME = 'Jeton — sans nom'

export async function listPersonalTokens(): Promise<PersonalAccessToken[]> {
  const { data } = await api.get<PersonalAccessToken[]>('/api/v1/me/tokens')
  return Array.isArray(data) ? data : []
}

export async function createPersonalToken(body: {
  name: string
  scope: PersonalAccessTokenScope
  expiresInDays: number
}): Promise<PersonalAccessTokenCreated> {
  const { data } = await api.post<PersonalAccessTokenCreated>('/api/v1/me/tokens', body)
  return data
}

export async function revokePersonalToken(id: string): Promise<void> {
  await api.delete(`/api/v1/me/tokens/${encodeURIComponent(id)}`)
}

export function scopeLabel(scope: PersonalAccessToken['scope']): string {
  return scope === 'read_write' ? 'lecture + écriture' : 'lecture seule'
}

const DATE_FMT = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' })

export function formatExpiry(token: PersonalAccessToken, now: number = Date.now()): string {
  if (!token.expiresAt) return ''
  const at = Date.parse(token.expiresAt)
  const date = DATE_FMT.format(new Date(at))
  return at <= now ? `expiré le ${date}` : `expire le ${date}`
}

export function formatLastUsed(lastUsedAt: string | null | undefined, now: number = Date.now()): string {
  if (!lastUsedAt) return 'jamais utilisé'
  const minutes = Math.max(0, Math.floor((now - Date.parse(lastUsedAt)) / 60_000))
  if (minutes < 1) return 'utilisé à l’instant'
  if (minutes < 60) return `utilisé il y a ${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `utilisé il y a ${hours} h`
  return `utilisé il y a ${Math.floor(hours / 24)}j`
}

export function maskedToken(last4: string | undefined): string {
  return `pat_••••••••••••${last4 ?? ''}`
}

/** Code Problem Detail stable renvoyé par l'API (pat_expiry_invalid, pat_limit_reached…). */
export function problemCode(error: unknown): string | undefined {
  const data = (error as { response?: { data?: { code?: unknown } } })?.response?.data
  return typeof data?.code === 'string' ? data.code : undefined
}
