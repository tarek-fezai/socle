// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/** @type {import('../src/lib/api-types.ts').components['schemas']['AdminOverviewView']} */
export const ADMIN_OVERVIEW_SEED = {
  oidc: {
    issuer: 'https://idp.example.com/realms/socle',
    clientId: 'socle-frontend',
    status: 'connected',
  },
  userCount: 214,
  spaceCount: 5,
  plan: {
    evaluationMode: false,
    edition: 'Entreprise',
    expiresAt: '2027-01-01T00:00:00.000Z',
  },
}

/** Horloge de la page /account : les jetons ci-dessous sont créés avant et expirent ≤ 90 jours après. */
export const ACCOUNT_VISUAL_NOW = Date.parse('2026-10-10T12:00:00.000Z')

/**
 * Deux jetons conformes à GET /api/v1/me/tokens. Le second est « lecture + écriture » AVEC expiration
 * (décision produit : expiration obligatoire ≤ 90 jours) — la maquette affiche « sans expiration ».
 * @type {import('../src/lib/api-types.ts').components['schemas']['PersonalAccessToken'][]}
 */
export const PAT_TOKENS_SEED = [
  {
    id: '7e210000-0000-4000-8000-000000000001',
    name: 'Script local — export de mes brouillons',
    last4: '7e21',
    scope: 'read',
    createdAt: '2026-09-14T12:00:00.000Z',
    expiresAt: '2026-12-12T12:00:00.000Z',
    lastUsedAt: new Date(ACCOUNT_VISUAL_NOW - 4 * 24 * 60 * 60_000).toISOString(),
    status: 'active',
  },
  {
    id: '3c480000-0000-4000-8000-000000000002',
    name: 'CLI Socle (poste de travail)',
    last4: '3c48',
    scope: 'read_write',
    createdAt: '2026-10-01T12:00:00.000Z',
    expiresAt: '2026-12-30T12:00:00.000Z',
    lastUsedAt: new Date(ACCOUNT_VISUAL_NOW - 40 * 60_000).toISOString(),
    status: 'active',
  },
]
