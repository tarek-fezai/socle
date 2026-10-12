// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * GET /api/v1/admin/users — AdminUsersResponse (après export OpenAPI).
 */
import { ME_TAREK, VISUAL_NOW } from './dashboard-fixtures.mjs'

const iso = (ms) => new Date(ms).toISOString()

export const TEAM_ADMIN_USERS = {
  users: [
    {
      id: ME_TAREK.id,
      displayName: ME_TAREK.displayName,
      email: ME_TAREK.email,
      initials: 'TF',
      platformRoles: ['ADMINISTRATEUR_SYSTEME'],
      teams: [{ id: 'g0000001-0000-4000-8000-000000000001', name: 'Identité & accès', role: 'proprietaire' }],
      status: 'active',
      lastActivityAt: iso(VISUAL_NOW - 20 * 60_000),
    },
  ],
  total: 1,
  activeCount: 214,
  teamCount: 5,
  offset: 0,
  limit: 200,
}
