// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it, vi } from 'vitest'
import type { AxiosInstance } from 'axios'
import {
  countPlaceholders,
  createTemplate,
  deleteTemplate,
  filterTemplates,
  getCreationWarnings,
  getTemplate,
  listPlaceholderHints,
  mapCreationWarnings,
  listTemplates,
  needsVisibilityWarning,
  parseTagIds,
  placeholderBannerText,
  placeholderConflictMessage,
  saveDocumentAsTemplate,
  scopeLabel,
  updateTemplate,
  type TemplateSummary,
} from './templates'

const body = {
  type: 'doc',
  content: [
    { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: 'Objet' }] },
    { type: 'placeholder', attrs: { hint: 'Décrire le périmètre' } },
    {
      type: 'bulletList',
      content: [
        {
          type: 'listItem',
          content: [{ type: 'placeholder', attrs: { hint: '  Responsable  ' } }],
        },
      ],
    },
    { type: 'placeholder', attrs: {} },
    { type: 'paragraph', content: [{ type: 'text', text: '{{date}}' }] },
  ],
}

function mockApi() {
  return {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  } as unknown as AxiosInstance & Record<'get' | 'post' | 'patch' | 'delete', ReturnType<typeof vi.fn>>
}

describe('countPlaceholders / listPlaceholderHints', () => {
  it('compte les zones à compléter, y compris imbriquées', () => {
    expect(countPlaceholders(body)).toBe(3)
  })

  it('renvoie 0 pour un corps vide ou invalide', () => {
    expect(countPlaceholders(null)).toBe(0)
    expect(countPlaceholders(undefined)).toBe(0)
    expect(countPlaceholders({ type: 'doc' })).toBe(0)
    expect(countPlaceholders({ type: 'doc', content: [{ type: 'paragraph' }] })).toBe(0)
    expect(countPlaceholders('texte')).toBe(0)
  })

  it('liste les indices dans l’ordre, avec indice par défaut', () => {
    expect(listPlaceholderHints(body)).toEqual([
      'Décrire le périmètre',
      'Responsable',
      'Zone à compléter',
    ])
    expect(listPlaceholderHints({ type: 'doc', content: [] })).toEqual([])
  })

  it('formate le libellé du bandeau', () => {
    expect(placeholderBannerText(1)).toBe('1 zone à compléter')
    expect(placeholderBannerText(3)).toBe('3 zones à compléter')
  })
})

describe('helpers', () => {
  it('avertit si le document n’est pas visible de toute l’organisation', () => {
    expect(needsVisibilityWarning('organisation')).toBe(false)
    expect(needsVisibilityWarning('space')).toBe(true)
    expect(needsVisibilityWarning('restricted')).toBe(true)
    expect(needsVisibilityWarning(undefined)).toBe(false)
  })

  it('parse une liste de tags dédoublonnée', () => {
    expect(parseTagIds('a, b;c  a\nd')).toEqual(['a', 'b', 'c', 'd'])
    expect(parseTagIds('  ')).toEqual([])
  })

  it('filtre sans tenir compte de la casse ni des accents', () => {
    const t = (name: string, description: string | null, docType: string | null) =>
      ({ id: name, name, description, docType }) as TemplateSummary
    const list = [t('Procédure', 'Étapes', 'procedure'), t('Politique', null, 'politique')]
    expect(filterTemplates(list, 'PROCEDURE')).toHaveLength(1)
    expect(filterTemplates(list, 'etapes')[0].name).toBe('Procédure')
    expect(filterTemplates(list, '')).toHaveLength(2)
    expect(filterTemplates(list, 'zzz')).toHaveLength(0)
  })

  it('extrait le message d’un 409 « zones à compléter »', () => {
    expect(
      placeholderConflictMessage({
        response: { status: 409, data: { error: 'placeholders_remaining', message: '2 zones restantes' } },
      }),
    ).toBe('2 zones restantes')
    expect(
      placeholderConflictMessage({ response: { status: 409, data: { error: 'placeholders_remaining' } } }),
    ).toMatch(/zones à compléter/)
    expect(
      placeholderConflictMessage({ response: { status: 409, data: { error: 'step_advanced' } } }),
    ).toBeNull()
    expect(placeholderConflictMessage({ response: { status: 500 } })).toBeNull()
    expect(placeholderConflictMessage(new Error('x'))).toBeNull()
  })
})

