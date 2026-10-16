// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { ReactNode } from 'react'

const getMock = vi.fn()
vi.mock('../../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
  },
}))

vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ me: { displayName: 'Camille', roles: [] }, logout: vi.fn() }),
}))

import { ShellSidebar } from './ShellSidebar'

const SPACE = 's1'

const rawTree = {
  spaceId: SPACE,
  spaceName: 'Identité',
  folders: [
    { id: 'l1', name: 'Niveau 1', parentFolderId: null, position: 0, documentCount: 1, folderCount: 1 },
    { id: 'l2', name: 'Niveau 2', parentFolderId: 'l1', position: 0, documentCount: 0, folderCount: 1 },
    { id: 'l3', name: 'Niveau 3', parentFolderId: 'l2', position: 0, documentCount: 1, folderCount: 0 },
    { id: 'autre', name: 'Autre', parentFolderId: null, position: 1, documentCount: 1, folderCount: 0 },
  ],
  documents: [
    { id: 'root', title: 'Politique', folderId: null, position: 0, status: 'valide' },
    { id: 'rootDraft', title: 'Registre', folderId: null, position: 1, status: 'brouillon' },
    { id: 'in1', title: 'Dans le dossier', folderId: 'l1', position: 0, status: 'valide' },
    { id: 'draft', title: 'Brouillon interne', folderId: 'autre', position: 0, status: 'brouillon' },
    { id: 'deep', title: 'Feuille', folderId: 'l3', position: 0, status: 'valide' },
  ],
}

function renderSidebar(path = '/') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  function Wrap({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>{children}</MemoryRouter>
      </QueryClientProvider>
    )
  }
  return render(<ShellSidebar onOpenSearch={() => undefined} />, { wrapper: Wrap })
}

afterEach(() => {
  vi.restoreAllMocks()
})

beforeEach(() => {
  localStorage.clear()
  getMock.mockReset()
  getMock.mockImplementation((url: string) => {
    if (String(url).includes('/tree')) return Promise.resolve({ data: rawTree })
    if (String(url).endsWith('/spaces')) {
      return Promise.resolve({
        data: [
          {
            id: SPACE,
            name: 'Identité',
            color: null,
            createdAt: null,
            canManage: false,
            isOwner: false,
            isResponsible: false,
          },
        ],
      })
    }
    return Promise.resolve({ data: {} })
  })
})

