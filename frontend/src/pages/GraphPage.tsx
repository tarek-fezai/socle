// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import cytoscape, { type Core } from 'cytoscape'
import { api } from '../lib/api'
import { fetchSpaceGraph } from '../lib/graph'
import { getSpace } from '../lib/spaces'

const INTRA_COLOR = '#3730E0'
const INTER_COLOR = '#B7791F'

export function GraphPage() {
  const { spaceId = '' } = useParams()
  const navigate = useNavigate()
  const containerRef = useRef<HTMLDivElement>(null)
  const cyRef = useRef<Core | null>(null)

  const space = useQuery({
    queryKey: ['space', spaceId],
    queryFn: () => getSpace(api, spaceId),
    enabled: Boolean(spaceId),
  })

  const graph = useQuery({
    queryKey: ['space-graph', spaceId],
    queryFn: () => fetchSpaceGraph(api, spaceId),
    enabled: Boolean(spaceId),
    staleTime: 0,
    refetchOnMount: 'always',
  })

  useEffect(() => {
    if (!containerRef.current || !graph.data) return

    cyRef.current?.destroy()

    const elements: cytoscape.ElementDefinition[] = [
      ...graph.data.nodes.map((n) => ({
        data: {
          id: n.id,
          label: n.title,
          spaceId: n.spaceId,
        },
        classes: n.spaceId !== spaceId ? 'foreign' : undefined,
      })),
      ...graph.data.edges.map((e) => ({
        data: {
          id: `${e.sourceId}->${e.targetId}`,
          source: e.sourceId,
          target: e.targetId,
          kind: e.kind,
        },
      })),
    ]

    const cy = cytoscape({
      container: containerRef.current,
      elements,
      style: [
        {
          selector: 'node',
          style: {
            label: 'data(label)',
            'text-valign': 'center',
            'text-halign': 'center',
            'font-size': 12,
            'font-family': 'IBM Plex Sans, sans-serif',
            color: '#0E0E10',
            'background-color': '#FFFFFF',
            'border-width': 1.5,
            'border-color': '#E4E3EA',
            width: 128,
            height: 42,
            shape: 'roundrectangle',
            'text-wrap': 'ellipsis',
            'text-max-width': '110',
          },
        },
        {
          selector: 'node.foreign',
          style: {
            'border-color': INTER_COLOR,
            'border-width': 2,
          },
        },
        {
          selector: 'edge[kind = "intra"]',
          style: {
            width: 2,
            'line-color': INTRA_COLOR,
            'target-arrow-color': INTRA_COLOR,
            'target-arrow-shape': 'triangle',
            'curve-style': 'bezier',
            opacity: 0.85,
          },
        },
        {
          selector: 'edge[kind = "inter"]',
          style: {
            width: 2.5,
            'line-color': INTER_COLOR,
            'line-style': 'dashed',
            'target-arrow-color': INTER_COLOR,
            'target-arrow-shape': 'triangle',
            'curve-style': 'bezier',
            opacity: 0.95,
          },
        },
      ],
      layout: {
        name: 'breadthfirst',
        directed: true,
        padding: 40,
        spacingFactor: 1.35,
      },
      userZoomingEnabled: true,
      userPanningEnabled: true,
    })

    cy.on('tap', 'node', (evt) => {
      const id = evt.target.id()
      void navigate(`/docs/${id}/view`)
    })

    cyRef.current = cy
    return () => {
      cy.destroy()
      cyRef.current = null
    }
  }, [graph.data, spaceId, navigate])

  if (space.isLoading || graph.isLoading) {
    return <main className="page-shell text-socle-muted">Chargement…</main>
  }

  if (graph.isError || !graph.data) {
    return (
      <main className="page-shell">
        <Link to="/spaces" className="text-sm font-semibold text-socle-accent">
          ← Espaces
        </Link>
        <p className="mt-4 text-socle-danger">Graphe indisponible ou accès refusé.</p>
      </main>
    )
  }

  return (
    <main className="flex min-h-[calc(100vh-60px)] flex-col">
      <div className="flex shrink-0 items-center justify-between gap-4 border-b border-socle-line px-6 py-3 md:px-10">
        <div className="breadcrumb">
          <Link to="/spaces">Espaces</Link>
          <span className="text-[#DEDEE1]">→</span>
          <Link to={`/spaces/${spaceId}`}>{space.data?.name ?? 'Espace'}</Link>
          <span className="text-[#DEDEE1]">→</span>
          <span className="font-medium text-socle-ink">Vue graphe</span>
        </div>
        <div className="flex flex-wrap items-center gap-4 text-[12.5px] text-socle-muted">
          <span className="inline-flex items-center gap-1.5">
            <span className="inline-block h-0.5 w-5 bg-socle-accent" aria-hidden />
            Intra-espace
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span
              className="inline-block h-0.5 w-5 border-t-2 border-dashed"
              style={{ borderColor: INTER_COLOR }}
              aria-hidden
            />
            Inter-espace
          </span>
        </div>
      </div>

      <div
        ref={containerRef}
        data-testid="transclusion-graph"
        className="relative min-h-[480px] flex-1 bg-socle-soft"
        role="img"
        aria-label="Graphe de dépendances de transclusion"
      />

      {graph.data.nodes.length === 0 ? (
        <p className="absolute bottom-8 left-1/2 -translate-x-1/2 text-sm text-socle-muted">
          Aucun document visible dans cet espace.
        </p>
      ) : null}
    </main>
  )
}
