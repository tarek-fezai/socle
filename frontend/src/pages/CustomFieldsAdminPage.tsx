// SPDX-License-Identifier: AGPL-3.0-or-later
import { FormEvent, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AdminShell } from '../components/admin/AdminShell'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/apiError'
import { listSpaces } from '../lib/spaces'
import type { CustomFieldType } from '../lib/customFields'
import {
  choicesFromOptions,
  createAdminCustomField,
  customFieldsAdminKey,
  deleteAdminCustomField,
  FIELD_TYPE_META,
  fieldScopeLabel,
  fieldStatusLabel,
  fieldTypeLabel,
  listAdminCustomFields,
  optionsFromChoices,
  updateAdminCustomField,
  type FieldAdminView,
  type FieldStatus,
} from '../lib/customFieldsAdmin'

type BuilderState = {
  id: string | null
  name: string
  helpText: string
  fieldType: CustomFieldType
  scopeMode: 'all' | 'space'
  spaceId: string
  required: boolean
  status: FieldStatus
  choices: string[]
}

const LIST_TYPES = new Set<CustomFieldType>(['liste', 'multi_selection'])

function emptyBuilder(spaceId: string): BuilderState {
  return {
    id: null,
    name: '',
    helpText: '',
    fieldType: 'liste',
    scopeMode: 'space',
    spaceId,
    required: false,
    status: 'draft',
    choices: [],
  }
}

function fromField(field: FieldAdminView): BuilderState {
  return {
    id: field.id,
    name: field.name,
    helpText: field.helpText ?? '',
    fieldType: field.fieldType as CustomFieldType,
    scopeMode: field.scope === 'all_spaces' ? 'all' : 'space',
    spaceId: field.scope === 'all_spaces' ? '' : field.scope,
    required: field.required,
    status: field.status,
    choices: choicesFromOptions(field.options),
  }
}

function statusColors(status: FieldStatus): { fg: string; dot: string } {
  if (status === 'active') return { fg: '#1E8E5A', dot: '#1E8E5A' }
  if (status === 'archived') return { fg: '#9B9BA1', dot: '#C2C2C6' }
  return { fg: '#9B9BA1', dot: '#C2C2C6' }
}

