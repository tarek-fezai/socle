// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest'
import type { CompareHunk, CompareLine, VersionSummary } from '../../lib/documents'
import {
  buildVersionRows,
  cellSpans,
  currentChangeSummaryLabel,
  formatLinesAdded,
  formatLinesRemoved,
  formatVersionDateTime,
  formatVersionDateTimeMobile,
  foldedHunkLines,
  hunkHeaderLabel,
  isFoldedHunk,
  lowerFirst,
  pairSideBySide,
  restoredVersionNo,
  segmentHunk,
  unchangedLabel,
  versionAuthor,
  versionsCountLabel,
} from './versionHistoryUtils'

/** ISO d'un instant exprimé en heure LOCALE (le formatage suit le fuseau du navigateur). */
const local = (y: number, m: number, d: number, h: number, min: number) =>
  new Date(y, m - 1, d, h, min).toISOString()

const v = (n: number, over: Partial<VersionSummary> = {}): VersionSummary => ({
  versionNo: n,
  authorId: 'u1',
  changeSummary: null,
  createdAt: local(2026, 9, n, 10, 0),
  ...over,
})

const line = (kind: CompareLine['kind'], oldNo: number | null, newNo: number | null, text = 't'): CompareLine => ({
  kind,
  oldNo,
  newNo,
  text,
})

describe('formatVersionDateTime', () => {
  it('« 3 septembre 2026 · 09:41 » dans le fuseau du navigateur', () => {
    expect(formatVersionDateTime(local(2026, 9, 3, 9, 41))).toBe('3 septembre 2026 · 09:41')
  })

  it('heure sur 24 h avec zéro initial', () => {
    expect(formatVersionDateTime(local(2026, 9, 12, 14, 22))).toBe('12 septembre 2026 · 14:22')
    expect(formatVersionDateTime(local(2026, 8, 28, 16, 5))).toBe('28 août 2026 · 16:05')
  })

  it('variante mobile « … à 14:22 »', () => {
    expect(formatVersionDateTimeMobile(local(2026, 9, 12, 14, 22))).toBe('12 septembre 2026 à 14:22')
  })

  it('date invalide ou absente → chaîne vide', () => {
    expect(formatVersionDateTime('pas une date')).toBe('')
    expect(formatVersionDateTime(null)).toBe('')
  })
})

describe('versionAuthor', () => {
  it('authorId null → « Système (migration) » + engrenage', () => {
    expect(versionAuthor({ authorId: null })).toEqual({ name: 'Système (migration)', initials: '⚙', system: true })
  })

  it('même si le serveur envoie un nom, un auteur null reste « Système (migration) »', () => {
    expect(versionAuthor({ authorId: null, authorDisplayName: 'Quelqu’un', authorInitials: 'QQ' }).name).toBe(
      'Système (migration)',
    )
  })

  it('auteur résolu : nom + initiales serveur', () => {
    expect(versionAuthor({ authorId: 'u1', authorDisplayName: 'Claire Dubois', authorInitials: 'CD' })).toEqual({
      name: 'Claire Dubois',
      initials: 'CD',
      system: false,
    })
  })

  it('initiales calculées si absentes ; auteur non résolu → libellé neutre', () => {
    expect(versionAuthor({ authorId: 'u1', authorDisplayName: 'Tarek Fezai' }).initials).toBe('TF')
    expect(versionAuthor({ authorId: 'u1' })).toEqual({ name: 'Utilisateur', initials: '?', system: false })
  })
})

describe('buildVersionRows', () => {
  const doc = { currentVersionNo: 3, updatedAt: local(2026, 9, 12, 14, 22) }

  it('trie du plus récent au plus ancien et marque la version courante', () => {
    const { rows, synthesizedCurrent } = buildVersionRows([v(1), v(3), v(2)], doc)
    expect(rows.map((r) => r.versionNo)).toEqual([3, 2, 1])
    expect(rows.map((r) => r.isCurrent)).toEqual([true, false, false])
    expect(rows.map((r) => r.previousVersionNo)).toEqual([2, 1, null])
    expect(synthesizedCurrent).toBe(false)
  })

  it('reconstitue la version courante si le serveur l’omet', () => {
    const { rows, synthesizedCurrent } = buildVersionRows([v(2), v(1)], {
      ...doc,
      updatedBy: { id: 'u9', displayName: 'Tarek Fezai', initials: 'TF' },
    })
    expect(synthesizedCurrent).toBe(true)
    expect(rows[0]).toMatchObject({
      versionNo: 3,
      isCurrent: true,
      authorId: 'u9',
      authorDisplayName: 'Tarek Fezai',
      previousVersionNo: 2,
    })
  })

  it('ne reconstitue rien si includeCurrent est faux', () => {
    expect(buildVersionRows([v(2)], doc, { includeCurrent: false }).rows.map((r) => r.versionNo)).toEqual([2])
  })

  it('d’autres pages existent : la dernière ligne chargée garde v-1 comme précédente', () => {
    const { rows } = buildVersionRows([v(3), v(2)], doc, { hasMore: true })
    expect(rows.at(-1)?.previousVersionNo).toBe(1)
  })
})

