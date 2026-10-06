// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useMemo, useState } from 'react'
import { Node, NodeViewWrapper, ReactNodeViewRenderer, mergeAttributes, type NodeViewProps } from '@tiptap/react'
import { ChartView } from './RichBlockViews'
import {
  CHART_NODE_TYPE,
  type ChartType,
  parseChartSeries,
  parseStringList,
  SAMPLE_CHART_ATTRS,
} from './richBlockUtils'

function jsonAttr(name: string, data: string, fallback: unknown) {
  return {
    default: fallback,
    parseHTML: (el: HTMLElement) => {
      const raw = el.getAttribute(data)
      if (!raw) return fallback
      try {
        return JSON.parse(raw) as unknown
      } catch {
        return fallback
      }
    },
    renderHTML: (attrs: Record<string, unknown>) => {
      const v = attrs[name]
      return v === undefined || v === null ? {} : { [data]: JSON.stringify(v) }
    },
  }
}

function ChartEditView({ node, selected, editor: ed, updateAttributes }: NodeViewProps) {
  const a = node.attrs as {
    chartType?: unknown
    labels?: unknown
    series?: unknown
    title?: unknown
  }
  const chartType = (a.chartType === 'line' || a.chartType === 'pie' ? a.chartType : 'bar') as ChartType
  const labels = parseStringList(a.labels)
  const series = parseChartSeries(a.series)
  const title = typeof a.title === 'string' ? a.title : ''
  const editing = selected && ed.isEditable

  const table = useMemo(() => {
    const lbls = labels.length ? labels : ['A']
    const ser = series.length ? series : [{ name: 'Série', values: lbls.map(() => 0) }]
    return { labels: lbls, series: ser }
  }, [labels, series])

  const [draftTitle, setDraftTitle] = useState<string | null>(null)

  const syncFromTable = (nextLabels: string[], nextSeries: typeof table.series) => {
    updateAttributes({
      labels: nextLabels,
      series: nextSeries,
    })
  }

  return (
    <NodeViewWrapper className={`edit-attachment-node edit-chart-node${selected ? ' is-selected' : ''}`} data-drag-handle>
      <ChartView chartType={chartType} labels={table.labels} series={table.series} title={title || undefined} />
      {editing ? (
        <div className="edit-chart-form" data-testid="edit-chart-form">
          <label>
            <span>Titre</span>
            <input
              type="text"
              value={draftTitle ?? title}
              onChange={(e) => setDraftTitle(e.target.value)}
              onBlur={() => {
                updateAttributes({ title: (draftTitle ?? title).trim() || null })
                setDraftTitle(null)
              }}
            />
          </label>
          <label>
            <span>Type</span>
            <select
              value={chartType}
              onChange={(e) => updateAttributes({ chartType: e.target.value as ChartType })}
            >
              <option value="bar">Barres</option>
              <option value="line">Courbe</option>
              <option value="pie">Camembert</option>
            </select>
          </label>
          <div className="edit-chart-table-wrap">
            <table className="edit-chart-table">
              <thead>
                <tr>
                  <th />
                  {table.labels.map((l, ci) => (
                    <th key={ci}>
                      <input
                        type="text"
                        aria-label={`Libellé ${ci + 1}`}
                        value={l}
                        onChange={(e) => {
                          const next = [...table.labels]
                          next[ci] = e.target.value
                          syncFromTable(next, table.series)
                        }}
                      />
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {table.series.map((s, si) => (
                  <tr key={si}>
                    <th>
                      <input
                        type="text"
                        aria-label={`Nom série ${si + 1}`}
                        value={s.name}
                        onChange={(e) => {
                          const next = table.series.map((row, i) =>
                            i === si ? { ...row, name: e.target.value } : row,
                          )
                          syncFromTable(table.labels, next)
                        }}
                      />
                    </th>
                    {table.labels.map((_, vi) => (
                      <td key={vi}>
                        <input
                          type="number"
                          aria-label={`Valeur ${s.name} ${table.labels[vi]}`}
                          value={s.values[vi] ?? 0}
                          onChange={(e) => {
                            const val = Number(e.target.value) || 0
                            const next = table.series.map((row, i) => {
                              if (i !== si) return row
                              const values = [...row.values]
                              values[vi] = val
                              return { ...row, values }
                            })
                            syncFromTable(table.labels, next)
                          }}
                        />
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : null}
    </NodeViewWrapper>
  )
}

/**
 * Graphique en bloc (Recharts). JSON : attrs chartType, labels, series, title?.
 */
export const ChartNode = Node.create({
  name: CHART_NODE_TYPE,
  group: 'block',
  inline: false,
  atom: true,
  selectable: true,
  draggable: true,

  addAttributes() {
    return {
      chartType: {
        default: SAMPLE_CHART_ATTRS.chartType,
        parseHTML: (el: HTMLElement) => el.getAttribute('data-chart-type') ?? 'bar',
        renderHTML: (attrs: Record<string, unknown>) => ({ 'data-chart-type': String(attrs.chartType ?? 'bar') }),
      },
      labels: jsonAttr('labels', 'data-labels', SAMPLE_CHART_ATTRS.labels),
      series: jsonAttr('series', 'data-series', SAMPLE_CHART_ATTRS.series),
      title: {
        default: null as string | null,
        parseHTML: (el: HTMLElement) => el.getAttribute('data-title'),
        renderHTML: (attrs: Record<string, unknown>) =>
          typeof attrs.title === 'string' && attrs.title ? { 'data-title': attrs.title } : {},
      },
    }
  },

  parseHTML() {
    return [{ tag: 'div[data-chart]' }]
  },

  renderHTML({ node, HTMLAttributes }) {
    return [
      'div',
      mergeAttributes(HTMLAttributes, { 'data-chart': 'true' }),
      String(node.attrs.title ?? 'Graphique'),
    ]
  },

  addNodeView() {
    return ReactNodeViewRenderer(ChartEditView)
  },
})
