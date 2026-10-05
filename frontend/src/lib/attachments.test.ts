// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'
import {
  attachmentNodeAttrs,
  attachmentObjectUrl,
  attachmentPath,
  clearAttachmentUrlCache,
  formatBytes,
  isImageMediaType,
  normalizeAttachment,
  uploadAttachment,
} from './attachments'

vi.mock('./api', () => ({ api: { post: vi.fn(), get: vi.fn() } }))

beforeEach(() => {
  vi.mocked(api.post).mockReset()
  vi.mocked(api.get).mockReset()
  clearAttachmentUrlCache()
})

describe('formatBytes', () => {
  it('formate en unités décimales françaises', () => {
    expect(formatBytes(12)).toBe('12 o')
    expect(formatBytes(1500)).toBe('1,5 Ko')
    expect(formatBytes(2_000_000)).toBe('2 Mo')
    expect(formatBytes(25 * 1000 * 1000)).toBe('25 Mo')
    expect(formatBytes(undefined)).toBe('')
    expect(formatBytes(-1)).toBe('')
  })
})

describe('attachments', () => {
  it('construit les chemins d’API', () => {
    expect(attachmentPath('a/b')).toBe('/api/v1/attachments/a%2Fb')
  })

  it('détecte les types image', () => {
    expect(isImageMediaType('image/png')).toBe(true)
    expect(isImageMediaType('application/pdf')).toBe(false)
    expect(isImageMediaType(null)).toBe(false)
  })

  it('normalise la réponse du serveur avec repli sur le fichier local', () => {
    const fallback = { filename: 'f.png', mediaType: 'image/png', sizeBytes: 5 }
    expect(normalizeAttachment({ id: 'x', width: 10, height: 20 }, fallback)).toEqual({
      id: 'x',
      filename: 'f.png',
      mediaType: 'image/png',
      sizeBytes: 5,
      width: 10,
      height: 20,
    })
    expect(() => normalizeAttachment({}, fallback)).toThrow()
  })

  it('attributs de nœud : largeur / hauteur seulement pour les images', () => {
    const base = { id: 'x', filename: 'a', sizeBytes: 1, width: 3, height: 4 }
    expect(attachmentNodeAttrs({ ...base, mediaType: 'image/webp' })).toMatchObject({ width: 3, height: 4, id: 'x' })
    expect(attachmentNodeAttrs({ ...base, mediaType: 'application/pdf' })).not.toHaveProperty('width')
  })

  it('envoie le fichier en multipart (champ « file ») avec progression', async () => {
    vi.mocked(api.post).mockImplementation(async (_url, _body, config) => {
      config?.onUploadProgress?.({ loaded: 50, total: 100 } as never)
      return { data: { id: 'att-9', filename: 'a.png', mediaType: 'image/png', sizeBytes: 3, width: 1, height: 1 } }
    })
    const onProgress = vi.fn()
    const file = new File(['abc'], 'a.png', { type: 'image/png' })
    const info = await uploadAttachment('doc-1', file, { onProgress })
    expect(info.id).toBe('att-9')
    const [url, body] = vi.mocked(api.post).mock.calls[0]!
    expect(url).toBe('/api/v1/documents/doc-1/attachments')
    expect((body as FormData).get('file')).toBe(file)
    expect(onProgress).toHaveBeenCalledWith(0.5)
  })

  it('met en cache l’URL d’objet par identifiant', async () => {
    const create = vi.fn(() => 'blob:x')
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: create }))
    vi.mocked(api.get).mockResolvedValue({ data: new Blob(['x']) })
    await expect(attachmentObjectUrl('a')).resolves.toBe('blob:x')
    await attachmentObjectUrl('a')
    expect(api.get).toHaveBeenCalledTimes(1)
    vi.unstubAllGlobals()
  })
})