describe('libellés', () => {
  it('+N / −N avec le vrai signe moins', () => {
    expect(formatLinesAdded(18)).toBe('+18')
    expect(formatLinesRemoved(0)).toBe('\u22120')
    expect(formatLinesAdded(null)).toBeNull()
    expect(formatLinesRemoved(undefined)).toBeNull()
  })

  it('compteur « N versions publiées depuis la création du document »', () => {
    expect(versionsCountLabel(12)).toBe('12 versions publiées depuis la création du document')
    expect(versionsCountLabel(1)).toBe('1 version publiée depuis la création du document')
  })

  it('résumé de la version courante dans la modale', () => {
    expect(currentChangeSummaryLabel("Clarification du circuit d'approbation N2")).toBe(
      "clarification du circuit d'approbation N2",
    )
    expect(currentChangeSummaryLabel('IAM-482 durci')).toBe('IAM-482 durci')
    expect(currentChangeSummaryLabel('  ')).toBe('sans résumé')
    expect(currentChangeSummaryLabel(null)).toBe('sans résumé')
  })

  it('lowerFirst ne touche pas aux sigles ni aux chiffres', () => {
    expect(lowerFirst('Ajout')).toBe('ajout')
    expect(lowerFirst('RGPD')).toBe('RGPD')
    expect(lowerFirst('2026 révisé')).toBe('2026 révisé')
  })

  it('numéro de la version créée par la restauration', () => {
    expect(restoredVersionNo(12)).toBe(13)
    expect(restoredVersionNo(undefined)).toBeNull()
  })
})

describe('pairSideBySide', () => {
  it('appaire suppressions et ajouts, le reste est vide de l’autre côté', () => {
    const rows = pairSideBySide([
      line('context', 1, 1),
      line('del', 2, null, 'a'),
      line('del', 3, null, 'b'),
      line('add', null, 2, 'A'),
      line('add', null, 3, 'B'),
      line('add', null, 4, 'C'),
    ])
    expect(rows).toHaveLength(4)
    expect(rows[0]).toMatchObject({ kind: 'context' })
    expect(rows[1]).toMatchObject({ kind: 'change', old: { no: 2, text: 'a' }, new: { no: 2, text: 'A' } })
    expect(rows[3]).toMatchObject({ kind: 'change', old: null, new: { no: 4, text: 'C' } })
  })

  it('ajout seul : côté ancien vide', () => {
    const rows = pairSideBySide([line('add', null, 1), line('add', null, 2)])
    expect(rows.every((r) => r.kind === 'change' && r.old === null)).toBe(true)
  })

  it('suppression seule : côté nouveau vide', () => {
    const rows = pairSideBySide([line('del', 5, null)])
    expect(rows[0]).toMatchObject({ kind: 'change', new: null, old: { no: 5 } })
  })
})

describe('segmentHunk (repli des lignes inchangées)', () => {
  const lines = [
    line('context', 1, 1),
    line('context', 2, 2),
    line('del', 3, null),
    line('add', null, 3),
    line('context', 4, 4),
  ]

  it('collapsedUnchanged = 0 → tout est affiché', () => {
    const segs = segmentHunk({ header: 'h', lines, collapsedUnchanged: 0 }, 0)
    expect(segs.every((s) => s.type === 'lines')).toBe(true)
  })

  it('collapsedUnchanged > 0 → chaque suite de lignes inchangées est repliable', () => {
    const segs = segmentHunk({ header: 'h', lines, collapsedUnchanged: 3 }, 2)
    expect(segs.map((s) => s.type)).toEqual(['collapsed', 'lines', 'collapsed'])
    const first = segs[0]!
    expect(first.type === 'collapsed' && first.lines).toHaveLength(2)
    expect(first.type === 'collapsed' && first.key).toBe('2:0')
  })

  it('bloc fait uniquement de lignes inchangées → un seul segment repliable', () => {
    const h: CompareHunk = { header: 'x', lines: [line('context', 6, 7), line('context', 7, 8)], collapsedUnchanged: 2 }
    expect(segmentHunk(h, 1)).toHaveLength(1)
    expect(unchangedLabel(2)).toBe('2 lignes inchangées')
    expect(unchangedLabel(1)).toBe('1 ligne inchangée')
  })
})

