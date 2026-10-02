// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Seed data for dashboard visual tests — aligns with Dashboard.dc.html numbers/copy.
 */

export const VISUAL_NOW = Date.parse('2026-09-10T12:00:00.000Z')

export const ME_TAREK = {
  id: '11111111-1111-1111-1111-111111111111',
  email: 'tarek.fezai@example.com',
  displayName: 'Tarek Fezai',
  avatarInitials: 'TF',
  givenName: 'Tarek',
  roles: ['CONTRIBUTEUR', 'ADMINISTRATEUR_SYSTEME'],
}

export const HOME_SEED = {
  greetingFirstName: 'Tarek',
  kpis: {
    publishedDocuments: 128,
    pendingApprovals: 6,
    viewsThisMonth: 4302,
    averageReliabilityPercent: 91,
  },
  resume: [
    {
      documentId: 'd0000001-0000-4000-8000-000000000001',
      title: 'Politique de gestion des accès',
      status: 'brouillon',
      modifiedAt: new Date(VISUAL_NOW - 20 * 60_000).toISOString(),
    },
    {
      documentId: 'd0000001-0000-4000-8000-000000000002',
      title: "Plan de reprise d'activité",
      status: 'brouillon',
      modifiedAt: new Date(VISUAL_NOW - 26 * 60 * 60_000).toISOString(),
    },
  ],
  recentlyPublished: [
    {
      documentId: 'd0000001-0000-4000-8000-000000000003',
      title: 'Registre des traitements',
      spaceName: 'Conformité',
      publishedAt: '2026-08-30T10:00:00.000Z',
    },
    {
      documentId: 'd0000001-0000-4000-8000-000000000004',
      title: 'Architecture réseau',
      spaceName: 'Infrastructure',
      publishedAt: '2026-09-02T10:00:00.000Z',
    },
  ],
  pendingYourApproval: [
    {
      approvalId: 'a0000001-0000-4000-8000-000000000001',
      documentId: 'd0000001-0000-4000-8000-000000000001',
      title: 'Politique de gestion des accès',
      requesterName: 'Claire Dubois',
      detail: 'Claire Dubois demande une approbation · SLA 22h restantes',
      detailShort: 'Claire Dubois · SLA 22h restantes',
      slaRemainingHours: 22,
      kind: 'approval',
    },
    {
      approvalId: 'a0000001-0000-4000-8000-000000000002',
      documentId: 'd0000001-0000-4000-8000-000000000005',
      title: 'Procédure de provisioning',
      requesterName: 'Yanis M.',
      detail: 'Modifications proposées par Yanis M.',
      detailShort: 'Modifications proposées par Yanis M.',
      kind: 'review',
    },
  ],
  teamActivity: [
    {
      id: 'act-1',
      actorName: 'Claire Dubois',
      actorInitials: 'CD',
      actorBg: '#EEEDFD',
      actorFg: '#3730E0',
      action: 'a commenté',
      documentTitle: 'Revue périodique des droits',
      documentId: 'd0000001-0000-4000-8000-000000000006',
      occurredAt: new Date(VISUAL_NOW - 60 * 60_000).toISOString(),
    },
    {
      id: 'act-2',
      actorName: 'Yanis M.',
      actorInitials: 'YM',
      actorBg: '#F2E4DC',
      actorFg: '#8A4A2E',
      action: 'a proposé une modification',
      documentTitle: null,
      occurredAt: new Date(VISUAL_NOW - 3 * 60 * 60_000).toISOString(),
    },
    {
      id: 'act-3',
      actorName: 'Tarek Fezai',
      actorInitials: 'TF',
      actorBg: '#0E0E10',
      actorFg: '#FFFFFF',
      isSelf: true,
      action: 'publié',
      documentTitle: 'Registre des traitements',
      documentId: 'd0000001-0000-4000-8000-000000000003',
      occurredAt: new Date(VISUAL_NOW - 26 * 60 * 60_000).toISOString(),
    },
  ],
}

