// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Fixtures Historique / Comparaison / Restauration — alignées sur History.dc.html, Diff.dc.html,
 * RestoreVersion.dc.html et MobileHistory.dc.html. Dates en UTC : le navigateur de test est en
 * Europe/Paris (UTC+2 en été), donc 12:22Z s'affiche « 14:22 ».
 */
import { ME_TAREK } from './dashboard-fixtures.mjs'
import { PAGE_DOC_ID, pageDocument } from './page-fixtures.mjs'

export { PAGE_DOC_ID as HIST_DOC_ID }

const CLAIRE_ID = '22222222-2222-2222-2222-222222222222'

const tarek = { authorId: ME_TAREK.id, authorDisplayName: ME_TAREK.displayName, authorInitials: 'TF' }
const claire = { authorId: CLAIRE_ID, authorDisplayName: 'Claire Dubois', authorInitials: 'CD' }
const system = { authorId: null, authorDisplayName: 'Système (migration)', authorInitials: '⚙' }

export const HIST_SUMMARY_CURRENT = "Clarification du circuit d'approbation N2 et ajout du schéma de refus"

/** Document : la version courante est la v12 (comme la maquette). */
export function historyDocument({ editor = true, status = 'valide' } = {}) {
  const d = pageDocument('desktop')
  return {
    ...d,
    status,
    permissions: editor
      ? d.permissions
      : { ...d.permissions, canEdit: false, canPublish: false, canManageAccess: false, canManageAttestations: false },
  }
}

/** History.dc.html : v12, v11, v10, v9 sur 12 versions au total. */
export const HIST_VERSIONS_DESKTOP = [
  {
    versionNo: 12,
    ...tarek,
    archivedBy: null,
    changeSummary: HIST_SUMMARY_CURRENT,
    createdAt: '2026-09-12T12:22:00.000Z',
    linesAdded: 18,
    linesRemoved: 4,
    current: true,
  },
  {
    versionNo: 11,
    ...claire,
    archivedBy: null,
    changeSummary: "Ajout du tableau des rôles et des périmètres d'accès",
    createdAt: '2026-09-03T07:41:00.000Z',
    linesAdded: 32,
    linesRemoved: 0,
    current: false,
  },
  {
    versionNo: 10,
    ...tarek,
    archivedBy: null,
    changeSummary: 'Révision annuelle — mise à jour des seuils de revue',
    createdAt: '2026-08-28T14:05:00.000Z',
    linesAdded: 6,
    linesRemoved: 11,
    current: false,
  },
  {
    versionNo: 9,
    ...system,
    archivedBy: null,
    changeSummary: "Import initial depuis l'ancien wiki IAM",
    createdAt: '2026-07-14T09:00:00.000Z',
    linesAdded: 210,
    linesRemoved: 0,
    current: false,
  },
]
export const HIST_TOTAL_DESKTOP = 12

/**
 * RestoreVersion.dc.html : le résumé de la v12 est plus court (« Clarification du circuit
 * d'approbation N2 ») que celui de History.dc.html — l'encart d'avertissement tient sur une ligne.
 */
export const HIST_VERSIONS_RESTORE = HIST_VERSIONS_DESKTOP.map((v) =>
  v.versionNo === 12 ? { ...v, changeSummary: "Clarification du circuit d'approbation N2" } : v,
)

/** MobileHistory.dc.html : v12, v11, v1 (système). */
export const HIST_VERSIONS_MOBILE = [
  { ...HIST_VERSIONS_DESKTOP[0] },
  { ...HIST_VERSIONS_DESKTOP[1], createdAt: '2026-09-02T07:10:00.000Z' },
  {
    versionNo: 1,
    ...system,
    archivedBy: null,
    changeSummary: 'Import initial',
    createdAt: '2026-07-14T09:00:00.000Z',
    linesAdded: 210,
    linesRemoved: 0,
    current: false,
  },
]

/** Page `GET …/versions?offset&limit` (honore `offset`). */
export function versionPage(items, total, url) {
  const u = new URL(url)
  const offset = Number(u.searchParams.get('offset') ?? 0)
  const limit = Number(u.searchParams.get('limit') ?? 50)
  return { items: items.slice(offset, offset + limit), offset, limit, total }
}

const L = (kind, oldNo, newNo, text, spans) => ({ kind, oldNo, newNo, text, ...(spans ? { spans } : {}) })

/** Diff.dc.html : v11 → v12 (+18 −4), trois blocs. */
export const COMPARE_11_12 = {
  documentId: PAGE_DOC_ID,
  fromVersion: 11,
  toVersion: 12,
  added: 18,
  removed: 4,
  hunks: [
    {
      header: '1. Principe du moindre privilège',
      collapsedUnchanged: 0,
      lines: [
        L('context', 1, 1, 'Tout accès accordé doit être strictement'),
        L('context', 2, 2, "nécessaire à l'exercice de la fonction."),
        L('del', 3, null, "Les demandes d'extension de périmètre sont validées"),
        L('del', 4, null, 'par le responsable hiérarchique ou un membre', [
          { kind: 'eq', text: 'par le responsable hiérarchique ' },
          { kind: 'del', text: 'ou un membre' },
        ]),
        L('del', 5, null, "de l'équipe Identité.", [{ kind: 'del', text: "de l'équipe Identité." }]),
        L('add', null, 3, "Les demandes d'extension de périmètre sont validées"),
        L('add', null, 4, 'par le responsable hiérarchique et le propriétaire', [
          { kind: 'eq', text: 'par le responsable hiérarchique ' },
          { kind: 'add', text: 'et le propriétaire' },
        ]),
        L('add', null, 5, 'de la ressource concernée.', [{ kind: 'add', text: 'de la ressource concernée.' }]),
      ],
    },
    {
      header: '3. Flux de provisionnement — nouvelle section',
      collapsedUnchanged: 0,
      lines: [
        L('add', null, 1, "L'événement d'embauche déclenche la création"),
        L('add', null, 2, 'du compte dans le moteur IGA, qui provisionne'),
        L('add', null, 3, "immédiatement l'annuaire central et synchronise"),
        L('add', null, 4, 'les applications SaaS à échéance quotidienne.'),
        L('add', null, 5, "Un circuit d'approbation à deux niveaux illustre"),
        L('add', null, 6, 'le traitement des refus (voir schéma).'),
      ],
    },
    {
      header: '2. Rôles et périmètres',
      collapsedUnchanged: 2,
      lines: [
        L('context', 6, 7, 'Les rôles suivants sont soumis à un cycle'),
        L('context', 7, 8, 'de revue formalisé.'),
      ],
    },
  ],
}