describe('hunkHeaderLabel', () => {
  it('entoure d’@@ et ajoute le nombre de lignes inchangées', () => {
    expect(hunkHeaderLabel({ header: '1. Principe du moindre privilège', collapsedUnchanged: 0 })).toBe(
      '@@ 1. Principe du moindre privilège @@',
    )
    expect(hunkHeaderLabel({ header: '2. Rôles et périmètres', collapsedUnchanged: 2 })).toBe(
      '@@ 2. Rôles et périmètres — 2 lignes inchangées @@',
    )
  })

  it('ne double pas les @@ déjà fournis par le serveur', () => {
    expect(hunkHeaderLabel({ header: '@@ -1,3 +1,4 @@', collapsedUnchanged: 0 })).toBe('@@ -1,3 +1,4 @@')
  })
})

describe('cellSpans (fragments de mots)', () => {
  const spans = [
    { kind: 'eq' as const, text: 'par le responsable ' },
    { kind: 'del' as const, text: 'ou un membre' },
    { kind: 'add' as const, text: 'et le propriétaire' },
  ]

  it('côté ancien : inchangé + suppressions en évidence', () => {
    expect(cellSpans({ text: '', spans }, 'old')).toEqual([
      { text: 'par le responsable ', strong: false },
      { text: 'ou un membre', strong: true },
    ])
  })

  it('côté nouveau : inchangé + ajouts en évidence', () => {
    expect(cellSpans({ text: '', spans }, 'new')).toEqual([
      { text: 'par le responsable ', strong: false },
      { text: 'et le propriétaire', strong: true },
    ])
  })

  it('sans spans → texte brut', () => {
    expect(cellSpans({ text: 'abc' }, 'new')).toEqual([{ text: 'abc', strong: false }])
  })
})

describe('hunkHeaderLabel sans titre', () => {
  it('repère le bloc par sa première ligne, ou le début du document', () => {
    const line = (newNo: number | null, oldNo: number | null): CompareLine => ({
      kind: 'add',
      oldNo,
      newNo,
      text: 'x',
    })
    expect(hunkHeaderLabel({ header: '', collapsedUnchanged: 0, lines: [line(7, null)] })).toBe('@@ ligne 7 @@')
    expect(hunkHeaderLabel({ header: ' ', collapsedUnchanged: 0, lines: [line(null, 4)] })).toBe('@@ ligne 4 @@')
    expect(hunkHeaderLabel({ header: '', collapsedUnchanged: 3 })).toBe('@@ début du document — 3 lignes inchangées @@')
  })
})

describe('blocs repliés par le serveur', () => {
  const ctx = (n: number): CompareLine => ({ kind: 'context', oldNo: n, newNo: n, text: `c${n}` })
  const add = (n: number): CompareLine => ({ kind: 'add', oldNo: null, newNo: n, text: `a${n}` })
  const hunks: CompareHunk[] = [
    { header: '', lines: [], collapsedUnchanged: 2 },
    { header: 'T', lines: [add(3)], collapsedUnchanged: 0 },
    { header: 'T', lines: [], collapsedUnchanged: 1 },
  ]
  const full = (lines: CompareLine[]) => ({ hunks: [{ header: '', lines, collapsedUnchanged: 0 }] })

  it('isFoldedHunk : sans lignes et avec un compteur', () => {
    expect(isFoldedHunk(hunks[0]!)).toBe(true)
    expect(isFoldedHunk(hunks[1]!)).toBe(false)
    // contrat « lignes + compteur » : bloc modifié dont le contexte se replie, pas un bloc replié
    expect(isFoldedHunk({ lines: [ctx(1)], collapsedUnchanged: 1 })).toBe(false)
    expect(isFoldedHunk({ lines: [], collapsedUnchanged: 0 })).toBe(false)
  })

  it('foldedHunkLines : découpe la réponse complète selon la position du bloc', () => {
    const all = full([ctx(1), ctx(2), add(3), ctx(3)])
    expect(foldedHunkLines(hunks, all, 0)?.map((l) => l.text)).toEqual(['c1', 'c2'])
    expect(foldedHunkLines(hunks, all, 2)?.map((l) => l.text)).toEqual(['c3'])
  })

  it('foldedHunkLines : null si la réponse est incohérente (serveur sans contexte complet)', () => {
    expect(foldedHunkLines(hunks, { hunks }, 0)).toBeNull() // blocs encore vides
    expect(foldedHunkLines(hunks, full([ctx(1), ctx(2), add(3)]), 0)).toBeNull() // mauvais total
    expect(foldedHunkLines(hunks, full([add(1), ctx(2), add(3), ctx(3)]), 0)).toBeNull() // ligne non « context »
    expect(foldedHunkLines(hunks, full([ctx(1), ctx(2), add(3), ctx(3)]), 1)).toBeNull() // bloc non replié
  })
})