describe('client API', () => {
  it('listTemplates passe spaceId en query seulement si fourni', async () => {
    const api = mockApi()
    api.get.mockResolvedValue({ data: [{ id: 't1' }] })
    expect(await listTemplates(api, 's1')).toEqual([{ id: 't1' }])
    expect(api.get).toHaveBeenCalledWith('/api/v1/templates', { params: { spaceId: 's1' } })
    await listTemplates(api)
    expect(api.get).toHaveBeenLastCalledWith('/api/v1/templates', { params: undefined })
  })

  it('listTemplates tolère une réponse inattendue', async () => {
    const api = mockApi()
    api.get.mockResolvedValue({ data: null })
    expect(await listTemplates(api)).toEqual([])
  })

  it('createTemplate omet spaceId pour un modèle global', async () => {
    const api = mockApi()
    api.post.mockResolvedValue({ data: { id: 't1' } })
    await createTemplate(api, { name: 'N', body: {}, spaceId: null })
    expect(api.post).toHaveBeenCalledWith('/api/v1/templates', { name: 'N', body: {} })
    await createTemplate(api, { name: 'N', body: {}, spaceId: 's1' })
    expect(api.post).toHaveBeenLastCalledWith('/api/v1/templates', {
      name: 'N',
      body: {},
      spaceId: 's1',
    })
  })

  it('updateTemplate / deleteTemplate', async () => {
    const api = mockApi()
    api.patch.mockResolvedValue({ data: { id: 't1' } })
    api.delete.mockResolvedValue({})
    await updateTemplate(api, 't1', { name: 'X' })
    expect(api.patch).toHaveBeenCalledWith('/api/v1/templates/t1', { name: 'X' })
    await deleteTemplate(api, 't1')
    expect(api.delete).toHaveBeenCalledWith('/api/v1/templates/t1')
  })

  it('getCreationWarnings : { templateId, spaceId, warnings[] } → messages', async () => {
    const api = mockApi()
    api.get.mockResolvedValueOnce({
      data: {
        templateId: 't1',
        spaceId: 's1',
        warnings: [
          {
            code: 'transclusion_restricted',
            message: '1 transclusion(s) du modèle seront masquées.',
            targetDocumentIds: ['d9'],
          },
        ],
      },
    })
    expect(await getCreationWarnings(api, 't1', 's1')).toEqual([
      '1 transclusion(s) du modèle seront masquées.',
    ])
    expect(api.get).toHaveBeenCalledWith('/api/v1/templates/t1/creation-warnings', {
      params: { spaceId: 's1' },
    })
    api.get.mockResolvedValueOnce({ data: { templateId: 't1', spaceId: 's1', warnings: [] } })
    expect(await getCreationWarnings(api, 't1', 's1')).toEqual([])
    api.get.mockResolvedValueOnce({ data: {} })
    expect(await getCreationWarnings(api, 't1', 's1')).toEqual([])
  })

  it('getTemplate renvoie le détail avec corps', async () => {
    const api = mockApi()
    api.get.mockResolvedValue({ data: { id: 't1', body: { type: 'doc' }, canManage: true } })
    const t = await getTemplate(api, 't1')
    expect(api.get).toHaveBeenCalledWith('/api/v1/templates/t1')
    expect(t.body).toEqual({ type: 'doc' })
  })

  it('saveDocumentAsTemplate renvoie { template, warnings } tel que le backend', async () => {
    const api = mockApi()
    const template = { id: 't9', name: 'M', scope: 'global', body: { type: 'doc' } }
    api.post.mockResolvedValue({
      data: { template, warnings: ['Le document source n’est pas en visibilité « organisation »'] },
    })
    const res = await saveDocumentAsTemplate(api, 'd1', { scope: 'global', name: 'M' })
    expect(res.template.id).toBe('t9')
    expect(res.warnings).toEqual(['Le document source n’est pas en visibilité « organisation »'])
  })

  it('saveDocumentAsTemplate tolère des warnings absents et envoie portée + espace', async () => {
    const api = mockApi()
    api.post.mockResolvedValue({ data: { template: { id: 't1' } } })
    const res = await saveDocumentAsTemplate(api, 'd1', {
      scope: 'space',
      spaceId: 's1',
      name: 'Mon modèle',
      description: 'desc',
    })
    expect(res.warnings).toEqual([])
    expect(api.post).toHaveBeenCalledWith('/api/v1/documents/d1/save-as-template', {
      scope: 'space',
      spaceId: 's1',
      name: 'Mon modèle',
      description: 'desc',
    })
    await saveDocumentAsTemplate(api, 'd1', { scope: 'global', spaceId: 's1', name: 'G' })
    expect(api.post).toHaveBeenLastCalledWith('/api/v1/documents/d1/save-as-template', {
      scope: 'global',
      name: 'G',
    })
  })
})

describe('mapCreationWarnings', () => {
  it('extrait les messages, ignore les entrées vides, replie sur le code', () => {
    expect(
      mapCreationWarnings({
        warnings: [
          { code: 'a', message: '  Message A  ', targetDocumentIds: [] },
          { code: 'b', message: '', targetDocumentIds: [] },
          { code: 'c', message: '   ', targetDocumentIds: [] },
          'Déjà une chaîne',
          null,
        ],
      }),
    ).toEqual(['Message A', 'b', 'c', 'Déjà une chaîne'])
  })

  it('renvoie [] pour une réponse absente ou mal formée', () => {
    expect(mapCreationWarnings(null)).toEqual([])
    expect(mapCreationWarnings(undefined)).toEqual([])
    expect(mapCreationWarnings({})).toEqual([])
    expect(mapCreationWarnings({ warnings: 'oops' as unknown as [] })).toEqual([])
  })
})

describe('scopeLabel', () => {
  it('libellés de portée', () => {
    expect(scopeLabel('space')).toBe('Espace')
    expect(scopeLabel('global')).toBe('Organisation')
    expect(scopeLabel(undefined)).toBe('Organisation')
  })
})