// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi } from 'vitest'
import {
  fieldInputValue,
  fieldOptions,
  listDocumentCustomFields,
  parseFieldInput,
  setDocumentCustomField,
} from './customFields'
import { attachTag, detachTag, filterTagSuggestions, searchTags, sortTags } from './tags'
import {
  INACCESSIBLE_LABEL,
  brokenLinkLabel,
  brokenLinkMessage,
  findLongParagraphs,
  getWritingHints,
  longParagraphMessage,
} from './writingAssistant'

const para = (text: string) => ({ type: 'paragraph', content: [{ type: 'text', text }] })
const words = (n: number) => Array.from({ length: n }, (_, i) => `mot${i}`).join(' ')

describe('tags', () => {
  it('appelle les routes de l’API tags', async () => {
    const get = vi.fn().mockResolvedValue({ data: [{ id: 't', name: 'IAM', color: '#3730E0' }] })
    const post = vi.fn().mockResolvedValue({ data: { id: 't', name: 'IAM', color: '#3730E0' } })
    const del = vi.fn().mockResolvedValue({})
    await searchTags({ get } as never, '  ia ', 5)
    expect(get).toHaveBeenCalledWith('/api/v1/tags', { params: { q: 'ia', limit: 5 } })
    await attachTag({ post } as never, 'd1', { name: 'RGPD' })
    expect(post).toHaveBeenCalledWith('/api/v1/documents/d1/tags', { name: 'RGPD' })
    await detachTag({ delete: del } as never, 'd1', 't')
    expect(del).toHaveBeenCalledWith('/api/v1/documents/d1/tags/t')
  })

  it('filtre les suggestions déjà posées et les doublons de casse, trie par nom', () => {
    const a = { id: '1', name: 'IAM', color: '#000' }
    const b = { id: '2', name: 'iam', color: '#000' }
    const c = { id: '3', name: 'Critique', color: '#000' }
    expect(filterTagSuggestions([a, b, c], [c])).toEqual([a])
    expect(sortTags([a, c]).map((t) => t.name)).toEqual(['Critique', 'IAM'])
  })
})

describe('champs personnalisés', () => {
  it('liste et enregistre une valeur', async () => {
    const get = vi.fn().mockResolvedValue({ data: [] })
    const put = vi.fn().mockResolvedValue({ data: { id: 'f' } })
    await listDocumentCustomFields({ get } as never, 'd1')
    expect(get).toHaveBeenCalledWith('/api/v1/documents/d1/custom-fields')
    await setDocumentCustomField({ put } as never, 'd1', 'f', 12)
    expect(put).toHaveBeenCalledWith('/api/v1/documents/d1/custom-fields/f', { value: 12 })
  })

  it('convertit la saisie selon le type', () => {
    expect(parseFieldInput('texte', ' x ')).toBe('x')
    expect(parseFieldInput('texte', '  ')).toBeNull()
    expect(parseFieldInput('nombre', '3,5')).toBe(3.5)
    expect(parseFieldInput('nombre', 'abc')).toBeUndefined()
    expect(fieldInputValue({ value: 4, fieldType: 'nombre' })).toBe('4')
    expect(fieldInputValue({ value: null, fieldType: 'texte' })).toBe('')
    expect(fieldOptions(['a', { value: 'b', label: 'B' }])).toEqual([
      { value: 'a', label: 'a' },
      { value: 'b', label: 'B' },
    ])
  })
})

describe('assistant de rédaction', () => {
  it('lit les indices du serveur', async () => {
    const get = vi.fn().mockResolvedValue({ data: { longParagraphThresholdWords: 120, brokenLinks: [], longParagraphs: [] } })
    await getWritingHints({ get } as never, 'd1')
    expect(get).toHaveBeenCalledWith('/api/v1/documents/d1/writing-assistant')
  })

  it('affiche le texte d’ancre source ; fallback « Document inaccessible »', () => {
    expect(brokenLinkLabel({ label: 'voir la procédure RH' })).toBe('voir la procédure RH')
    expect(brokenLinkLabel({ label: '  ' })).toBe(INACCESSIBLE_LABEL)
    expect(brokenLinkLabel({ label: undefined })).toBe(INACCESSIBLE_LABEL)
  })

  it('« n’existe plus » si reason=deleted, sinon inaccessible', () => {
    expect(brokenLinkMessage({
      targetId: 'x',
      label: 'Procédure de provisioning v9',
      accessible: false,
      reason: 'deleted',
    })).toContain("n'existe plus")
    expect(brokenLinkMessage({
      targetId: 'y',
      label: 'voir la procédure RH',
      accessible: false,
      reason: 'inaccessible',
    })).toContain("n'est pas accessible")
  })

  it('repère les paragraphes au-delà du seuil avec leur rang', () => {
    const body = { type: 'doc', content: [para('court'), para(words(130)), para(words(120)), para(words(121))] }
    const found = findLongParagraphs(body, 120)
    expect(found.map((f) => [f.index, f.wordCount])).toEqual([
      [1, 130],
      [3, 121],
    ])
    expect(found[0]!.excerpt.length).toBeLessThanOrEqual(81)
    expect(longParagraphMessage(found[0]!, 120)).toContain('130')
  })

  it('applique un seuil personnalisé et ignore les transclusions', () => {
    const body = {
      type: 'doc',
      content: [para(words(45)), { type: 'transclusion', content: [para(words(500))] }],
    }
    expect(findLongParagraphs(body, 40)).toHaveLength(1)
    expect(findLongParagraphs(body, 50)).toHaveLength(0)
  })
})
