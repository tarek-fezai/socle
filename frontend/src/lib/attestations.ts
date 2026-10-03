// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'
import type { components } from './api-types'

/** Campagne ouverte vue par un utilisateur concerné (GET …/attestations/active). */
export type ActiveAttestation = components['schemas']['ActiveAttestation'] & {
  campaignId: string
  documentId: string
  versionNo: number
  currentVersionNo: number
  audienceType: string
  dueDate: string
  overdue: boolean
  acknowledged: boolean
}

type Client = Pick<AxiosInstance, 'get' | 'post'>

/** Campagne ouverte concernant l'utilisateur courant ; `null` si aucune (204). */
export async function getActiveAttestation(
  client: Client,
  documentId: string,
): Promise<ActiveAttestation | null> {
  const res = await client.get<ActiveAttestation | ''>(
    `/api/v1/documents/${documentId}/attestations/active`,
    { validateStatus: (s) => s === 200 || s === 204 },
  )
  if (res.status === 204 || !res.data) return null
  return res.data as ActiveAttestation
}

/** « J'ai lu et compris » — retourne la campagne mise à jour. */
export async function acknowledgeAttestation(
  client: Client,
  documentId: string,
  campaignId: string,
): Promise<ActiveAttestation> {
  const { data } = await client.post<ActiveAttestation>(
    `/api/v1/documents/${documentId}/attestations/${campaignId}/acknowledge`,
  )
  return data
}

export const attestationKey = (documentId: string) => ['attestation-active', documentId] as const

/** Bannière visible : campagne ouverte et accusé non encore enregistré. */
export function shouldShowAttestationBanner(a: ActiveAttestation | null | undefined): boolean {
  return Boolean(a) && a!.acknowledged !== true
}

/** « 3 octobre 2026 » à partir d'une date ISO (yyyy-MM-dd) — sans décalage de fuseau. */
export function formatAttestationDueDate(iso: string | null | undefined): string | null {
  if (!iso) return null
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso)
  if (!m) return null
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])))
  if (Number.isNaN(d.getTime())) return null
  return d.toLocaleDateString('fr-FR', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  })
}
