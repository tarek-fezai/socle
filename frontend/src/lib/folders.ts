// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { AxiosInstance } from 'axios'
import type { DocumentDetail, TagRef } from './documents'

/* ------------------------------------------------------------------ */
/* Types API                                                           */
/* ------------------------------------------------------------------ */

export type ActorRef = {
  kind: string
  id?: string | null
  displayName?: string | null
}

export type OwnerPerson = {
  id: string
  displayName: string
  initials: string
}

export type OwnerGroupRef = {
  id: string
  name: string
  owner?: OwnerPerson | null
}

/** Document direct du dossier (page exclue), tel que renvoyé par GET /folders/{id}. */
export type FolderDocumentCard = {
  id: string
  title: string
  status: string
  docType?: string | null
  updatedAt?: string | null
  /** Nom affiché du dernier modificateur. */
  updatedBy?: string | null
  /** Soumission de l'approbation en cours, sinon null. */
  reviewSince?: string | null
  tags?: TagRef[]
}

export type StatusCounts = {
  validated: number
  inReview: number
  draft: number
}

export type Folder = {
  id: string
  spaceId: string
  parentFolderId: string | null
  name: string
  position: number
  createdBy?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  documentCount?: number
  folderCount?: number
  pageDocumentId?: string | null
  /** `actif` ou `archivé`. */
  status?: string | null
  creator?: ActorRef | null
  lastModifiedBy?: ActorRef | null
  lastModifiedAt?: string | null
  ownerGroup?: OwnerGroupRef | null
  reliabilityAverage?: number | string | null
  statusCounts?: StatusCounts | null
  /** Relation OpenFGA `editor` du dossier. */
  canEdit?: boolean
  /** Même porte que DELETE /folders/{id}. */
  canDelete?: boolean
  /** Langue du document de page, ou null sans page. */
  language?: string | null
  documents?: FolderDocumentCard[]
}

/** Dossier dans l'arborescence (liste plate + `parentFolderId`). */
export type TreeFolder = {
  id: string
  name: string
  parentFolderId: string | null
  position: number
  documentCount: number
  folderCount: number
}

export type TreeDocument = {
  id: string
  title: string
  /** null = racine de l'espace */
  folderId: string | null
  position: number
  status: string
}

/** Arborescence normalisée : listes plates, parents par id. */
export type SpaceTree = {
  spaceId: string
  spaceName: string
  folders: TreeFolder[]
  documents: TreeDocument[]
}

/**
 * Réponse brute de `GET /spaces/{id}/tree`. Le backend peut renvoyer des listes plates
 * ou des nœuds imbriqués (`folders` / `children` + `documents`) : `normalizeSpaceTree`
 * accepte les deux.
 */
export type RawTreeFolder = {
  id: string
  name: string
  parentFolderId?: string | null
  position?: number
  documentCount?: number
  folderCount?: number
  folders?: RawTreeFolder[]
  children?: RawTreeFolder[]
  documents?: RawTreeDocument[]
}

export type RawTreeDocument = {
  id: string
  title: string
  folderId?: string | null
  position?: number
  status?: string
}

export type RawSpaceTree = {
  spaceId: string
  spaceName?: string
  folders?: RawTreeFolder[]
  documents?: RawTreeDocument[]
}

/** Nœud d'arbre prêt pour le rendu React. */
export type FolderTreeNode = {
  folder: TreeFolder
  folders: FolderTreeNode[]
  documents: TreeDocument[]
}

export type SpaceTreeView = {
  folders: FolderTreeNode[]
  documents: TreeDocument[]
}

export type BreadcrumbItem = { label: string; to?: string }

/* ------------------------------------------------------------------ */
/* Client API                                                          */
/* ------------------------------------------------------------------ */

export async function getFolder(api: AxiosInstance, id: string) {
  const { data } = await api.get<Folder>(`/api/v1/folders/${id}`)
  return data
}

/** `null` tant que la page n'a pas été écrite (204). */
export async function getFolderPage(api: AxiosInstance, id: string): Promise<DocumentDetail | null> {
  const res = await api.get<DocumentDetail | ''>(`/api/v1/folders/${id}/page`, {
    validateStatus: (status) => status === 200 || status === 204,
  })
  if (res.status === 204 || res.data == null || res.data === '') return null
  return res.data
}

export async function saveFolderPage(
  api: AxiosInstance,
  id: string,
  body: Record<string, unknown>,
  changeSummary?: string,
) {
  const { data } = await api.put<DocumentDetail>(`/api/v1/folders/${id}/page`, {
    body,
    ...(changeSummary ? { changeSummary } : {}),
  })
  return data
}

export async function createFolder(
  api: AxiosInstance,
  body: { spaceId: string; parentFolderId?: string | null; name: string; position?: number },
) {
  const { data } = await api.post<Folder>('/api/v1/folders', {
    spaceId: body.spaceId,
    parentFolderId: body.parentFolderId ?? null,
    name: body.name,
    ...(body.position != null ? { position: body.position } : {}),
  })
  return data
}

