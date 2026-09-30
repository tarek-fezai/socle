export type DiffChange = {
  path: string
  op: string
  before: unknown
  after: unknown
}

function stringify(value: unknown): string {
  if (value == null) return '∅'
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

type DiffViewerProps = {
  changes: DiffChange[] | null
  loading?: boolean
  error?: boolean
  emptyHint?: string
  title?: string
}

/** Rendu structurel des changements JSON — couleurs alignées Diff.dc.html / DiffApproval.dc.html. */
export function DiffViewer({
  changes,
  loading = false,
  error = false,
  emptyHint = 'Aucun changement structurel.',
  title,
}: DiffViewerProps) {
  return (
    <div>
      {title && <h3 className="section-label">{title}</h3>}
      {loading && <p className="mt-2 text-sm text-socle-muted">Chargement du diff…</p>}
      {error && (
        <p className="mt-2 text-sm text-socle-danger">Impossible de charger le diff.</p>
      )}
      {!loading && !error && changes && changes.length === 0 && (
        <p className="mt-2 text-sm text-socle-muted">{emptyHint}</p>
      )}
      {!loading && !error && changes && changes.length > 0 && (
        <ul className="mt-3 overflow-hidden rounded-lg border border-socle-line font-mono text-xs">
          {changes.map((c) => (
            <li
              key={`${c.op}-${c.path}`}
              className={
                c.op === 'added'
                  ? 'border-b border-[#D3EBD9] bg-[#DFF3E6] px-3 py-2 last:border-b-0'
                  : c.op === 'removed'
                    ? 'border-b border-[#F2CFC2] bg-[#FCEEEA] px-3 py-2 last:border-b-0'
                    : 'border-b border-socle-line bg-socle-mist/60 px-3 py-2 last:border-b-0'
              }
            >
              <span
                className={
                  c.op === 'added'
                    ? 'font-semibold text-socle-success'
                    : c.op === 'removed'
                      ? 'font-semibold text-socle-danger'
                      : 'font-semibold text-socle-accent'
                }
              >
                [{c.op}]
              </span>{' '}
              <span className="text-socle-slate">{c.path || '$'}</span>
              {c.op === 'modified' && (
                <div className="mt-1 pl-2 text-socle-slate">
                  <span className="text-socle-danger">− {stringify(c.before)}</span>
                  <br />
                  <span className="text-socle-success">+ {stringify(c.after)}</span>
                </div>
              )}
              {c.op === 'added' && (
                <div className="mt-1 pl-2 text-socle-success">+ {stringify(c.after)}</div>
              )}
              {c.op === 'removed' && (
                <div className="mt-1 pl-2 text-socle-danger">− {stringify(c.before)}</div>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
