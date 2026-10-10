// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Règles : docs/visual-parity.md
 *
 * Annotation `data-mock-id` / `data-visual-mask` (aucune modification de DOM ni de style) et sections
 * pixel communes aux comparaisons Diff.dc.html (Historique → Comparer) et DiffApproval.dc.html.
 *
 * Les maquettes Diff / DiffApproval / Approval n'ont pas de sidebar ; l'app garde le shell (268 px) :
 * la colonne principale fait 1172 px au lieu de 1440 px (docs/visual-parity.md § Décisions produit).
 * Conséquence : sections pleine largeur → SIZE_EXCEPTION Δw = −268 (crop haut-gauche) ; les côtés
 * « ancien / nouveau » d'une ligne de diff (50 % de la boîte) → Δw = −134 ; les éléments dont la taille ne
 * dépend pas de la largeur de page (boutons, rail 349 px…) sont comparés sans exception.
 */

export const SHELL_DW = -268
/** Boîte de diff : 1352 px (maquette) vs 1084 px (app) → chaque côté 675 px vs 541 px. */
export const SIDE_DW = -134

const DECISION_REASON =
  'docs/visual-parity.md § Décisions produit — Comparaison / Approbation : shell avec sidebar conservé ; colonne principale 1172 px vs 1440 px maquette'

/**
 * Annote le corps du diff : blocs `@@` (`dpx-hunk-N`) et côtés ancien / nouveau de chaque ligne
 * (`dpx-row-N-old|new`). `kind` : `mock` (Diff*.dc.html) ou `app`.
 * @returns {Promise<{ hunks: number, rows: number }>}
 */
export async function annotateDiffBody(page, kind) {
  return page.evaluate((k) => {
    const box =
      k === 'mock'
        ? document.querySelector('.hunk')?.parentElement
        : document.querySelector('[data-testid="diff-view"]')
    if (!box) throw new Error(`annotateDiffBody(${k}): boîte de diff introuvable`)
    const hunkSel = k === 'mock' ? '.hunk' : '.diff-hunk'
    const rowSel = k === 'mock' ? '.diffline' : '.diff-row'
    let h = 0
    let r = 0
    for (const el of box.children) {
      if (el.matches(hunkSel)) {
        el.setAttribute('data-mock-id', `dpx-hunk-${h++}`)
      } else if (el.matches(rowSel)) {
        ;[...el.children].forEach((side, i) =>
          side.setAttribute('data-mock-id', `dpx-row-${r}-${i === 0 ? 'old' : 'new'}`),
        )
        r++
      }
    }
    return { hunks: h, rows: r }
  }, kind)
}

/** Sections pixel du corps du diff (blocs + côtés de ligne). */
export function diffBodySections({ hunks, rows }) {
  return [
    ...Array.from({ length: hunks }, (_, i) => ({ id: `dpx-hunk-${i}`, name: `hunk-${i}`, masks: [] })),
    ...Array.from({ length: rows }, (_, i) =>
      ['old', 'new'].map((s) => ({ id: `dpx-row-${i}-${s}`, name: `row-${i}-${s}`, masks: [] })),
    ).flat(),
  ]
}

/** SIZE_EXCEPTIONS du corps du diff pour un préfixe de spec (`diff`, `diff-approval`). */
export function diffBodySizeExceptions(prefix, { hunks, rows }) {
  const out = {}
  for (let i = 0; i < hunks; i++) {
    out[`${prefix}-hunk-${i}`] = { dw: SHELL_DW, dh: 0, reason: DECISION_REASON }
  }
  for (let i = 0; i < rows; i++) {
    for (const s of ['old', 'new']) {
      out[`${prefix}-row-${i}-${s}`] = {
        dw: SIDE_DW,
        dh: 0,
        reason: `${DECISION_REASON} ; chaque côté = 50 % de la boîte de diff (≈541 px vs 675 px)`,
      }
    }
  }
  return out
}

export const SHELL_DECISION_REASON = DECISION_REASON
