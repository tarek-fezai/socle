// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Logique pure de l'écran Historique (History.dc.html, Diff.dc.html, RestoreVersion.dc.html,
 * MobileHistory.dc.html) : libellés, dates, lignes de la timeline, mise en page du diff.
 */
import type {
  CompareHunk,
  CompareLine,
  CompareSpan,
  DocumentDetail,
  VersionCompare,
  VersionSummary,
} from '../../lib/documents'
import { initialsOf, SYSTEM_AUTHOR_LABEL, UNKNOWN_USER_LABEL } from './documentPageUtils'

export const SYSTEM_AUTHOR_GLYPH = '⚙'

function validDate(iso: string | null | undefined): Date | null {
  if (!iso) return null
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? null : d
}

/** « 3 septembre 2026 · 09:41 » — fuseau du navigateur, format de History.dc.html. */
export function formatVersionDateTime(iso: string | null | undefined): string {
  const d = validDate(iso)
  if (!d) return ''
  const date = d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })
  const time = d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', hour12: false })
  return `${date} · ${time}`
}

/** « 12 septembre 2026 à 14:22 » — variante mobile (MobileHistory.dc.html). */
export function formatVersionDateTimeMobile(iso: string | null | undefined): string {
  const d = validDate(iso)
  if (!d) return ''
  const date = d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })
  const time = d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', hour12: false })
  return `${date} à ${time}`
}

export type VersionAuthor = {
  name: string
  initials: string
  system: boolean
}

/** Auteur d'une version ; `authorId` null → « Système (migration) » + engrenage. */
export function versionAuthor(
  v: Pick<VersionSummary, 'authorId' | 'authorDisplayName' | 'authorInitials'>,
): VersionAuthor {
  if (!v.authorId) return { name: SYSTEM_AUTHOR_LABEL, initials: SYSTEM_AUTHOR_GLYPH, system: true }
  const name = v.authorDisplayName?.trim() || UNKNOWN_USER_LABEL
  const initials = v.authorInitials?.trim() || (v.authorDisplayName?.trim() ? initialsOf(name) : '?')
  return { name, initials, system: false }
}

export type VersionRow = VersionSummary & {
  isCurrent: boolean
  /** Version affichée juste avant dans la chronologie (null pour la plus ancienne). */
  previousVersionNo: number | null
}

/**
 * Lignes de la chronologie à partir de la réponse serveur (version courante incluse).
 * Pas de reconstitution client : « N versions publiées » = `total` serveur.
 */
export function buildVersionRows(
  items: VersionSummary[],
  doc: Pick<DocumentDetail, 'currentVersionNo'> | null | undefined,
  { hasMore = false }: { hasMore?: boolean } = {},
): VersionRow[] {
  const currentNo = doc?.currentVersionNo
  const list = [...items].sort((a, b) => b.versionNo - a.versionNo)
  return list.map<VersionRow>((v, i) => ({
    ...v,
    isCurrent: Boolean(v.current) || (currentNo != null && v.versionNo === currentNo),
    // Dernière ligne chargée alors que d'autres pages existent : la précédente est v−1.
    previousVersionNo:
      list[i + 1]?.versionNo ?? (hasMore && v.versionNo > 1 ? v.versionNo - 1 : null),
  }))
}

/** « +18 » / « −4 » (vrai signe moins U+2212, comme la maquette). */
export function formatLinesAdded(n: number | null | undefined): string | null {
  return n == null ? null : `+${n}`
}

export function formatLinesRemoved(n: number | null | undefined): string | null {
  return n == null ? null : `\u2212${n}`
}

/** « 12 versions publiées depuis la création du document » */
export function versionsCountLabel(total: number): string {
  return `${total} version${total > 1 ? 's' : ''} publiée${total > 1 ? 's' : ''} depuis la création du document`
}

/** Commence en minuscule si le mot n'est pas une sigle (« Clarification… » → « clarification… »). */
export function lowerFirst(text: string): string {
  const t = text.trim()
  if (t.length < 2) return t
  const [a, b] = [t[0]!, t[1]!]
  if (a === a.toLowerCase() || b !== b.toLowerCase() || b === b.toUpperCase()) return t
  return a.toLowerCase() + t.slice(1)
}

/** Fragment « (…) » de l'avertissement de restauration. */
export function currentChangeSummaryLabel(summary: string | null | undefined): string {
  const s = summary?.trim()
  return s ? lowerFirst(s) : 'sans résumé'
}

/** Numéro de la version créée par la restauration. */
export function restoredVersionNo(currentVersionNo: number | null | undefined): number | null {
  return currentVersionNo == null ? null : currentVersionNo + 1
}

/* ------------------------------------------------------------------ */
/* Diff                                                                 */
/* ------------------------------------------------------------------ */

export type SideCell = { no: number | null; text: string; spans?: CompareSpan[] | null }

export type SideRow =
  | { kind: 'context'; old: SideCell; new: SideCell }
  | { kind: 'change'; old: SideCell | null; new: SideCell | null }

const cell = (l: CompareLine, no: number | null | undefined): SideCell => ({
  no: no ?? null,
  text: l.text,
  spans: l.spans,
})

