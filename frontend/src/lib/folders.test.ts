// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it } from 'vitest'
import {
  ancestorFolderIds,
  buildBreadcrumb,
  buildSpaceTree,
  childrenOf,
  descendantFolderIds,
  flattenFolderOptions,
  folderPath,
  normalizeSpaceTree,
  type SpaceTree,
} from './folders'

const tree: SpaceTree = {
  spaceId: 's1',
  spaceName: 'Identité & accès',
  folders: [
    {
      id: 'a',
      name: 'Référence',
      parentFolderId: null,
      position: 1,
      documentCount: 0,
      folderCount: 1,
    },
    {
      id: 'b',
      name: 'Procédures',
      parentFolderId: null,
      position: 0,
      documentCount: 1,
      folderCount: 0,
    },
    { id: 'a1', name: 'Rôles', parentFolderId: 'a', position: 0, documentCount: 1, folderCount: 1 },
    {
      id: 'a2',
      name: 'Annexes',
      parentFolderId: 'a1',
      position: 0,
      documentCount: 0,
      folderCount: 0,
    },
  ],
  documents: [
    { id: 'd1', title: 'Rôles & habilitations', folderId: 'a1', position: 0, status: 'valide' },
    { id: 'd2', title: 'Politique', folderId: null, position: 0, status: 'valide' },
    { id: 'd3', title: 'Provisioning', folderId: 'b', position: 1, status: 'brouillon' },
  ],
}

describe('normalizeSpaceTree', () => {
  it('garde une réponse plate telle quelle', () => {
    const out = normalizeSpaceTree(tree)
    expect(out.folders).toHaveLength(4)
    expect(out.documents).toHaveLength(3)
    expect(out.spaceName).toBe('Identité & accès')
  })

  it('aplatit les nœuds imbriqués et déduit les parents', () => {
    const out = normalizeSpaceTree({
      spaceId: 's1',
      spaceName: 'E',
      folders: [
        {
          id: 'a',
          name: 'A',
          documentCount: 1,
          folderCount: 1,
          documents: [{ id: 'dx', title: 'X', status: 'valide' }],
          folders: [{ id: 'a1', name: 'A1', documents: [], folders: [] }],
        },
      ],
      documents: [{ id: 'root', title: 'R', folderId: null, position: 0, status: 'brouillon' }],
    })
    expect(out.folders.find((f) => f.id === 'a1')?.parentFolderId).toBe('a')
    expect(out.folders.find((f) => f.id === 'a')?.parentFolderId).toBeNull()
    expect(out.documents.find((d) => d.id === 'dx')?.folderId).toBe('a')
    expect(out.documents.find((d) => d.id === 'root')?.folderId).toBeNull()
  })

  it('dédoublonne et tolère une réponse inattendue', () => {
    const dup = normalizeSpaceTree({
      spaceId: 's1',
      folders: [{ id: 'a', name: 'A', documents: [{ id: 'd', title: 'D' }] }],
      documents: [{ id: 'd', title: 'D', folderId: 'a' }],
    })
    expect(dup.documents).toHaveLength(1)
    expect(normalizeSpaceTree({ id: 'doc' } as never, 's9')).toEqual({
      spaceId: 's9',
      spaceName: '',
      folders: [],
      documents: [],
    })
  })
})