describe('ShellSidebar', () => {
  it('affiche les documents racine puis le dossier et ses documents', async () => {
    renderSidebar()
    const root = await screen.findByRole('link', { name: 'Politique' })
    const draft = screen.getByRole('link', { name: /Registre/ })
    const folder = screen.getByRole('link', { name: 'Niveau 1' })
    expect(draft.textContent).toContain('brouillon')
    expect(root.compareDocumentPosition(folder) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(draft.compareDocumentPosition(folder) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Dans le dossier' })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Déplier Niveau 1' }))
    const doc = screen.getByRole('link', { name: 'Dans le dossier' })
    const sub = screen.getByRole('link', { name: 'Niveau 2' })
    expect(doc.compareDocumentPosition(sub) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(doc.style.paddingLeft).toBe('22px')
  })

  it('ouvre les trois niveaux parents d’un document et laisse les autres dossiers fermés', async () => {
    renderSidebar('/docs/deep')
    expect(
      (await screen.findByRole('button', { name: 'Replier Niveau 1' })).getAttribute('aria-expanded'),
    ).toBe('true')
    expect(screen.getByRole('button', { name: 'Replier Niveau 2' }).getAttribute('aria-expanded')).toBe(
      'true',
    )
    expect(screen.getByRole('button', { name: 'Replier Niveau 3' }).getAttribute('aria-expanded')).toBe(
      'true',
    )
    const leaf = screen.getByRole('link', { name: 'Feuille' })
    expect(leaf.style.paddingLeft).toBe('46px')
    expect(screen.getByRole('button', { name: 'Déplier Autre' }).getAttribute('aria-expanded')).toBe(
      'false',
    )
  })

  it('surligne le dossier actif et garde le repli choisi sur un autre dossier', async () => {
    renderSidebar('/folders/l2')
    const current = await screen.findByRole('link', { name: 'Niveau 2' })
    expect(current.getAttribute('aria-current')).toBe('page')
    expect(current.parentElement?.className).toContain('active')
    expect(screen.getByRole('button', { name: 'Replier Niveau 1' }).getAttribute('aria-expanded')).toBe(
      'true',
    )

    fireEvent.click(screen.getByRole('button', { name: 'Déplier Autre' }))
    expect(screen.getByRole('link', { name: /Brouillon interne/ }).textContent).toContain('brouillon')
    fireEvent.click(screen.getByRole('button', { name: 'Replier Autre' }))
    expect(screen.getByRole('button', { name: 'Déplier Autre' }).getAttribute('aria-expanded')).toBe('false')
    expect(screen.getByRole('button', { name: 'Replier Niveau 1' }).getAttribute('aria-expanded')).toBe(
      'true',
    )
    expect(screen.queryByRole('link', { name: /Brouillon interne/ })).toBeNull()
  })

  it('déplie au clavier avec aria-expanded', async () => {
    renderSidebar()
    const toggle = await screen.findByRole('button', { name: 'Déplier Autre' })
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    toggle.focus()
    fireEvent.keyDown(toggle, { key: 'ArrowRight' })
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Replier Autre' }).getAttribute('aria-expanded')).toBe(
        'true',
      )
    })
    expect(screen.getByRole('link', { name: /Brouillon interne/ })).toBeTruthy()
  })

  it('bascule le menu réduit et agrandi, et mémorise le choix', async () => {
    renderSidebar()
    const toggle = await screen.findByRole('button', { name: 'Réduire ou agrandir le menu' })
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(toggle.querySelector('polyline')?.getAttribute('points')).toBe('15 18 9 12 15 6')
    expect(document.querySelector('.shell-sidebar')?.className).not.toContain('is-collapsed')

    fireEvent.click(toggle)
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(toggle.querySelector('polyline')?.getAttribute('points')).toBe('9 18 15 12 9 6')
    expect(document.querySelector('.shell-sidebar')?.className).toContain('is-collapsed')
    expect(localStorage.getItem('socle.prefs.sidebarCollapsed')).toBe('1')
    expect((await screen.findByTitle('Politique')).className).not.toContain('active')

    fireEvent.click(toggle)
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(localStorage.getItem('socle.prefs.sidebarCollapsed')).toBe('0')
    expect(screen.getByRole('link', { name: 'Politique' })).toBeTruthy()
  })

  it('rouvre réduit si la préférence est déjà enregistrée, et marque l’élément actif', async () => {
    localStorage.setItem('socle.prefs.sidebarCollapsed', '1')
    renderSidebar('/docs/root')
    const toggle = await screen.findByRole('button', { name: 'Réduire ou agrandir le menu' })
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    const active = await screen.findByTitle('Politique')
    expect(active.className).toContain('active')
    expect(active.getAttribute('title')).toBe('Politique')
    expect(screen.getByTitle('Niveau 1').className).not.toContain('active')
    expect(screen.queryByRole('link', { name: 'Favoris' })).toBeNull()
  })

  it('réduit au raccourci Ctrl+\\ et ignore la saisie', async () => {
    renderSidebar()
    await screen.findByRole('link', { name: 'Politique' })
    fireEvent.keyDown(window, { key: '\\', ctrlKey: true })
    expect(document.querySelector('.shell-sidebar')?.className).toContain('is-collapsed')

    fireEvent.keyDown(window, { key: '\\', ctrlKey: true })
    expect(document.querySelector('.shell-sidebar')?.className).not.toContain('is-collapsed')

    const input = document.createElement('input')
    document.body.appendChild(input)
    fireEvent.keyDown(input, { key: '\\', ctrlKey: true })
    expect(document.querySelector('.shell-sidebar')?.className).not.toContain('is-collapsed')
    input.remove()
  })

  it('reste agrandi si le stockage est illisible, et bascule quand même s’il refuse l’écriture', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('indisponible')
    })
    renderSidebar()
    const toggle = await screen.findByRole('button', { name: 'Réduire ou agrandir le menu' })
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota')
    })
    fireEvent.click(toggle)
    expect(document.querySelector('.shell-sidebar')?.className).toContain('is-collapsed')
  })
})