/** Appaire les suppressions et les ajouts consécutifs (ligne par ligne, reste vide de l'autre côté). */
export function pairSideBySide(lines: CompareLine[]): SideRow[] {
  const rows: SideRow[] = []
  let i = 0
  while (i < lines.length) {
    const l = lines[i]!
    if (l.kind === 'context') {
      rows.push({ kind: 'context', old: cell(l, l.oldNo), new: cell(l, l.newNo) })
      i += 1
      continue
    }
    const dels: CompareLine[] = []
    const adds: CompareLine[] = []
    while (i < lines.length && lines[i]!.kind === 'del') dels.push(lines[i++]!)
    while (i < lines.length && lines[i]!.kind === 'add') adds.push(lines[i++]!)
    // Ajout avant suppression (ordre inhabituel) : on traite quand même le bloc.
    if (dels.length === 0 && adds.length === 0) {
      i += 1
      continue
    }
    const n = Math.max(dels.length, adds.length)
    for (let k = 0; k < n; k += 1) {
      const d = dels[k]
      const a = adds[k]
      rows.push({
        kind: 'change',
        old: d ? cell(d, d.oldNo) : null,
        new: a ? cell(a, a.newNo) : null,
      })
    }
  }
  return rows
}

export type HunkSegment =
  | { type: 'lines'; lines: CompareLine[] }
  | { type: 'collapsed'; key: string; lines: CompareLine[] }

/**
 * Découpe un bloc en segments. Si `collapsedUnchanged > 0`, chaque suite de lignes inchangées
 * devient un segment repliable (« N lignes inchangées ») ; sinon tout est affiché.
 */
export function segmentHunk(hunk: CompareHunk, hunkIndex: number): HunkSegment[] {
  const segments: HunkSegment[] = []
  const collapse = hunk.collapsedUnchanged > 0
  let buf: CompareLine[] = []
  let bufKind: 'ctx' | 'chg' | null = null
  let startIdx = 0
  const flush = () => {
    if (buf.length === 0) return
    if (bufKind === 'ctx' && collapse) {
      segments.push({ type: 'collapsed', key: `${hunkIndex}:${startIdx}`, lines: buf })
    } else {
      segments.push({ type: 'lines', lines: buf })
    }
    buf = []
  }
  hunk.lines.forEach((l, idx) => {
    const k = l.kind === 'context' ? 'ctx' : 'chg'
    if (k !== bufKind) {
      flush()
      bufKind = k
      startIdx = idx
    }
    buf.push(l)
  })
  flush()
  return segments
}

/** Bloc replié par le serveur : aucune ligne fournie, seulement le nombre de lignes inchangées. */
export function isFoldedHunk(hunk: Pick<CompareHunk, 'lines' | 'collapsedUnchanged'>): boolean {
  return hunk.lines.length === 0 && hunk.collapsedUnchanged > 0
}

/**
 * Lignes d'un bloc replié, lues dans une comparaison « contexte complet » de la même paire de versions.
 * La réponse complète est une suite de lignes dans le même ordre : le bloc `hunkIndex` occupe la plage qui
 * suit les lignes des blocs précédents. Retourne `null` si la réponse est incohérente (serveur sans
 * support du contexte complet : les blocs repliés y restent vides).
 */
export function foldedHunkLines(
  hunks: CompareHunk[],
  full: Pick<VersionCompare, 'hunks'>,
  hunkIndex: number,
): CompareLine[] | null {
  const size = (h: CompareHunk) => (isFoldedHunk(h) ? h.collapsedUnchanged : h.lines.length)
  const flat = full.hunks.flatMap((h) => h.lines)
  const expected = hunks.reduce((n, h) => n + size(h), 0)
  const target = hunks[hunkIndex]
  if (!target || !isFoldedHunk(target) || flat.length !== expected) return null
  const offset = hunks.slice(0, hunkIndex).reduce((n, h) => n + size(h), 0)
  const slice = flat.slice(offset, offset + target.collapsedUnchanged)
  return slice.length === target.collapsedUnchanged && slice.every((l) => l.kind === 'context') ? slice : null
}

/** « 2 lignes inchangées » */
export function unchangedLabel(n: number): string {
  return `${n} ligne${n > 1 ? 's' : ''} inchangée${n > 1 ? 's' : ''}`
}

/** « @@ 3. Flux de provisionnement — nouvelle section @@ » (+ « — N lignes inchangées »). */
export function hunkHeaderLabel(
  hunk: Pick<CompareHunk, 'header' | 'collapsedUnchanged'> & { lines?: CompareLine[] },
): string {
  let h = (hunk.header ?? '').trim()
  const wrapped = /^@@\s*([\s\S]*?)\s*@@$/.exec(h)
  if (wrapped) h = wrapped[1]!.trim()
  if (!h) {
    // Pas de titre au-dessus du bloc (début du document) : on repère le bloc par sa première ligne.
    const first = hunk.lines?.[0]
    const no = first ? (first.newNo ?? first.oldNo) : null
    h = no != null ? `ligne ${no}` : 'début du document'
  }
  if (hunk.collapsedUnchanged > 0) h = `${h} — ${unchangedLabel(hunk.collapsedUnchanged)}`
  return `@@ ${h} @@`
}

/** Fragments à afficher pour une cellule : les segments `add` / `del` sont mis en évidence. */
export function cellSpans(
  cellData: Pick<SideCell, 'text' | 'spans'>,
  side: 'old' | 'new',
): Array<{ text: string; strong: boolean }> {
  const spans = cellData.spans
  if (!spans || spans.length === 0) return [{ text: cellData.text, strong: false }]
  const wanted = side === 'old' ? 'del' : 'add'
  const other = side === 'old' ? 'add' : 'del'
  return spans
    .filter((s) => s.kind !== other)
    .map((s) => ({ text: s.text, strong: s.kind === wanted }))
}
