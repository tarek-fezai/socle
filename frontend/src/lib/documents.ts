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
  /** Étiquette de gouvernance : rattachement / retrait réservés aux propriétaires. */
  governed?: boolean
}

/** Personne résolue depuis `users` (auteur, propriétaire). */
export type PersonRef = {
  id: string
  displayName: string
  initials: string
}

/** Droits calculés serveur (OpenFGA BatchCheck + statut). */
export type DocumentPermissions = {
  canEdit: boolean
  canPublish: boolean
  canManageAccess: boolean
  canComment: boolean
  canManageAttestations: boolean
}

export const emptyPermissions: DocumentPermissions = {
  canEdit: false,
  canPublish: false,
  canManageAccess: false,
  canComment: false,
  canManageAttestations: false,
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
  /** Créateur — null = « Système (migration) » */
  createdBy?: PersonRef | null
  /** Auteur du contenu courant — null = « Système (migration) » */
  updatedBy?: PersonRef | null
  /** Propriétaire d'espace (utilisateur), si résolu */
  owner?: PersonRef | null
  /** Étiquettes, triées par nom */
  tags?: TagRef[]
  permissions?: DocumentPermissions
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
  /** Nom affiché de l'auteur (null / absent si auteur système ou non résolu). */
  authorDisplayName?: string | null
  authorInitials?: string | null
  /** Lignes ajoutées / retirées par rapport à la version précédente (absent = inconnu). */
  linesAdded?: number | null
  linesRemoved?: number | null
  /** Renseigné par le serveur : c'est la version courante du document. */
  current?: boolean
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

/** Fragment intra-ligne (mots) : `eq` inchangé, `add` / `del` mis en évidence. */
export type CompareSpan = { kind: 'eq' | 'add' | 'del'; text: string }

export type CompareLine = {
  kind: 'context' | 'add' | 'del'
  oldNo: number | null
  newNo: number | null
  text: string
  spans?: CompareSpan[] | null
}

export type CompareHunk = {
  /** Titre le plus proche au-dessus du bloc (peut être vide). */
  header: string
  /**
   * Bloc modifié : lignes (contexte compris). Bloc replié par le serveur : liste vide et
   * `collapsedUnchanged` = nombre de lignes inchangées omises.
   */
  lines: CompareLine[]
  /** > 0 : lignes inchangées repliées (bloc sans `lines`, ou contexte à replier dans le bloc). */
  collapsedUnchanged: number
}

/** Comparaison ligne à ligne de deux versions (`…/compare/{b}?mode=lines`). */
export type VersionCompare = {
  documentId: string
  fromVersion: number
  toVersion: number
  added: number
  removed: number
  hunks: CompareHunk[]
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

export const VERSION_PAGE_SIZE = 50

export async function listVersions(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  documentId: string,
  page: { offset?: number; limit?: number } = {},
) {
  const offset = page.offset ?? 0
  const limit = page.limit ?? VERSION_PAGE_SIZE
  const { data } = await api.get<VersionPage>(
    `/api/v1/documents/${documentId}/versions?offset=${offset}&limit=${limit}`,
  )
  return data
}

/** Charge toutes les pages (sélecteurs de comparaison). */
export async function listAllVersions(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  documentId: string,
  pageSize = 100,
): Promise<VersionPage> {
  const items: VersionSummary[] = []
  let offset = 0
  let total = 0
  // Garde-fou : un total incohérent ne doit pas boucler indéfiniment.
  for (let guard = 0; guard < 100; guard += 1) {
    const page = await listVersions(api, documentId, { offset, limit: pageSize })
    items.push(...page.items)
    total = page.total
    offset += page.items.length
    if (page.items.length === 0 || offset >= total) break
  }
  return { items, offset: 0, limit: pageSize, total }
}

/** Comparaison ligne à ligne (`from` → `to`). Le diff structurel `fetchVersionDiff` reste utilisé par les approbations. */
export async function fetchVersionCompare(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  documentId: string,
  fromVersion: number,
  toVersion: number,
  options: { fullContext?: boolean } = {},
) {
  // `context=all` : demande les lignes inchangées des blocs repliés (dépliage « N lignes inchangées »).
  const ctx = options.fullContext ? '&context=all' : ''
  const { data } = await api.get<VersionCompare>(
    `/api/v1/documents/${documentId}/versions/${fromVersion}/compare/${toVersion}?mode=lines${ctx}`,
  )
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
