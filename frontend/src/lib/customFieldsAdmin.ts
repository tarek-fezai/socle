// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'
import type { CustomFieldType } from './customFields'

export type FieldStatus = 'draft' | 'active' | 'archived'

export type FieldAdminView = {
  id: string
  name: string
  slug: string
  helpText: string | null
  fieldType: CustomFieldType
  scope: string
  scopeSpaceName: string | null
  required: boolean
  options: unknown
  status: FieldStatus
  documentCount: number
  createdAt: string | null
}

export type FieldListResponse = {
  fields: FieldAdminView[]
}

export type CreateFieldRequest = {
  name: string
  helpText?: string | null
  fieldType: CustomFieldType
  scope: string
  required: boolean
  options?: unknown
  status?: FieldStatus
}

export type UpdateFieldRequest = CreateFieldRequest & {
  archiveRemovedOptions?: boolean
}

export function customFieldsAdminKey() {
  return ['admin', 'custom-fields'] as const
}

export async function listAdminCustomFields(api: Pick<AxiosInstance, 'get'>) {
  const { data } = await api.get<FieldListResponse>('/api/v1/admin/custom-fields')
  return data
}

export async function createAdminCustomField(
  api: Pick<AxiosInstance, 'post'>,
  body: CreateFieldRequest,
) {
  const { data } = await api.post<FieldAdminView>('/api/v1/admin/custom-fields', body)
  return data
}

export async function updateAdminCustomField(
  api: Pick<AxiosInstance, 'put'>,
  id: string,
  body: UpdateFieldRequest,
) {
  const { data } = await api.put<FieldAdminView>(`/api/v1/admin/custom-fields/${id}`, body)
  return data
}

export async function deleteAdminCustomField(api: Pick<AxiosInstance, 'delete'>, id: string) {
  const { data } = await api.delete<FieldAdminView>(`/api/v1/admin/custom-fields/${id}`)
  return data
}

export const FIELD_TYPE_META: {
  type: CustomFieldType
  label: string
  hint: string
}[] = [
  { type: 'texte', label: 'Texte', hint: 'Chaîne libre' },
  { type: 'liste', label: 'Liste (unique)', hint: 'Une valeur parmi une liste définie' },
  { type: 'multi_selection', label: 'Multi-sélection', hint: 'Plusieurs valeurs' },
  { type: 'date', label: 'Date', hint: 'Sélecteur de date' },
  { type: 'nombre', label: 'Nombre', hint: 'Valeur numérique' },
  { type: 'lien', label: 'Lien', hint: 'URL externe' },
  { type: 'personne', label: 'Personne', hint: "Membre de l'organisation" },
  { type: 'case_a_cocher', label: 'Case à cocher', hint: 'Vrai / faux' },
]

export function fieldTypeLabel(type: string): string {
  return FIELD_TYPE_META.find((m) => m.type === type)?.label ?? type
}

export function fieldScopeLabel(field: Pick<FieldAdminView, 'scope' | 'scopeSpaceName'>): string {
  if (field.scope === 'all_spaces') return 'Tous les espaces'
  const name = field.scopeSpaceName?.trim()
  return name ? `Espace : ${name}` : 'Espace spécifique'
}

export function fieldStatusLabel(status: FieldStatus): string {
  switch (status) {
    case 'active':
      return 'Actif'
    case 'archived':
      return 'Archivé'
    default:
      return 'Brouillon'
  }
}

export function choicesFromOptions(options: unknown): string[] {
  const raw = Array.isArray(options)
    ? options
    : options && typeof options === 'object' && Array.isArray((options as { choices?: unknown }).choices)
      ? (options as { choices: unknown[] }).choices
      : []
  const out: string[] = []
  for (const o of raw) {
    if (typeof o === 'string') out.push(o)
    else if (o && typeof o === 'object') {
      const rec = o as { value?: unknown; label?: unknown }
      const v = rec.value ?? rec.label
      if (typeof v === 'string') out.push(v)
    }
  }
  return out
}

export function optionsFromChoices(choices: string[]): { choices: { value: string; label: string }[] } {
  return {
    choices: choices.map((c) => ({ value: c, label: c })),
  }
}