export async function updateFolder(
  api: AxiosInstance,
  id: string,
  body: { name?: string; position?: number },
) {
  const { data } = await api.patch<Folder>(`/api/v1/folders/${id}`, body)
  return data
}

/** Soft-delete en cascade (sous-dossiers + documents). */
export async function deleteFolder(api: AxiosInstance, id: string) {
  await api.delete(`/api/v1/folders/${id}`)
}

export async function moveFolder(
  api: AxiosInstance,
  id: string,
  body: { parentFolderId: string | null; position?: number },
) {
  const { data } = await api.post<Folder>(`/api/v1/folders/${id}/move`, {
    parentFolderId: body.parentFolderId,
    ...(body.position != null ? { position: body.position } : {}),
  })
  return data
}

export async function getSpaceTree(api: AxiosInstance, spaceId: string, depth?: number) {
  const { data } = await api.get<RawSpaceTree>(`/api/v1/spaces/${spaceId}/tree`, {
    params: depth != null ? { depth } : undefined,
  })
  return normalizeSpaceTree(data, spaceId)
}

export const spaceTreeKey = (spaceId: string) => ['space-tree', spaceId] as const

/* ------------------------------------------------------------------ */
/* Helpers purs (arbre)                                                */
/* ------------------------------------------------------------------ */

export function folderHref(id: string) {
  return `/folders/${id}`
}

/** Page de lecture du document (vue par défaut). */
export function documentHref(id: string) {
  return `/docs/${id}`
}

/** Éditeur du document. */
export function documentEditHref(id: string) {
  return `/docs/${id}/edit`
}

export function spaceBrowseHref(spaceId: string) {
  return `/spaces/${spaceId}/tree`
}

function byPositionThenLabel<T>(label: (t: T) => string, position: (t: T) => number) {
  return (a: T, b: T) =>
    position(a) - position(b) || label(a).localeCompare(label(b), 'fr', { sensitivity: 'base' })
}

export const compareFolders = byPositionThenLabel<TreeFolder>(
  (f) => f.name,
  (f) => f.position,
)
export const compareDocuments = byPositionThenLabel<TreeDocument>(
  (d) => d.title,
  (d) => d.position,
)

/**
 * Aplati une réponse plate ou imbriquée en listes dédoublonnées (par id).
 * Tolère une réponse partielle/inattendue (jamais d'exception).
 */
export function normalizeSpaceTree(raw: RawSpaceTree | null | undefined, spaceId = ''): SpaceTree {
  const folders = new Map<string, TreeFolder>()
  const documents = new Map<string, TreeDocument>()

  const addDocument = (d: RawTreeDocument, parentId: string | null | undefined) => {
    if (!d || typeof d.id !== 'string' || documents.has(d.id)) return
    documents.set(d.id, {
      id: d.id,
      title: d.title ?? '',
      folderId: d.folderId !== undefined ? d.folderId : (parentId ?? null),
      position: d.position ?? 0,
      status: d.status ?? '',
    })
  }

  const addFolder = (f: RawTreeFolder, parentId: string | null) => {
    if (!f || typeof f.id !== 'string') return
    if (!folders.has(f.id)) {
      folders.set(f.id, {
        id: f.id,
        name: f.name ?? '',
        parentFolderId: f.parentFolderId !== undefined ? f.parentFolderId : parentId,
        position: f.position ?? 0,
        documentCount: f.documentCount ?? 0,
        folderCount: f.folderCount ?? 0,
      })
    }
    for (const child of f.folders ?? f.children ?? []) addFolder(child, f.id)
    for (const d of f.documents ?? []) addDocument(d, f.id)
  }

  for (const f of Array.isArray(raw?.folders) ? raw.folders : []) addFolder(f, null)
  for (const d of Array.isArray(raw?.documents) ? raw.documents : []) addDocument(d, null)

  return {
    spaceId: raw?.spaceId ?? spaceId,
    spaceName: raw?.spaceName ?? '',
    folders: [...folders.values()],
    documents: [...documents.values()],
  }
}

/** Construit l'arbre imbriqué (triée par position puis nom). Les orphelins remontent à la racine. */
export function buildSpaceTree(tree: Pick<SpaceTree, 'folders' | 'documents'>): SpaceTreeView {
  const ids = new Set(tree.folders.map((f) => f.id))
  const parentOf = (id: string | null) => (id && ids.has(id) ? id : null)

  const foldersByParent = new Map<string | null, TreeFolder[]>()
  for (const f of tree.folders) {
    const key = parentOf(f.parentFolderId)
    foldersByParent.set(key, [...(foldersByParent.get(key) ?? []), f])
  }
  const docsByFolder = new Map<string | null, TreeDocument[]>()
  for (const d of tree.documents) {
    const key = parentOf(d.folderId)
    docsByFolder.set(key, [...(docsByFolder.get(key) ?? []), d])
  }

  const seen = new Set<string>()
  const build = (parent: string | null): FolderTreeNode[] =>
    (foldersByParent.get(parent) ?? [])
      .slice()
      .sort(compareFolders)
      .filter((f) => !seen.has(f.id) && (seen.add(f.id), true))
      .map((folder) => ({
        folder,
        folders: build(folder.id),
        documents: (docsByFolder.get(folder.id) ?? []).slice().sort(compareDocuments),
      }))

  return {
    folders: build(null),
    documents: (docsByFolder.get(null) ?? []).slice().sort(compareDocuments),
  }
}

