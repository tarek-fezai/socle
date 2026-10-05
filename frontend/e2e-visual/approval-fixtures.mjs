// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Fixtures Approbation / Comparaison d'approbation / Mobile — alignées sur Approval.dc.html,
 * DiffApproval.dc.html et MobileApproval.dc.html.
 *
 * Horloge figée à APPR_NOW (28 septembre 2026 · 11:14 Paris) : la demande a été soumise le
 * 27 septembre 2026 · 09:14 (07:14Z) avec un SLA de 48 h → « Dans 22h · SLA 48h ».
 * Les dates sont en UTC : le navigateur de test est en Europe/Paris (UTC+2 en septembre).
 */
import { PAGE_DOC_ID } from './page-fixtures.mjs'

export { PAGE_DOC_ID as APPR_DOC_ID }

export const APPR_REQUEST_ID = 'a0000001-0000-4000-8000-000000000001'
export const APPR_LINK_PROVISIONING_ID = 'd0000002-0000-4000-8000-000000000002'
export const APPR_LINK_REVIEW_ID = 'd0000003-0000-4000-8000-000000000003'
const CLAIRE_ID = '22222222-2222-2222-2222-222222222222'

export const APPR_NOW = Date.parse('2026-09-28T09:14:00.000Z')

const CREATED_AT = '2026-09-27T07:14:00.000Z'
const DEADLINE_AT = new Date(Date.parse(CREATED_AT) + 48 * 3_600_000).toISOString()

/**
 * File `/approvals/mine` : plus de liens impactés (tableaux vides / hiddenImpactedCount: 0).
 * Les pixel-tests de décision utilisent `APPR_DETAIL_*` via `GET /approvals/{id}`.
 */
export const APPR_ITEM_DESKTOP = {
  approvalRequestId: APPR_REQUEST_ID,
  documentId: PAGE_DOC_ID,
  documentTitle: 'Politique de gestion des accès',
  temporalWorkflowId: 'doc-approval-visual',
  status: 'en_cours',
  currentStepOrder: 2,
  slaDeadlineAt: DEADLINE_AT,
  submittedVersionNo: 13,
  baselineVersionNo: 12,
  requestedBy: CLAIRE_ID,
  createdAt: CREATED_AT,
  requestedByDisplayName: 'Claire Dubois',
  requestedByInitials: 'CD',
  impactedLinks: [],
  hiddenImpactedCount: 0,
}

/** MobileApproval — file mine (N1), sans liens. */
export const APPR_ITEM_MOBILE = {
  ...APPR_ITEM_DESKTOP,
  currentStepOrder: 1,
}

/** Détail décision (Approval.dc.html) — canDecide + liens entrants filtrés. */
export const APPR_DETAIL_DESKTOP = {
  ...APPR_ITEM_DESKTOP,
  impactedLinks: [
    { id: APPR_LINK_PROVISIONING_ID, title: 'Procédure de provisioning' },
    { id: APPR_LINK_REVIEW_ID, title: 'Revue périodique des droits' },
  ],
  hiddenImpactedCount: 0,
  canDecide: true,
  cannotDecideReason: null,
}

/** Détail mobile (MobileApproval.dc.html) — canDecide, aucun lien (exception M3). */
export const APPR_DETAIL_MOBILE = {
  ...APPR_ITEM_MOBILE,
  impactedLinks: [],
  hiddenImpactedCount: 0,
  canDecide: true,
  cannotDecideReason: null,
}

/** Mode lecture seule (exception A9 vs maquette — badge LECTURE, pas de boutons). */
export const APPR_DETAIL_READONLY = {
  ...APPR_DETAIL_DESKTOP,
  canDecide: false,
  cannotDecideReason: 'requester',
}

export const APPR_SUMMARY_DESKTOP =
  'le nouveau flux de provisionnement et la reformulation du circuit de validation'
export const APPR_SUMMARY_MOBILE =
  "Ajout d'un délai de traitement de 24h pour les comptes de service, section 1."

/** Circuit applicable (`ApplicableView`) — rôles de la maquette desktop. */
export const APPR_WORKFLOW_DESKTOP = {
  id: 'f0000001-0000-4000-8000-000000000001',
  name: 'Approbation standard',
  stepCount: 2,
  matchLevel: 'global',
  steps: [
    { id: 'f1000001-0000-4000-8000-000000000001', stepOrder: 1, slaHours: 24, approverRoleName: 'Responsable' },
    { id: 'f1000002-0000-4000-8000-000000000002', stepOrder: 2, slaHours: 24, approverRoleName: 'Propriétaire' },
  ],
}

/** Circuit applicable — rôles de la maquette mobile. */
export const APPR_WORKFLOW_MOBILE = {
  ...APPR_WORKFLOW_DESKTOP,
  steps: [
    { ...APPR_WORKFLOW_DESKTOP.steps[0], approverRoleName: 'Manager' },
    { ...APPR_WORKFLOW_DESKTOP.steps[1], approverRoleName: 'Propriétaire ressource' },
  ],
}

function version(versionNo, changeSummary, current) {
  return {
    versionNo,
    authorId: CLAIRE_ID,
    authorDisplayName: 'Claire Dubois',
    authorInitials: 'CD',
    archivedBy: null,
    changeSummary,
    createdAt: '2026-09-27T07:14:00.000Z',
    linesAdded: 18,
    linesRemoved: 4,
    current,
  }
}

export const APPR_VERSIONS_DESKTOP = [
  version(13, APPR_SUMMARY_DESKTOP, true),
  version(12, 'Révision précédente approuvée', false),
]
export const APPR_VERSIONS_MOBILE = [
  version(13, APPR_SUMMARY_MOBILE, true),
  version(12, 'Révision précédente approuvée', false),
]

const L = (kind, oldNo, newNo, text) => ({ kind, oldNo, newNo, text })

/** DiffApproval.dc.html : v12 → v13 (+18 −4), trois blocs. */
export const APPR_COMPARE_12_13 = {
  documentId: PAGE_DOC_ID,
  fromVersion: 12,
  toVersion: 13,
  added: 18,
  removed: 4,
  hunks: [
    {
      header: "Circuit d'approbation — reformulation",
      collapsedUnchanged: 0,
      lines: [
        L('del', 1, null, 'Toute révision est validée par le responsable'),
        L('del', 2, null, 'hiérarchique du demandeur avant publication.'),
        L('add', null, 1, 'Toute révision est validée successivement par le'),
        L('add', null, 2, 'responsable N1 puis par le propriétaire N2 de la'),
        L('add', null, 3, 'ressource avant toute publication.'),
        L('context', 3, 4, 'Un refus doit être motivé par écrit.'),
      ],
    },
    {
      header: 'Nouveau flux de provisionnement — section ajoutée',
      collapsedUnchanged: 0,
      lines: [
        L('add', null, 5, "Le moteur IGA synchronise désormais l'annuaire"),
        L('add', null, 6, 'central en continu et non plus à échéance'),
        L('add', null, 7, 'quotidienne, réduisant le délai de révocation'),
        L('add', null, 8, 'des accès en cas de départ.'),
      ],
    },
    {
      header: '2. Rôles et périmètres',
      collapsedUnchanged: 2,
      lines: [
        L('context', 4, 9, 'Les rôles suivants sont soumis à un cycle'),
        L('context', 5, 10, 'de revue formalisé.'),
      ],
    },
  ],
}
