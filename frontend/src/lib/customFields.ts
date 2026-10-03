// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'
import type { components } from './api-types'

/** Types du backend (document_custom_field_definitions.field_type). */
export type CustomFieldType =
  | 'texte'
  | 'nombre'
  | 'case_a_cocher'
  | 'date'
  | 'lien'
  | 'personne'
  | 'liste'
  | 'multi_selection'
  | string

export type CustomFieldView = components['schemas']['CustomFieldView'] & {
  id: string
  name: string
  slug: string
  fieldType: string
  required: boolean
}

export function customFieldsKey(documentId: string) {
  return ['document-custom-fields', documentId] as const
}

/** GET /api/v1/documents/{id}/custom-fields — définitions applicables + valeurs. */
export async function listDocumentCustomFields(api: Pick<AxiosInstance, 'get'>, documentId: string) {
  const { data } = await api.get<CustomFieldView[]>(`/api/v1/documents/${documentId}/custom-fields`)
  return data
}

/** PUT /api/v1/documents/{id}/custom-fields/{fieldId} — `value: null` efface. */
export async function setDocumentCustomField(
  api: Pick<AxiosInstance, 'put'>,
  documentId: string,
  fieldId: string,
  value: unknown,
) {
  const { data } = await api.put<CustomFieldView>(
    `/api/v1/documents/${documentId}/custom-fields/${fieldId}`,
    { value },
  )
  return data
}

export type FieldOption = { value: string; label: string }

export function fieldOptions(options: unknown): FieldOption[] {
  const raw = Array.isArray(options)
    ? options
    : options && typeof options === 'object' && Array.isArray((options as { choices?: unknown }).choices)
      ? (options as { choices: unknown[] }).choices
      : []
  const out: FieldOption[] = []
  for (const o of raw) {
    if (typeof o === 'string') out.push({ value: o, label: o })
    else if (o && typeof o === 'object') {
      const rec = o as { value?: unknown; label?: unknown }
      const v = rec.value ?? rec.label
      if (typeof v === 'string') out.push({ value: v, label: typeof rec.label === 'string' ? rec.label : v })
    }
  }
  return out
}

/** Valeur affichée dans l'input (chaîne) pour un champ typé. */
export function fieldInputValue(field: Pick<CustomFieldView, 'value' | 'fieldType'>): string {
  const v = field.value
  if (v == null) return ''
  if (typeof v === 'string') return v
  if (typeof v === 'number') return String(v)
  return ''
}

/** Valeur JSON à envoyer depuis la saisie ; `undefined` si invalide (non envoyé). */
export function parseFieldInput(fieldType: CustomFieldType, raw: string): unknown | undefined {
  const t = raw.trim()
  if (t === '') return null
  if (fieldType === 'nombre') {
    const n = Number(t.replace(',', '.'))
    return Number.isFinite(n) ? n : undefined
  }
  return t
}
