// SPDX-License-Identifier: AGPL-3.0-or-later
export type DocumentSummary = {
  id: string
  title: string
  status: string
  updatedAt: string
  /** null hors `valide` — jamais traiter comme 0 */
  reliabilityScore?: number | null
  stale?: boolean
  contentModifiedAt?: string | null
}

/** Étiquette rattachée à un document (triée par nom côté serveur). */
export type TagRef = {
  id: string
  name: string
  color?: string | null
}

export type DocumentDetail = {
  id: string
  spaceId: string
  /** null / absent = racine de l'espace */
  folderId?: string | null
  position?: number
  title: string
  docType?: string | null
  body: Record<string, unknown>
  status: string
  currentVersionNo?: number
  createdAt: string
  updatedAt: string
  /** null hors statut `valide` — afficher « Non évalué », jamais 0% fictif */
  reliabilityScore?: number | null
  reliabilityComputedAt?: string | null
  stale?: boolean
  contentModifiedAt?: string | null
  stalenessThresholdDays?: number
  /** organisation | space | restricted */
  visibility?: 'organisation' | 'space' | 'restricted' | string
  /** Créateur du document (null / absent = inconnu ou migration) */
  createdBy?: string | null
  /** Auteur du contenu courant (null / absent = inconnu ou migration) */
  updatedBy?: string | null
  /** Étiquettes, triées par nom */
  tags?: TagRef[]
}

export type DocumentVisibility = 'organisation' | 'space' | 'restricted'

/** Nœud TipTap (body brut ou résolu, y compris blocs transclusion). */
export type TipTapNode = {
  type?: string
  text?: string
  attrs?: Record<string, unknown>
  content?: TipTapNode[]
  [key: string]: unknown
}

export type VersionSummary = {
  versionNo: number
  authorId: string | null
  archivedBy?: string | null
  changeSummary: string | null
  createdAt: string
}

export type VersionPage = {
  items: VersionSummary[]
  offset: number
  limit: number
  total: number
}

export type VersionDiff = {
  documentId: string
  fromVersion: number
  toVersion: number
  changes: Array<{
    path: string
    op: string
    before: unknown
    after: unknown
  }>
}

export const emptyDocBody: Record<string, unknown> = {
  type: 'doc',
  content: [{ type: 'paragraph' }],
}

export type DocumentListPage = {
  results: DocumentSummary[]
  total: number
  totalIsEstimate: boolean
  warning?: string | null
}

export async function listDocuments(
  api: {
    get: <T>(url: string, config?: { params?: Record<string, unknown> }) => Promise<{ data: T }>
  },
  params?: { limit?: number; offset?: number },
) {
  const { data } = await api.get<DocumentListPage>('/api/v1/documents', {
    params: {
      limit: params?.limit ?? 20,
      offset: params?.offset ?? 0,
    },
  })
  return data
}

export async function getDocument(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  id: string,
) {
  const { data } = await api.get<DocumentDetail>(`/api/v1/documents/${id}`)
  return data
}

/** Lecture composite : blocs transclusion résolus (OpenFGA à chaque requête). */
export async function getResolvedDocument(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  id: string,
) {
  const { data } = await api.get<DocumentDetail>(`/api/v1/documents/${id}/resolved`)
  return data
}

export async function createDocument(
  api: { post: <T>(url: string, body: unknown) => Promise<{ data: T }> },
  title: string,
  spaceId: string,
  body: Record<string, unknown> | null = emptyDocBody,
  folderId?: string | null,
  opts?: { templateId?: string | null; docType?: string | null; visibility?: DocumentVisibility },
) {
  const templateId = opts?.templateId ?? null
  // Avec un modèle, le corps vient du modèle : on n'envoie pas le corps vide par défaut.
  const sendBody = body !== null && !(templateId && body === emptyDocBody)
  const { data } = await api.post<DocumentDetail>('/api/v1/documents', {
    title,
    ...(sendBody ? { body } : {}),
    spaceId,
    ...(folderId ? { folderId } : {}),
    ...(templateId ? { templateId } : {}),
    ...(opts?.docType ? { docType: opts.docType } : {}),
    ...(opts?.visibility ? { visibility: opts.visibility } : {}),
  })
  return data
}

/** Déplace un document vers un dossier (`folderId: null` = racine de l'espace). */
export async function moveDocument(
  api: { post: <T>(url: string, body: unknown) => Promise<{ data: T }> },
  id: string,
  body: { folderId: string | null; position?: number },
) {
  const { data } = await api.post<DocumentDetail>(`/api/v1/documents/${id}/move`, {
    folderId: body.folderId,
    ...(body.position != null ? { position: body.position } : {}),
  })
  return data
}

export async function updateDocument(
  api: { put: <T>(url: string, body: unknown) => Promise<{ data: T }> },
  id: string,
  title: string,
  body: Record<string, unknown>,
  docType?: string | null,
  expectedVersionNo?: number | null,
) {
  const { data } = await api.put<DocumentDetail>(`/api/v1/documents/${id}`, {
    title,
    body,
    docType: docType ?? null,
    expectedVersionNo: expectedVersionNo ?? null,
  })
  return data
}

export async function updateDocumentVisibility(
  api: { put: <T>(url: string, body: unknown) => Promise<{ data: T }> },
  id: string,
  visibility: DocumentVisibility,
) {
  const { data } = await api.put<DocumentDetail>(`/api/v1/documents/${id}/visibility`, {
    visibility,
  })
  return data
}

export async function listVersions(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  documentId: string,
) {
  const { data } = await api.get<VersionPage>(`/api/v1/documents/${documentId}/versions`)
  return data
}

export async function fetchVersionDiff(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  documentId: string,
  fromVersion: number,
  toVersion: number,
) {
  const { data } = await api.get<VersionDiff>(
    `/api/v1/documents/${documentId}/versions/${fromVersion}/diff/${toVersion}`,
  )
  return data
}

export async function restoreVersion(
  api: { post: <T>(url: string, body?: unknown) => Promise<{ data: T }> },
  documentId: string,
  versionNo: number,
  expectedVersionNo?: number | null,
) {
  const q =
    expectedVersionNo != null ? `?expectedVersionNo=${encodeURIComponent(String(expectedVersionNo))}` : ''
  const { data } = await api.post<DocumentDetail>(
    `/api/v1/documents/${documentId}/versions/${versionNo}/restore${q}`,
  )
  return data
}

const VIEW_SESSION_PREFIX = 'socle.docView.'

/** Enregistre une vue document (une fois par onglet / session navigateur). */
export async function recordDocumentView(
  api: { post: <T>(url: string, body?: unknown) => Promise<{ data: T }> },
  documentId: string,
) {
  const key = `${VIEW_SESSION_PREFIX}${documentId}`
  try {
    if (typeof sessionStorage !== 'undefined' && sessionStorage.getItem(key)) {
      return
    }
  } catch {
    // ignore quota / private mode
  }
  await api.post(`/api/v1/documents/${documentId}/view`)
  try {
    sessionStorage.setItem(key, '1')
  } catch {
    // ignore
  }
}