describe('buildSpaceTree / childrenOf', () => {
  it('imbrique et trie par position puis nom', () => {
    const view = buildSpaceTree(tree)
    expect(view.folders.map((n) => n.folder.id)).toEqual(['b', 'a'])
    expect(view.folders[1].folders[0].folder.id).toBe('a1')
    expect(view.folders[1].folders[0].folders[0].folder.id).toBe('a2')
    expect(view.folders[0].documents.map((d) => d.id)).toEqual(['d3'])
    expect(view.documents.map((d) => d.id)).toEqual(['d2'])
  })

  it('remonte les orphelins à la racine', () => {
    const view = buildSpaceTree({
      folders: [
        {
          id: 'x',
          name: 'X',
          parentFolderId: 'missing',
          position: 0,
          documentCount: 0,
          folderCount: 0,
        },
      ],
      documents: [{ id: 'd', title: 'D', folderId: 'missing', position: 0, status: 'valide' }],
    })
    expect(view.folders.map((n) => n.folder.id)).toEqual(['x'])
    expect(view.documents.map((d) => d.id)).toEqual(['d'])
  })

  it('childrenOf renvoie les enfants directs', () => {
    expect(childrenOf(tree, null).folders.map((f) => f.id)).toEqual(['b', 'a'])
    const a = childrenOf(tree, 'a')
    expect(a.folders.map((f) => f.id)).toEqual(['a1'])
    expect(a.documents).toEqual([])
    expect(childrenOf(tree, 'a1').documents.map((d) => d.id)).toEqual(['d1'])
  })
})

describe('chemins et descendants', () => {
  it('folderPath va de la racine au dossier', () => {
    expect(folderPath(tree.folders, 'a2').map((f) => f.id)).toEqual(['a', 'a1', 'a2'])
    expect(folderPath(tree.folders, null)).toEqual([])
    expect(folderPath(tree.folders, 'inconnu')).toEqual([])
  })

  it('folderPath résiste aux cycles', () => {
    const cyc = [
      { id: 'x', name: 'X', parentFolderId: 'y', position: 0, documentCount: 0, folderCount: 0 },
      { id: 'y', name: 'Y', parentFolderId: 'x', position: 0, documentCount: 0, folderCount: 0 },
    ]
    expect(folderPath(cyc, 'x').length).toBe(2)
  })

  it('ancestorFolderIds / descendantFolderIds', () => {
    expect(ancestorFolderIds(tree.folders, 'a1')).toEqual(['a', 'a1'])
    expect([...descendantFolderIds(tree.folders, 'a')].sort()).toEqual(['a1', 'a2'])
    expect(descendantFolderIds(tree.folders, 'b').size).toBe(0)
  })

  it('flattenFolderOptions indente et exclut des branches', () => {
    expect(flattenFolderOptions(tree).map((o) => [o.id, o.depth])).toEqual([
      ['b', 1],
      ['a', 1],
      ['a1', 2],
      ['a2', 3],
    ])
    expect(flattenFolderOptions(tree, new Set(['a1', 'a2'])).map((o) => o.id)).toEqual(['b', 'a'])
  })
})

describe('buildBreadcrumb', () => {
  const base = { spaceId: 's1', spaceName: 'Identité & accès', folders: tree.folders }

  it('racine : le nom de l’espace est le dernier élément', () => {
    expect(buildBreadcrumb(base)).toEqual([
      { label: 'Accueil', to: '/' },
      { label: 'Espaces', to: '/spaces' },
      { label: 'Identité & accès' },
    ])
  })

  it('page dossier : dossier courant non cliquable', () => {
    const items = buildBreadcrumb({ ...base, folderId: 'a1', currentIsFolder: true })
    expect(items.map((i) => i.label)).toEqual([
      'Accueil',
      'Espaces',
      'Identité & accès',
      'Référence',
      'Rôles',
    ])
    expect(items[2].to).toBe('/spaces/s1/tree')
    expect(items[3].to).toBe('/folders/a')
    expect(items[4].to).toBeUndefined()
  })

  it('document : dossiers cliquables puis titre', () => {
    const items = buildBreadcrumb({ ...base, folderId: 'a1', leaf: 'Rôles & habilitations' })
    expect(items.at(-2)).toEqual({ label: 'Rôles', to: '/folders/a1' })
    expect(items.at(-1)).toEqual({ label: 'Rôles & habilitations' })
  })

  it('document à la racine : espace cliquable puis titre', () => {
    const items = buildBreadcrumb({ ...base, folderId: null, leaf: 'Politique' })
    expect(items.map((i) => i.label)).toEqual([
      'Accueil',
      'Espaces',
      'Identité & accès',
      'Politique',
    ])
    expect(items[2].to).toBe('/spaces/s1/tree')
  })
})
