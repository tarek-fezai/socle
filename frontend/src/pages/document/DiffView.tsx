// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Fragment, useState } from 'react'
import type { CompareHunk, CompareLine, VersionCompare } from '../../lib/documents'
import {
  cellSpans,
  foldedHunkLines,
  hunkHeaderLabel,
  isFoldedHunk,
  pairSideBySide,
  segmentHunk,
  unchangedLabel,
  type SideCell,
  type SideRow,
} from './versionHistoryUtils'

export type DiffMode = 'side' | 'unified'

/** Comparaison « contexte complet » (lignes inchangées des blocs repliés par le serveur). */
export type FullContext = { status: 'idle' | 'loading' | 'error' | 'ready'; data?: VersionCompare }

function Spans({ cell, side }: { cell: Pick<SideCell, 'text' | 'spans'>; side: 'old' | 'new' }) {
  return (
    <>
      {cellSpans(cell, side).map((s, i) =>
        s.strong ? <strong key={i}>{s.text}</strong> : <Fragment key={i}>{s.text}</Fragment>,
      )}
    </>
  )
}

function Side({
  side,
  kind,
  cell,
}: {
  side: 'old' | 'new'
  kind: 'ctx' | 'del' | 'add' | 'empty'
  cell: SideCell | null
}) {
  return (
    <div className={`diff-side diff-side--${side} is-${kind}`}>
      <div className="diff-ln">{cell?.no ?? ''}</div>
      <div className="diff-cell">{cell ? <Spans cell={cell} side={side} /> : null}</div>
    </div>
  )
}

function SideBySideRow({ row }: { row: SideRow }) {
  if (row.kind === 'context') {
    return (
      <div className="diff-row" data-kind="context">
        <Side side="old" kind="ctx" cell={row.old} />
        <Side side="new" kind="ctx" cell={row.new} />
      </div>
    )
  }
  return (
    <div className="diff-row" data-kind="change">
      <Side side="old" kind={row.old ? 'del' : 'empty'} cell={row.old} />
      <Side side="new" kind={row.new ? 'add' : 'empty'} cell={row.new} />
    </div>
  )
}

function UnifiedRow({ line }: { line: CompareLine }) {
  const kind = line.kind
  const sign = kind === 'add' ? '+' : kind === 'del' ? '\u2212' : ''
  const side = kind === 'add' ? 'new' : 'old'
  return (
    <div className={`diff-row diff-row--unified is-${kind === 'context' ? 'ctx' : kind}`} data-kind={kind}>
      <div className="diff-ln">{line.oldNo ?? ''}</div>
      <div className="diff-ln">{line.newNo ?? ''}</div>
      <div className="diff-sign" aria-hidden>
        {sign}
      </div>
      <div className="diff-cell">
        {kind === 'context' ? line.text : <Spans cell={line} side={side} />}
      </div>
    </div>
  )
}

/** Lignes d'un segment, côte à côte ou unifié. */
function SegmentLines({ lines, mode }: { lines: CompareLine[]; mode: DiffMode }) {
  return mode === 'side' ? (
    <>
      {pairSideBySide(lines).map((row, ri) => (
        <SideBySideRow key={ri} row={row} />
      ))}
    </>
  ) : (
    <>
      {lines.map((line, li) => (
        <UnifiedRow key={li} line={line} />
      ))}
    </>
  )
}

function CollapsedButton({ count, onClick }: { count: number; onClick: () => void }) {
  return (
    <button
      type="button"
      className="diff-collapsed"
      aria-expanded={false}
      onClick={onClick}
      data-testid="diff-collapsed"
    >
      <span aria-hidden>▸</span>
      {unchangedLabel(count)}
    </button>
  )
}

/** Bloc replié par le serveur (aucune ligne fournie) : les lignes viennent d'une comparaison « contexte complet ». */
function FoldedHunk({
  hunks,
  index,
  mode,
  expanded,
  onExpand,
  full,
}: {
  hunks: CompareHunk[]
  index: number
  mode: DiffMode
  expanded: Set<string>
  onExpand: (key: string) => void
  full?: FullContext
}) {
  const hunk = hunks[index]!
  const key = `f:${index}`
  if (!expanded.has(key)) return <CollapsedButton count={hunk.collapsedUnchanged} onClick={() => onExpand(key)} />
  if (full?.status === 'ready' && full.data) {
    const lines = foldedHunkLines(hunks, full.data, index)
    return lines ? (
      <SegmentLines lines={lines} mode={mode} />
    ) : (
      <p className="diff-folded-note" role="status" data-testid="diff-folded-unavailable">
        Les {unchangedLabel(hunk.collapsedUnchanged)} ne peuvent pas être affichées pour cette comparaison.
      </p>
    )
  }
  if (full?.status === 'error') {
    return (
      <p className="diff-folded-note" role="alert" data-testid="diff-folded-error">
        Impossible de charger les {unchangedLabel(hunk.collapsedUnchanged)}.
      </p>
    )
  }
  return (
    <p className="diff-folded-note" role="status" data-testid="diff-folded-loading">
      Chargement des {unchangedLabel(hunk.collapsedUnchanged)}…
    </p>
  )
}

function Hunk({
  hunks,
  index,
  mode,
  expanded,
  onExpand,
  full,
}: {
  hunks: CompareHunk[]
  index: number
  mode: DiffMode
  expanded: Set<string>
  onExpand: (key: string) => void
  full?: FullContext
}) {
  const hunk = hunks[index]!
  if (isFoldedHunk(hunk)) {
    return <FoldedHunk hunks={hunks} index={index} mode={mode} expanded={expanded} onExpand={onExpand} full={full} />
  }
  return (
    <>
      <div className="diff-hunk" data-testid="diff-hunk">
        <div className="diff-hunk-cell" data-mock-id={`diff-hunk-${index}`}>
          {hunkHeaderLabel(hunk)}
        </div>
      </div>
      {segmentHunk(hunk, index).map((seg, si) => {
        if (seg.type === 'collapsed' && !expanded.has(seg.key)) {
          return <CollapsedButton key={`c${si}`} count={seg.lines.length} onClick={() => onExpand(seg.key)} />
        }
        return <SegmentLines key={`l${si}`} lines={seg.lines} mode={mode} />
      })}
    </>
  )
}

/**
 * Rendu d'une comparaison (blocs, lignes, fragments de mots) en côte à côte ou unifié.
 * `full` / `onRequestFull` : lignes des blocs repliés par le serveur, chargées au premier dépliage.
 */
export function DiffView({
  compare,
  mode,
  full,
  onRequestFull,
}: {
  compare: VersionCompare
  mode: DiffMode
  full?: FullContext
  onRequestFull?: () => void
}) {
  // Blocs dépliés : état local (pas de persistance).
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set())
  if (compare.hunks.length === 0) {
    return (
      <p className="diff-state" data-testid="diff-empty">
        Aucune différence entre ces deux versions.
      </p>
    )
  }
  return (
    <div className="diff-box" data-testid="diff-view" data-mode={mode} data-mock-id="diff-box">
      {compare.hunks.map((_, i) => (
        <Hunk
          key={i}
          hunks={compare.hunks}
          index={i}
          mode={mode}
          expanded={expanded}
          full={full}
          onExpand={(key) => {
            setExpanded((prev) => new Set(prev).add(key))
            if (key.startsWith('f:')) onRequestFull?.()
          }}
        />
      ))}
    </div>
  )
}
