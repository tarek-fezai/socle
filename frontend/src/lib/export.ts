// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { AxiosInstance } from 'axios'

export type ExportScope = 'document' | 'folder' | 'tag'

function pathFor(scope: ExportScope, id: string): string {
  switch (scope) {
    case 'document':
      return `/api/v1/documents/${id}/export`
    case 'folder':
      return `/api/v1/folders/${id}/export`
    case 'tag':
      return `/api/v1/tags/${id}/export`
  }
}

/** Télécharge le PDF d'export (blob) — même auth que le reste de l'API. */
export async function downloadExport(
  api: AxiosInstance,
  scope: ExportScope,
  id: string,
  fallbackFilename: string,
): Promise<void> {
  const { data, headers } = await api.get<Blob>(pathFor(scope, id), {
    responseType: 'blob',
  })
  const fromHeader = filenameFromContentDisposition(headers['content-disposition'])
  const filename = fromHeader || fallbackFilename
  const url = URL.createObjectURL(data)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

export function filenameFromContentDisposition(header: string | undefined): string | null {
  if (!header) return null
  const utf = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (utf?.[1]) {
    try {
      return decodeURIComponent(utf[1])
    } catch {
      return utf[1]
    }
  }
  const plain = /filename="([^"]+)"/i.exec(header)
  return plain?.[1] ?? null
}
