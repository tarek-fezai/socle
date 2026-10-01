// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'
import type { TipTapNode } from './documents'

/* ------------------------------------------------------------------ */
/* Types API (miroir de eu.socle.template.TemplateDtos)                */
/* ------------------------------------------------------------------ */

export type TemplateScope = 'global' | 'space'

/** Entrée de liste (`GET /templates`) — sans corps. */
export type TemplateSummary = {
  id: string
  name: string
  description: string | null
  docType: string | null
  scope: TemplateScope
  /** null pour un modèle global (toute l'organisation) */
  spaceId: string | null
  defaultTagIds: string[]
  version: number
  createdBy?: string | null
  updatedBy?: string | null
  createdAt: string
  updatedAt: string
  /** L'appelant peut modifier / supprimer ce modèle. */
  canManage: boolean
}

/** Détail (`GET /templates/{id}`) — corps TipTap inclus. */
export type TemplateResponse = TemplateSummary & {
  body: Record<string, unknown>
}

export type TemplateCreateInput = {
  name: string
  description?: string | null
  docType?: string | null
  defaultTagIds?: string[]
  body: Record<string, unknown>
  /** null / absent = modèle global */
  spaceId?: string | null
}

export type TemplateUpdateInput = {
  name?: string
  description?: string | null
  docType?: string | null
  defaultTagIds?: string[]
  body?: Record<string, unknown>
}

export type SaveAsTemplateInput = {
  scope: TemplateScope
  spaceId?: string
  name: string
  description?: string | null
}

/** Avertissement non bloquant à la création (`transclusion_restricted`, …). */
export type CreationWarning = {
  code: string
  message: string
  targetDocumentIds: string[]
}

export type CreationWarningsResponse = {
  templateId: string
  spaceId: string
  warnings: CreationWarning[]
}

/** Réponse de `POST /documents/{id}/save-as-template`. */
export type SaveAsTemplateResponse = {
  template: TemplateResponse
  warnings: string[]
}

/* ------------------------------------------------------------------ */
/* Client API                                                          */
/* ------------------------------------------------------------------ */

export const templatesKey = (spaceId?: string | null) => ['templates', spaceId ?? 'global'] as const

/** Modèles globaux + ceux de `spaceId` si l'appelant y a accès (sans spaceId : globaux seuls). */
export async function listTemplates(api: AxiosInstance, spaceId?: string | null) {
  const { data } = await api.get<TemplateSummary[]>('/api/v1/templates', {
    params: spaceId ? { spaceId } : undefined,
  })
  return Array.isArray(data) ? data : []
}

export async function getTemplate(api: AxiosInstance, id: string) {
  const { data } = await api.get<TemplateResponse>(`/api/v1/templates/${id}`)
  return data
}

export async function createTemplate(api: AxiosInstance, body: TemplateCreateInput) {
  const { spaceId, ...rest } = body
  const { data } = await api.post<TemplateResponse>('/api/v1/templates', {
    ...rest,
    ...(spaceId ? { spaceId } : {}),
  })
  return data
}

export async function updateTemplate(api: AxiosInstance, id: string, body: TemplateUpdateInput) {
  const { data } = await api.patch<TemplateResponse>(`/api/v1/templates/${id}`, body)
  return data
}

export async function deleteTemplate(api: AxiosInstance, id: string) {
  await api.delete(`/api/v1/templates/${id}`)
}

/**
 * `CreationWarningsResponse` → messages affichables. Tolère les avertissements déjà sous forme
 * de chaîne ; ignore les entrées vides ; replie sur `code` si le message est absent.
 */
export function mapCreationWarnings(
  data: { warnings?: Array<CreationWarning | string | null | undefined> } | null | undefined,
): string[] {
  if (!Array.isArray(data?.warnings)) return []
  return data.warnings
    .map((w) => {
      if (typeof w === 'string') return w
      const message = w?.message?.trim()
      if (message) return message
      return w?.code?.trim() ?? ''
    })
    .map((s) => s.trim())
    .filter(Boolean)
}

/** Avertissements à la création depuis un modèle (ex. transclusion restreinte). */
export async function getCreationWarnings(
  api: AxiosInstance,
  templateId: string,
  spaceId: string,
): Promise<string[]> {
  const { data } = await api.get<CreationWarningsResponse>(
    `/api/v1/templates/${templateId}/creation-warnings`,
    { params: { spaceId } },
  )
  return mapCreationWarnings(data)
}