/** Administration des champs personnalisés (CustomFields.dc.html). */
export function CustomFieldsAdminPage() {
  const qc = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const [builder, setBuilder] = useState<BuilderState | null>(null)
  const [newChoice, setNewChoice] = useState('')
  const [error, setError] = useState<string | null>(null)

  const fieldsQuery = useQuery({
    queryKey: customFieldsAdminKey(),
    queryFn: () => listAdminCustomFields(api),
  })
  const spacesQuery = useQuery({ queryKey: ['spaces'], queryFn: () => listSpaces(api) })

  const fields = fieldsQuery.data?.fields ?? []
  const spaces = spacesQuery.data ?? []
  const defaultSpaceId = spaces[0]?.id ?? ''

  useEffect(() => {
    if (searchParams.get('create') === '1' && !builder && defaultSpaceId) {
      setBuilder(emptyBuilder(defaultSpaceId))
      setSearchParams({}, { replace: true })
    }
  }, [searchParams, builder, defaultSpaceId, setSearchParams])

  const invalidate = () => void qc.invalidateQueries({ queryKey: customFieldsAdminKey() })

  const openNew = () => {
    setError(null)
    setBuilder(emptyBuilder(defaultSpaceId))
  }

  const openEdit = (field: FieldAdminView) => {
    setError(null)
    setBuilder(fromField(field))
  }

  const cancelBuilder = () => {
    setBuilder(null)
    setError(null)
    setNewChoice('')
  }

  const saveMut = useMutation({
    mutationFn: async (state: BuilderState) => {
      const scope =
        state.scopeMode === 'all' ? 'all_spaces' : state.spaceId || defaultSpaceId
      const options = LIST_TYPES.has(state.fieldType)
        ? optionsFromChoices(state.choices)
        : null
      const body = {
        name: state.name.trim(),
        helpText: state.helpText.trim() || null,
        fieldType: state.fieldType,
        scope,
        required: state.required,
        options,
        status: state.status,
      }
      if (state.id) {
        return updateAdminCustomField(api, state.id, { ...body, archiveRemovedOptions: false })
      }
      return createAdminCustomField(api, body)
    },
    onSuccess: () => {
      cancelBuilder()
      invalidate()
    },
    onError: (e) => setError(apiErrorMessage(e, 'Enregistrement impossible')),
  })

  const archiveMut = useMutation({
    mutationFn: (id: string) => deleteAdminCustomField(api, id),
    onSuccess: () => {
      if (builder?.id) cancelBuilder()
      invalidate()
    },
    onError: (e) => setError(apiErrorMessage(e, 'Archivage impossible')),
  })

  const builderTitle = useMemo(() => {
    if (!builder) return ''
    return builder.id ? `Modifier — ${builder.name}` : 'Aperçu du constructeur — nouveau champ'
  }, [builder])

  function addChoice() {
    const v = newChoice.trim()
    if (!v || !builder) return
    if (builder.choices.includes(v)) return
    setBuilder({ ...builder, choices: [...builder.choices, v] })
    setNewChoice('')
  }

  function removeChoice(value: string) {
    if (!builder) return
    setBuilder({ ...builder, choices: builder.choices.filter((c) => c !== value) })
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (!builder || !builder.name.trim()) return
    if (builder.scopeMode === 'space' && !builder.spaceId && !defaultSpaceId) return
    saveMut.mutate(builder)
  }

  return (
    <AdminShell
      active="custom-fields"
      breadcrumb={[
        { label: 'Compte', to: '/' },
        { label: 'Administration', to: '/admin/tags' },
        { label: 'Champs personnalisés' },
      ]}
      mainClassName="admin-main--fields"
      innerWide
      rightRail={
        <aside className="admin-rail admin-rail--fields" data-mock-id="custom-fields-rail">
          <div>
            <div className="admin-rail__label">Où apparaissent les champs</div>
            <p className="admin-rail__para">
              Les champs actifs s&apos;affichent dans le panneau « Métadonnées » de l&apos;éditeur,
              dans la barre latérale de lecture, et peuvent être utilisés comme filtres de recherche
              et de workflow.
            </p>
          </div>
          <div>
            <div className="admin-rail__label">Limites</div>
            <div className="admin-rail__limits">
              <span>20 champs actifs maximum par organisation</span>
              <span>50 valeurs maximum par liste</span>
            </div>
          </div>
          <div className="admin-rail__divider">
            <div className="admin-rail__label">Voir aussi</div>
            <Link to="/admin/tags" className="admin-rail__link">
              Tags →
            </Link>
            <Link to="/admin/templates" className="admin-rail__link">
              Modèles →
            </Link>
          </div>
        </aside>
      }
    >
      <div className="admin-title-row">
        <h1 className="admin-title" data-mock-id="custom-fields-title">
          Champs personnalisés
        </h1>
        <button type="button" className="admin-cta" data-mock-id="custom-fields-cta" onClick={openNew}>
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#FFFFFF" strokeWidth="2.4" strokeLinecap="round" aria-hidden>
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          Nouveau champ
        </button>
      </div>
      <p className="admin-lead" data-mock-id="custom-fields-stats">
        Étendez les métadonnées des documents au-delà des champs fixes (propriétaire, fiabilité,
        tags) avec des champs propres à votre organisation.
      </p>

      {fieldsQuery.isError && (
        <p className="admin-alert admin-alert--error" role="alert">
          {apiErrorMessage(fieldsQuery.error, 'Impossible de charger les champs')}
        </p>
      )}

      <div className="admin-table-wrap admin-table-wrap--rounded12" data-mock-id="custom-fields-table">
        <div className="admin-table-head admin-table-head--fields">
          <div style={{ flex: 2 }}>Champ</div>
          <div style={{ flex: 1.1 }}>Type</div>
          <div style={{ flex: 1.6 }}>Portée</div>
          <div style={{ flex: 0.9 }}>Obligatoire</div>
          <div style={{ flex: 0.9 }}>Utilisation</div>
          <div style={{ flex: 0.6, textAlign: 'right' }}>Statut</div>
        </div>
        {fields.map((field) => {
          const st = statusColors(field.status)
          const selected = builder?.id === field.id
          return (
            <div
              key={field.id}
              className={`admin-table-row admin-table-row--fields admin-row-click${selected ? ' admin-row-click--selected' : ''}`}
              onClick={() => openEdit(field)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') openEdit(field)
              }}
              role="button"
              tabIndex={0}
            >
              <div style={{ flex: 2 }}>
                <div
                  className={`admin-field-name${field.status === 'draft' ? ' admin-field-name--draft' : ''}`}
                >
                  {field.name}
                </div>
                <div className="admin-field-slug">{field.slug}</div>
              </div>
              <div style={{ flex: 1.1 }}>
                <span
                  className={`admin-type-chip${field.status === 'draft' ? ' admin-type-chip--muted' : ''}`}
                >
                  {fieldTypeLabel(field.fieldType)}
                </span>
              </div>
              <div style={{ flex: 1.6, fontSize: 13, color: field.status === 'draft' ? '#9B9BA1' : '#6B6B72' }}>
                {fieldScopeLabel(field)}
              </div>
              <div style={{ flex: 0.9 }}>
                <span
                  style={{
                    fontSize: 11.5,
                    fontWeight: 600,
                    color: field.required ? '#1E8E5A' : '#9B9BA1',
                  }}
                >
                  {field.required ? 'Oui' : 'Non'}
                </span>
              </div>
              <div style={{ flex: 0.9, fontSize: 13, color: field.status === 'draft' ? '#9B9BA1' : '#6B6B72' }}>
                {field.documentCount > 0 ? `${field.documentCount} documents` : '—'}
              </div>
              <div style={{ flex: 0.6, textAlign: 'right' }}>
                <span className="admin-status" style={{ color: st.fg }}>
                  <span className="admin-status__dot" style={{ background: st.dot }} aria-hidden />
                  {fieldStatusLabel(field.status)}
                </span>
              </div>
            </div>
          )
        })}
      </div>

      {builder && (
        <>
          <div className="admin-builder-label" data-mock-id="custom-fields-builder-label">
            {builderTitle}
          </div>
          <form data-mock-id="custom-fields-builder" onSubmit={onSubmit}>
            <div className="admin-builder">
            <div className="admin-builder__left">
              <label className="admin-form-label" htmlFor="cf-name">
                Nom du champ
              </label>
              <input
                id="cf-name"
                className="admin-form-input"
                value={builder.name}
                onChange={(e) => setBuilder({ ...builder, name: e.target.value })}
                required
              />

              <label className="admin-form-label" htmlFor="cf-help">
                Description d&apos;aide (optionnelle)
              </label>
              <input
                id="cf-help"
                className="admin-form-input admin-form-input--help"
                value={builder.helpText}
                onChange={(e) => setBuilder({ ...builder, helpText: e.target.value })}
              />

              <span className="admin-form-label">Portée</span>
              <div className="admin-scope-toggle">
                <button
                  type="button"
                  className={`admin-scope-chip${builder.scopeMode === 'space' ? ' admin-scope-chip--on' : ''}`}
                  onClick={() => setBuilder({ ...builder, scopeMode: 'space' })}
                >
                  Espace spécifique
                </button>
                <button
                  type="button"
                  className={`admin-scope-chip${builder.scopeMode === 'all' ? ' admin-scope-chip--on' : ''}`}
                  onClick={() => setBuilder({ ...builder, scopeMode: 'all' })}
                >
                  Tous les espaces
                </button>
              </div>
              {builder.scopeMode === 'space' && (
                <select
                  className="admin-form-input"
                  value={builder.spaceId || defaultSpaceId}
                  onChange={(e) => setBuilder({ ...builder, spaceId: e.target.value })}
                  aria-label="Espace"
                >
                  {spaces.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
              )}

              <label className="admin-toggle-row">
                <input
                  type="checkbox"
                  checked={builder.required}
                  onChange={(e) => setBuilder({ ...builder, required: e.target.checked })}
                />
                Champ obligatoire à la publication
              </label>

              <span className="admin-form-label">Statut</span>
              <select
                className="admin-form-input"
                value={builder.status}
                onChange={(e) =>
                  setBuilder({ ...builder, status: e.target.value as FieldStatus })
                }
                aria-label="Statut"
              >
                <option value="draft">Brouillon</option>
                <option value="active">Actif</option>
                <option value="archived">Archivé</option>
              </select>
            </div>

            <div className="admin-builder__right">
              <span className="admin-form-label">Type de champ</span>
              <div className="admin-type-grid">
                {FIELD_TYPE_META.map((meta) => (
                  <button
                    key={meta.type}
                    type="button"
                    className={`admin-type-option${builder.fieldType === meta.type ? ' admin-type-option--on' : ''}`}
                    onClick={() => setBuilder({ ...builder, fieldType: meta.type })}
                  >
                    <div className="admin-type-option__title">{meta.label}</div>
                    <div className="admin-type-option__hint">{meta.hint}</div>
                  </button>
                ))}
              </div>

              {LIST_TYPES.has(builder.fieldType) && (
                <div style={{ marginTop: 16 }}>
                  <span className="admin-form-label">Valeurs de la liste</span>
                  <div className="admin-choice-list">
                    {builder.choices.map((c) => (
                      <span key={c} className="admin-choice">
                        {c}
                        <button
                          type="button"
                          aria-label={`Retirer ${c}`}
                          onClick={() => removeChoice(c)}
                          style={{ border: 'none', background: 'none', cursor: 'pointer', color: '#9B9BA1' }}
                        >
                          ×
                        </button>
                      </span>
                    ))}
                    <span style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>
                      <input
                        type="text"
                        value={newChoice}
                        onChange={(e) => setNewChoice(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') {
                            e.preventDefault()
                            addChoice()
                          }
                        }}
                        placeholder="Nouvelle valeur"
                        style={{
                          border: '1px dashed #DEDEE1',
                          borderRadius: 6,
                          padding: '4px 8px',
                          fontSize: 11.5,
                          width: 100,
                        }}
                      />
                      <button type="button" className="admin-choice-add" onClick={addChoice}>
                        + Valeur
                      </button>
                    </span>
                  </div>
                </div>
              )}
            </div>
            </div>

            {error && (
              <p className="admin-alert admin-alert--error" role="alert">
                {error}
              </p>
            )}

            <div className="admin-form-actions">
              <button type="button" className="admin-btn-ghost" onClick={cancelBuilder}>
                Annuler
              </button>
              {builder.id && builder.status !== 'archived' && (
                <button
                  type="button"
                  className="admin-btn-ghost"
                  onClick={() => archiveMut.mutate(builder.id!)}
                  disabled={archiveMut.isPending}
                >
                  Archiver
                </button>
              )}
              <button type="submit" className="admin-cta" disabled={saveMut.isPending}>
                {builder.id ? 'Enregistrer' : 'Créer le champ'}
              </button>
            </div>
          </form>
        </>
      )}
    </AdminShell>
  )
}