export const SPACE_IDENTITE = {
  id: 's0000001-0000-4000-8000-000000000001',
  name: 'Identité & accès',
  color: '#3730E0',
  createdAt: '2026-01-01T00:00:00.000Z',
  canManage: true,
  isOwner: true,
  isResponsible: true,
  membership: 'member',
}

export const SPACE_INFRA = {
  id: 's0000001-0000-4000-8000-000000000002',
  name: 'Infrastructure',
  color: '#0E0E10',
  createdAt: '2026-01-01T00:00:00.000Z',
  canManage: true,
  isOwner: true,
  isResponsible: true,
  membership: 'member',
}

export const TREE_IDENTITE = {
  spaceId: SPACE_IDENTITE.id,
  spaceName: SPACE_IDENTITE.name,
  folders: [
    {
      id: 'f0000001-0000-4000-8000-000000000001',
      name: 'Procédures',
      parentFolderId: null,
      position: 0,
      documentCount: 3,
      folderCount: 0,
      documents: [
        {
          id: 'd0000001-0000-4000-8000-000000000010',
          title: 'Provisioning des comptes',
          folderId: 'f0000001-0000-4000-8000-000000000001',
          position: 0,
          status: 'valide',
        },
        {
          id: 'd0000001-0000-4000-8000-000000000011',
          title: 'Dé-provisioning des comptes',
          folderId: 'f0000001-0000-4000-8000-000000000001',
          position: 1,
          status: 'valide',
        },
        {
          id: 'd0000001-0000-4000-8000-000000000016',
          title: 'Revue périodique des droits',
          folderId: 'f0000001-0000-4000-8000-000000000001',
          position: 2,
          status: 'valide',
        },
      ],
    },
    {
      id: 'f0000001-0000-4000-8000-000000000002',
      name: 'Référence',
      parentFolderId: null,
      position: 1,
      documentCount: 2,
      folderCount: 0,
      documents: [
        {
          id: 'd0000001-0000-4000-8000-000000000017',
          title: 'Rôles & habilitations',
          folderId: 'f0000001-0000-4000-8000-000000000002',
          position: 0,
          status: 'valide',
        },
        {
          id: 'd0000001-0000-4000-8000-000000000013',
          title: 'Glossaire des permissions',
          folderId: 'f0000001-0000-4000-8000-000000000002',
          position: 1,
          status: 'valide',
        },
      ],
    },
  ],
  documents: [
    {
      id: 'd0000001-0000-4000-8000-000000000001',
      title: 'Politique de gestion des accès',
      folderId: null,
      position: 0,
      status: 'brouillon',
    },
    {
      id: 'd0000001-0000-4000-8000-000000000014',
      title: 'Procédure de provisioning',
      folderId: null,
      position: 1,
      status: 'valide',
    },
    {
      id: 'd0000001-0000-4000-8000-000000000006',
      title: 'Revue périodique des droits',
      folderId: null,
      position: 2,
      status: 'valide',
    },
    {
      id: 'd0000001-0000-4000-8000-000000000012',
      title: 'Rôles & habilitations',
      folderId: null,
      position: 3,
      status: 'valide',
    },
  ],
}

export const TREE_INFRA = {
  spaceId: SPACE_INFRA.id,
  spaceName: SPACE_INFRA.name,
  folders: [],
  documents: [
    {
      id: 'd0000001-0000-4000-8000-000000000004',
      title: 'Architecture réseau',
      folderId: null,
      position: 0,
      status: 'valide',
    },
    {
      id: 'd0000001-0000-4000-8000-000000000002',
      title: "Plan de reprise d'activité",
      folderId: null,
      position: 1,
      status: 'brouillon',
    },
  ],
}

export const FAVORITES_SEED = { items: [] }

export const NOTIFICATIONS_SEED = {
  items: [],
  offset: 0,
  limit: 1,
  total: 0,
  unreadCount: 3,
}