export async function saveDocumentAsTemplate(
  api: AxiosInstance,
  documentId: string,
  input: SaveAsTemplateInput,
): Promise<SaveAsTemplateResponse> {
  const { data } = await api.post<Partial<SaveAsTemplateResponse>>(
    `/api/v1/documents/${documentId}/save-as-template`,
    {
      scope: input.scope,
      ...(input.scope === 'space' && input.spaceId ? { spaceId: input.spaceId } : {}),
      name: input.name,
      ...(input.description ? { description: input.description } : {}),
    },
  )
  return {
    template: data.template as TemplateResponse,
    warnings: Array.isArray(data.warnings) ? data.warnings : [],
  }
}

/* ------------------------------------------------------------------ */
/* Helpers purs                                                        */
/* ------------------------------------------------------------------ */

/** Variables texte reconnues par le backend à la création (insérées telles quelles). */
export const TEMPLATE_VARIABLES = ['{{date}}', '{{auteur}}', '{{espace}}', '{{titre}}'] as const

export const PLACEHOLDER_NODE_TYPE = 'placeholder'

function walk(node: unknown, visit: (n: TipTapNode) => void) {
  if (!node || typeof node !== 'object') return
  const n = node as TipTapNode
  visit(n)
  if (Array.isArray(n.content)) for (const child of n.content) walk(child, visit)
}

/** Nombre de nœuds `placeholder` (zones à compléter) dans un corps TipTap. */
export function countPlaceholders(body: unknown): number {
  let count = 0
  walk(body, (n) => {
    if (n.type === PLACEHOLDER_NODE_TYPE) count += 1
  })
  return count
}

/** Indices (`attrs.hint`) des zones à compléter, dans l'ordre du document. Hints vides → « Zone à compléter ». */
export function listPlaceholderHints(body: unknown): string[] {
  const hints: string[] = []
  walk(body, (n) => {
    if (n.type !== PLACEHOLDER_NODE_TYPE) return
    const hint = n.attrs?.hint
    hints.push(typeof hint === 'string' && hint.trim() ? hint.trim() : DEFAULT_PLACEHOLDER_HINT)
  })
  return hints
}

export const DEFAULT_PLACEHOLDER_HINT = 'Zone à compléter'

/** Libellé « N zone(s) à compléter ». */
export function placeholderBannerText(count: number): string {
  return `${count} zone${count > 1 ? 's' : ''} à compléter`
}

/** Un document non visible de toute l'organisation expose son contenu une fois transformé en modèle. */
export function needsVisibilityWarning(visibility: string | null | undefined): boolean {
  return Boolean(visibility) && visibility !== 'organisation'
}

export function visibilityLabel(visibility: string | null | undefined): string {
  switch (visibility) {
    case 'space':
      return 'espace'
    case 'restricted':
      return 'restreinte'
    case 'organisation':
      return 'organisation'
    default:
      return visibility ?? 'inconnue'
  }
}

/** Libellé de portée affiché sur les cartes de modèle. */
export function scopeLabel(scope: string | null | undefined): string {
  return scope === 'space' ? 'Espace' : 'Organisation'
}

/** Normalise un champ libre « tags » (UUID séparés par virgule/espace/retour ligne). */
export function parseTagIds(raw: string): string[] {
  return Array.from(
    new Set(
      raw
        .split(/[\s,;]+/)
        .map((s) => s.trim())
        .filter(Boolean),
    ),
  )
}

export function formatTagIds(ids: string[] | null | undefined): string {
  return (ids ?? []).join(', ')
}

/** Filtre de recherche (nom, description, type) insensible à la casse et aux accents. */
export function filterTemplates<T extends Pick<TemplateSummary, 'name' | 'description' | 'docType'>>(
  templates: T[],
  query: string,
): T[] {
  const norm = (s: string) =>
    s
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .toLowerCase()
  const q = norm(query.trim())
  if (!q) return templates
  return templates.filter((t) =>
    norm(`${t.name} ${t.description ?? ''} ${t.docType ?? ''}`).includes(q),
  )
}

type ErrorResponse = {
  status?: number
  data?: { error?: string; message?: string; detail?: string }
}

function errorResponse(error: unknown): ErrorResponse | undefined {
  if (error && typeof error === 'object' && 'response' in error) {
    return (error as { response?: ErrorResponse }).response
  }
  return undefined
}

/**
 * 409 d'approbation quand des zones à compléter subsistent : renvoie le message du corps de
 * la réponse s'il existe, sinon un message par défaut ; `null` si l'erreur n'est pas concernée.
 */
export function placeholderConflictMessage(error: unknown): string | null {
  const response = errorResponse(error)
  if (response?.status !== 409) return null
  const data = response.data
  const code = data?.error ?? ''
  const text = data?.message ?? data?.detail ?? ''
  if (!/placeholder|zone/i.test(`${code} ${text}`)) return null
  return text || 'Des zones à compléter subsistent : complétez-les avant de soumettre pour approbation.'
}
