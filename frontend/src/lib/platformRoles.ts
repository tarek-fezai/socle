// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { SocleRole, type SocleRoleName } from './auth'

const LABELS: Record<SocleRoleName, string> = {
  [SocleRole.CONTRIBUTEUR]: 'Contributeur',
  [SocleRole.AUDITEUR]: 'Auditeur',
  [SocleRole.INTEGRATEUR]: 'Intégrateur',
  [SocleRole.ADMINISTRATEUR_SYSTEME]: 'Administrateur système',
}

/** Libellés affichables à partir des rôles renvoyés par GET /api/v1/me. */
export function formatPlatformRoleLabels(roles: string[] | undefined): string {
  if (!roles?.length) return ''
  const seen = new Set<string>()
  const parts: string[] = []
  for (const raw of roles) {
    const label = LABELS[raw as SocleRoleName] ?? raw
    if (seen.has(label)) continue
    seen.add(label)
    parts.push(label)
  }
  return parts.join(', ')
}

export function isPlatformAdmin(roles: string[] | undefined): boolean {
  return Boolean(roles?.includes(SocleRole.ADMINISTRATEUR_SYSTEME))
}