/** Enfants directs d'un dossier (ou de la racine si `folderId` est null), triés. */
export function childrenOf(
  tree: Pick<SpaceTree, 'folders' | 'documents'>,
  folderId: string | null,
): { folders: TreeFolder[]; documents: TreeDocument[] } {
  return {
    folders: tree.folders
      .filter((f) => (f.parentFolderId ?? null) === folderId)
      .sort(compareFolders),
    documents: tree.documents
      .filter((d) => (d.folderId ?? null) === folderId)
      .sort(compareDocuments),
  }
}

/** Chaîne d'ancêtres, de la racine jusqu'au dossier inclus (vide si inconnu). */
export function folderPath(
  folders: TreeFolder[],
  folderId: string | null | undefined,
): TreeFolder[] {
  const byId = new Map(folders.map((f) => [f.id, f]))
  const path: TreeFolder[] = []
  const seen = new Set<string>()
  let cur = folderId ? byId.get(folderId) : undefined
  while (cur && !seen.has(cur.id)) {
    seen.add(cur.id)
    path.unshift(cur)
    cur = cur.parentFolderId ? byId.get(cur.parentFolderId) : undefined
  }
  return path
}

/** Ids des dossiers à déplier pour montrer `folderId` (ancêtres + lui-même). */
export function ancestorFolderIds(folders: TreeFolder[], folderId: string | null | undefined) {
  return folderPath(folders, folderId).map((f) => f.id)
}

/**
 * Retrait de la sidebar. La maquette pose 10 px à gauche d'un dossier racine
 * et 22 px pour un document dans ce dossier. Chaque niveau de plus, absent
 * de la maquette, ajoute 12 px. `depth` 0 est la racine de l'espace.
 */
export function shellFolderIndent(depth: number): number {
  return 10 + Math.max(0, depth) * 12
}

export function shellDocumentIndent(depth: number): number {
  if (depth <= 0) return 10
  return 22 + (depth - 1) * 12
}

/** Ids de tous les descendants d'un dossier (sans lui-même). */
export function descendantFolderIds(folders: TreeFolder[], folderId: string): Set<string> {
  const out = new Set<string>()
  const stack = [folderId]
  while (stack.length) {
    const id = stack.pop() as string
    for (const f of folders) {
      if (f.parentFolderId === id && !out.has(f.id)) {
        out.add(f.id)
        stack.push(f.id)
      }
    }
  }
  return out
}

export type FolderOption = { id: string | null; name: string; depth: number }

/** Liste plate indentée (parcours en profondeur) pour sélecteurs d'emplacement. */
export function flattenFolderOptions(
  tree: Pick<SpaceTree, 'folders' | 'documents'>,
  excludeIds?: Set<string>,
): FolderOption[] {
  const out: FolderOption[] = []
  const walk = (nodes: FolderTreeNode[], depth: number) => {
    for (const n of nodes) {
      if (excludeIds?.has(n.folder.id)) continue
      out.push({ id: n.folder.id, name: n.folder.name, depth })
      walk(n.folders, depth + 1)
    }
  }
  walk(buildSpaceTree(tree).folders, 1)
  return out
}

/** Fil d'Ariane : Accueil → Espaces → espace → dossiers → (feuille). */
export function buildBreadcrumb(opts: {
  spaceId: string
  spaceName: string
  folders: TreeFolder[]
  /** Dossier courant (ou dossier du document). */
  folderId?: string | null
  /** Page courante (titre de document, etc.), non cliquable. */
  leaf?: string
  /** Rend le dernier dossier non cliquable (page dossier). */
  currentIsFolder?: boolean
}): BreadcrumbItem[] {
  const items: BreadcrumbItem[] = [
    { label: 'Accueil', to: '/' },
    { label: 'Espaces', to: '/spaces' },
  ]
  const path = folderPath(opts.folders, opts.folderId)
  const spaceItem: BreadcrumbItem = {
    label: opts.spaceName || 'Espace',
    to: spaceBrowseHref(opts.spaceId),
  }
  if (path.length === 0 && !opts.leaf) {
    items.push({ label: spaceItem.label })
    return items
  }
  items.push(spaceItem)
  path.forEach((f, i) => {
    const last = i === path.length - 1
    items.push(
      last && opts.currentIsFolder && !opts.leaf
        ? { label: f.name }
        : { label: f.name, to: folderHref(f.id) },
    )
  })
  if (opts.leaf) items.push({ label: opts.leaf })
  return items
}

/** Libellé de statut court pour l'arborescence. */
export function statusLabel(status: string) {
  switch (status) {
    case 'brouillon':
      return 'brouillon'
    case 'en_revue':
      return 'en revue'
    default:
      return ''
  }
}
