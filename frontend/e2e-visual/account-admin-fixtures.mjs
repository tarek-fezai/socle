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
