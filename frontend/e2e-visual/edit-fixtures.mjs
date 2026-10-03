// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Seed data for the document EDIT screen visual tests — aligns with Edit.dc.html
 * ("Politique de gestion des accès", 14:22, « 1 240 mots · 6 min de lecture »).
 *
 * Choix de fixture (voir l'en-tête de edit-visual.spec.mjs pour la liste complète des exceptions) :
 *  - le seul éditeur est l'utilisateur courant et il détient le verrou : pas de bandeau de co-édition,
 *    un seul avatar de présence ;
 *  - le corps ne contient que des nœuds StarterKit : pas de puce Jira / date, pas de callout, pas de
 *    case à cocher, pas de lien réduit (la maquette est normalisée dans le spec) ;
 *  - des paragraphes de remplissage SOUS la ligne de flottaison amènent le compteur à exactement
 *    1 240 mots, comme la maquette ;
 *  - le seuil « paragraphe long » du serveur est calé entre le chapeau et le paragraphe « en cours
 *    d'édition » : seule la carte « Paragraphe long » de la maquette apparaît ;
 *  - 3 tags (IAM, RGPD, Critique) et 2 champs personnalisés (Référence réglementaire, Système concerné).
 */
import { ME_TAREK, SPACE_IDENTITE, TREE_IDENTITE, VISUAL_NOW } from './dashboard-fixtures.mjs'
import { CAMPAIGN_ID, PAGE_COMMENTS, pageDocument } from './page-fixtures.mjs'

export { ME_TAREK, SPACE_IDENTITE, TREE_IDENTITE, VISUAL_NOW, CAMPAIGN_ID, PAGE_COMMENTS }

export const EDIT_DOC_ID = 'd0000001-0000-4000-8000-000000000001'
export const EDIT_TARGET_WORDS = 1240

const text = (t) => ({ type: 'text', text: t })
const p = (t) => ({ type: 'paragraph', content: [text(t)] })
const h2 = (t) => ({ type: 'heading', attrs: { level: 2 }, content: [text(t)] })
const li = (t) => ({ type: 'listItem', content: [p(t)] })

export const LEAD =
  "Cette politique définit les principes régissant l'attribution, la révision et la révocation des droits d'accès aux systèmes d'information de l'organisation. Elle s'applique à l'ensemble des collaborateurs, prestataires et comptes de service."
export const EDITING_PARAGRAPH =
  "Tout accès accordé doit être strictement nécessaire à l'exercice de la fonction. Les demandes d'extension de périmètre font l'objet d'une validation par le responsable hiérarchique et le propriétaire de la ressource — paragraphe en cours d'édition."
export const CHANTIER =
  'Le chantier de mise en conformité est suivi dans IAM-482 Durcir la revue N2 et doit être clôturé avant la revue du 15 octobre 2026.'
const CODE = "okta-cli users list --group priv-access \\\n  --format json | socle audit --policy iam-482"
const STEPS = [
  'Soumission de la demande par le collaborateur ou son manager, avec justification métier.',
  'Validation par le propriétaire de la ressource concernée.',
  'Provisioning automatique via la synchronisation SCIM.',
  'Revue périodique lors du cycle trimestriel de recertification.',
]

/** Même règle que countWordsFromTipTap : segments à espaces contenant une lettre ou un chiffre. */
export function countWords(str) {
  return str
    .split(/\s+/)
    .filter((tok) => tok && /[\p{L}\p{N}]/u.test(tok)).length
}

const FILLER_SENTENCE =
  'Les accès à privilèges sont recertifiés chaque trimestre par le propriétaire de la ressource concernée'

function baseBlocks() {
  return [
    p(LEAD),
    h2('1. Principe du moindre privilège'),
    p(EDITING_PARAGRAPH),
    p(CHANTIER),
    { type: 'codeBlock', content: [text(CODE)] },
    h2("2. Cycle de vie d'une demande d'accès"),
    { type: 'orderedList', attrs: { start: 1 }, content: STEPS.map(li) },
    { type: 'horizontalRule' },
  ]
}

function blockWords(blocks) {
  let n = 0
  const walk = (node) => {
    if (node.type === 'text') return
    if (node.type === 'paragraph' || node.type === 'heading' || node.type === 'codeBlock') {
      n += countWords((node.content ?? []).map((c) => c.text ?? '').join(''))
      return
    }
    for (const c of node.content ?? []) walk(c)
  }
  for (const b of blocks) walk(b)
  return n
}

/** Seuil serveur : strictement entre le chapeau et le paragraphe « en cours d'édition ». */
export const EDIT_LONG_THRESHOLD = countWords(EDITING_PARAGRAPH) - 1

export function editBody() {
  const blocks = baseBlocks()
  const base = blockWords(blocks)
  let remaining = EDIT_TARGET_WORDS - base
  if (remaining < 0) throw new Error(`corps trop long: ${base} mots`)
  // Paragraphes de remplissage toujours ≤ seuil (pas de carte « Paragraphe long » parasite).
  const maxPerParagraph = Math.min(EDIT_LONG_THRESHOLD, 30)
  while (remaining > 0) {
    const take = Math.min(remaining, maxPerParagraph)
    const words = []
    while (words.length < take) words.push(...FILLER_SENTENCE.split(' '))
    blocks.push(p(words.slice(0, take).join(' ')))
    remaining -= take
  }
  if (countWords(LEAD) > EDIT_LONG_THRESHOLD || countWords(CHANTIER) > EDIT_LONG_THRESHOLD) {
    throw new Error('le chapeau dépasse le seuil de paragraphe long')
  }
  return { type: 'doc', content: blocks }
}

export function editDocument() {
  const d = pageDocument('desktop')
  return {
    ...d,
    body: editBody(),
    status: 'brouillon',
    // « Revue prévue : Trimestrielle »
    stalenessThresholdDays: 90,
    // 14:22 à Paris (UTC+2 le 12 septembre) — « Brouillon enregistré à 14:22 »
    updatedAt: '2026-09-12T12:22:00.000Z',
    reliabilityScore: 91,
    // Étiquettes non gouvernées (`governed: false`) : pas de cadenas, pixel-diff inchangé.
    // Le rendu du cadenas est couvert par DocumentEditPage.test.tsx.
    tags: d.tags.map((t) => ({ ...t, governed: false })),
  }
}

/** Verrou exclusif détenu par l'utilisateur courant. */
export function editLockMine() {
  return {
    active: true,
    holderUserId: ME_TAREK.id,
    holderDisplayName: ME_TAREK.displayName,
    acquiredAt: '2026-09-12T12:00:00.000Z',
    heartbeatAt: '2026-09-12T12:22:00.000Z',
    heldByCurrentUser: true,
    ttlSeconds: 45,
    heartbeatSeconds: 15,
  }
}

export const EDIT_WRITING_HINTS = {
  longParagraphThresholdWords: EDIT_LONG_THRESHOLD,
  // Valeur serveur réelle : texte d'ancre source + reason=deleted (jamais accessible: true).
  brokenLinks: [
    {
      targetId: 'd0000001-0000-4000-8000-0000000000b9',
      label: 'Procédure de provisioning v9',
      accessible: false,
      reason: 'deleted',
    },
  ],
  longParagraphs: [],
}

export const EDIT_CUSTOM_FIELDS = [
  {
    id: 'f0000001-0000-4000-8000-000000000001',
    name: 'Référence réglementaire',
    slug: 'reference-reglementaire',
    fieldType: 'texte',
    required: false,
    options: null,
    value: 'ISO 27001 · A.9.2',
  },
  {
    id: 'f0000001-0000-4000-8000-000000000002',
    name: 'Système concerné',
    slug: 'systeme-concerne',
    fieldType: 'texte',
    required: false,
    options: null,
    value: 'Okta, SailPoint IdentityIQ',
  },
]

export const EDIT_APPLICABLE_WORKFLOW = {
  id: 'w0000001-0000-4000-8000-000000000001',
  name: 'Approbation simple',
  stepCount: 1,
  matchLevel: 'fallback',
  steps: [],
}
