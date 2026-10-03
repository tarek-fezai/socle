// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Seed data for the document READ page visual tests — aligns with Main.dc.html /
 * MobilePage.dc.html numbers and copy ("Politique de gestion des accès").
 *
 * Pixel exceptions (documented in page-visual.spec.mjs):
 *  - draw.io figures and screenshot captures are NOT seeded as supported blocks: the
 *    maquette shows SVG / dashed placeholders there, the app renders the
 *    « Bloc non pris en charge dans cette version » placeholder. They sit below the
 *    900px fold on desktop and are excluded from the pixel frame.
 *  - « Champs personnalisés » is omitted in the app when the organisation has no
 *    field definitions (no API yet) → hidden in the maquette before comparing.
 */
import { ME_TAREK, SPACE_IDENTITE, TREE_IDENTITE, VISUAL_NOW } from './dashboard-fixtures.mjs'

export { ME_TAREK, SPACE_IDENTITE, TREE_IDENTITE, VISUAL_NOW }

export const PAGE_DOC_ID = 'd0000001-0000-4000-8000-000000000001'
export const CAMPAIGN_ID = 'c0000001-0000-4000-8000-000000000001'
export const RELATED_IDS = [
  'd0000001-0000-4000-8000-0000000000a1',
  'd0000001-0000-4000-8000-0000000000a2',
]

const text = (t, marks) => ({ type: 'text', text: t, ...(marks ? { marks } : {}) })
const p = (t) => ({ type: 'paragraph', content: [text(t)] })
const h2 = (t) => ({ type: 'heading', attrs: { level: 2 }, content: [text(t)] })
const cell = (t, type = 'tableCell') => ({ type, content: [p(t)] })
const row = (cells) => ({ type: 'tableRow', content: cells })

const LEAD_DESKTOP =
  "Cette politique définit les principes régissant l'attribution, la révision et la révocation des droits d'accès aux systèmes d'information de l'organisation. Elle s'applique à l'ensemble des collaborateurs, prestataires et comptes de service."
const LEAD_MOBILE =
  "Cette politique définit les principes régissant l'attribution, la révision et la révocation des droits d'accès aux systèmes d'information de l'organisation."

/** @param {'desktop'|'mobile'} variant */
export function pageBody(variant = 'desktop') {
  const mobile = variant === 'mobile'
  return {
    type: 'doc',
    content: [
      p(mobile ? LEAD_MOBILE : LEAD_DESKTOP),
      h2('Principe du moindre privilège'),
      p(
        mobile
          ? "Tout accès accordé doit être strictement nécessaire à l'exercice de la fonction, validé par le responsable hiérarchique et le propriétaire de la ressource."
          : "Tout accès accordé doit être strictement nécessaire à l'exercice de la fonction. Les demandes d'extension de périmètre font l'objet d'une validation par le responsable hiérarchique et le propriétaire de la ressource.",
      ),
      ...(mobile
        ? []
        : [
            {
              type: 'blockquote',
              content: [
                p(
                  "Toute exception doit être documentée et approuvée avant l'attribution effective de l'accès — aucune dérogation implicite n'est tolérée.",
                ),
              ],
            },
          ]),
      h2('Rôles et périmètres'),
      {
        type: 'table',
        content: [
          row([cell('Rôle', 'tableHeader'), cell('Portée', 'tableHeader'), cell('Revue', 'tableHeader')]),
          row([cell('Administrateur système'), cell('Infrastructure critique'), cell('Trimestrielle')]),
          row([cell('Analyste conformité'), cell('Registres & audits'), cell('Semestrielle')]),
        ],
      },
      // Exception pixel : draw.io / capture d'écran → bloc non pris en charge (sous la ligne de flottaison).
      {
        type: 'drawio',
        attrs: { caption: "Circuit d'approbation à deux niveaux ; un refus à N2 renvoie la demande au demandeur." },
      },
      { type: 'screenshot', attrs: { caption: 'Formulaire soumis par le demandeur, avec justification obligatoire au-delà de 90 jours.' } },
      h2('Flux de provisionnement'),
      p(
        "L'événement d'embauche déclenche la création du compte dans le moteur IGA, qui provisionne immédiatement l'annuaire central et synchronise les applications SaaS à échéance quotidienne.",
      ),
      { type: 'drawio', attrs: { caption: "Le SIRH déclenche le provisionnement immédiat de l'annuaire, la synchronisation SaaS suit en tâche planifiée." } },
    ],
  }
}

/** @param {'desktop'|'mobile'} variant */
export function pageDocument(variant = 'desktop') {
  return {
    id: PAGE_DOC_ID,
    spaceId: SPACE_IDENTITE.id,
    folderId: null,
    title: 'Politique de gestion des accès',
    docType: 'politique',
    body: pageBody(variant),
    status: 'valide',
    currentVersionNo: 12,
    createdAt: '2026-07-14T09:00:00.000Z',
    updatedAt: '2026-09-12T12:22:00.000Z',
    contentModifiedAt: '2026-09-12T12:22:00.000Z',
    // 12 septembre 2026 + 181 j = 12 mars 2027 (« Prochaine revue »)
    stalenessThresholdDays: 181,
    reliabilityScore: 91,
    stale: false,
    createdBy: null, // « Système (migration) »
    updatedBy: { id: ME_TAREK.id, displayName: ME_TAREK.displayName, initials: 'TF' },
    owner: { id: ME_TAREK.id, displayName: ME_TAREK.displayName, initials: 'TF' },
    tags: [
      { id: 'tag-iam', name: 'IAM', color: '#3730E0' },
      { id: 'tag-rgpd', name: 'RGPD', color: '#B7791F' },
      { id: 'tag-critique', name: 'Critique', color: '#B54708' },
    ],
    permissions: {
      canEdit: true,
      // Maquette Main.dc.html affiche « Publier » même en Validé — conservé pour la parité visuelle.
      canPublish: true,
      canManageAccess: true,
      canComment: true,
      canManageAttestations: true,
    },
  }
}

export const PAGE_ATTESTATION = {
  campaignId: CAMPAIGN_ID,
  documentId: PAGE_DOC_ID,
  versionNo: 12,
  currentVersionNo: 12,
  audienceType: 'space_members',
  dueDate: '2026-10-03',
  overdue: false,
  acknowledged: false,
  ackCount: 742,
  audienceSize: 856,
  createdAt: '2026-09-01T00:00:00.000Z',
}

export const PAGE_LINKS = {
  outgoing: [
    { id: RELATED_IDS[0], title: 'Procédure de provisioning des comptes' },
    { id: RELATED_IDS[1], title: 'Référentiel des rôles & habilitations' },
  ],
  incoming: [],
}

export const PAGE_COMMENTS = {
  documentId: PAGE_DOC_ID,
  versionNo: 12,
  threads: [],
  detached: [],
  openThreadCount: 3,
}

export const PAGE_FEEDBACK_EDITOR = { myVote: null, totals: { yes: 18, no: 2 } }
export const PAGE_FEEDBACK_VIEWER = { myVote: null }

/** Document seed for a viewer (no edit/publish). */
export function pageDocumentViewer(variant = 'desktop') {
  const d = pageDocument(variant)
  return {
    ...d,
    permissions: {
      canEdit: false,
      canPublish: false,
      canManageAccess: false,
      canComment: true,
      canManageAttestations: false,
    },
  }
}
