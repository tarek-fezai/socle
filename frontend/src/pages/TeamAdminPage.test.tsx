// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

vi.mock('../lib/api', () => ({ api: {} }))

const listInvitations = vi.fn()
const resendInvitation = vi.fn()
const revokeInvitation = vi.fn()

const getInvitationCapability = vi.fn()

vi.mock('../lib/invitations', () => ({
  invitationsKey: () => ['admin', 'invitations'],
  listInvitations: (...args: unknown[]) => listInvitations(...args),
  resendInvitation: (...args: unknown[]) => resendInvitation(...args),
  revokeInvitation: (...args: unknown[]) => revokeInvitation(...args),
  createInvitation: vi.fn(),
  getInvitationCapability: (...args: unknown[]) => getInvitationCapability(...args),
  invitationNotConfiguredMessage: (missing: string[]) =>
    `L'envoi d'invitations n'est pas configuré (${missing.join(', ')}).`,
}))

const listAdminUsers = vi.fn()

vi.mock('../lib/directory', () => ({
  listAdminUsers: (...args: unknown[]) => listAdminUsers(...args),
  disableUser: vi.fn(),
  enableUser: vi.fn(),
}))

vi.mock('../lib/groups', () => ({
  listGroups: () => Promise.resolve([]),
  addGroupMember: vi.fn(),
  createGroup: vi.fn(),
  removeGroupMember: vi.fn(),
  updateGroupMemberRole: vi.fn(),
}))

vi.mock('../lib/licenceAdmin', () => ({
  getLicence: () => Promise.resolve(null),
}))

vi.mock('../lib/publicBranding', () => ({
  useBrandingInstanceName: () => 'Organisation Démo',
}))

vi.mock('../lib/platformRoles', () => ({
  grantPlatformRole: vi.fn(),
  revokePlatformRole: vi.fn(),
}))

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ organizationName: 'Organisation Démo', me: { id: 'self-admin' } }),
}))

import { TeamAdminPage } from './TeamAdminPage'

const INVITE = {
  id: 'i1',
  email: 'marc.petit@example.com',
  teamId: 't-conf',
  teamName: 'Conformité',
  teamRole: 'lecteur',
  globalRole: 'CONTRIBUTEUR',
  status: 'pending',
  createdAt: '2026-10-01T00:00:00.000Z',
  expiresAt: '2026-10-15T00:00:00.000Z',
  lastSentAt: '2026-10-01T00:00:00.000Z',
  invitedBy: 'u-tf',
  invitedByName: 'Tarek Fezai',
  sendCount: 1,
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <TeamAdminPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/**
 * jsdom n'exécute pas le comportement d'activation HTML (Entrée / Espace → click)
 * des <button>. On le reproduit uniquement pour un <button type="button">
 * encore dans le document : un <span role="button"> ne reçoit pas le click.
 */
async function pressButton(name: string, key: 'Enter' | ' ') {
  const first = screen.getByRole('button', { name }) as HTMLButtonElement
  if (!first.disabled) first.focus()
  await act(async () => {})
  const button = screen.getByRole('button', { name }) as HTMLButtonElement
  if (!button.disabled) expect(document.activeElement).toBe(button)
  fireEvent.keyDown(button, { key })
  if (key === ' ') fireEvent.keyUp(button, { key: ' ' })
  if (button.disabled) return
  if (button.tagName !== 'BUTTON' || button.getAttribute('type') !== 'button') {
    throw new Error(`activation refusée (${key}): ${button.tagName} type=${button.getAttribute('type')}`)
  }
  fireEvent.click(button)
  await act(async () => {})
}

const EMPTY_USERS = {
  users: [],
  total: 0,
  activeCount: 1,
  teamCount: 1,
  offset: 0,
  limit: 200,
}

describe('TeamAdminPage invitations', () => {
  beforeEach(() => {
    listInvitations.mockReset()
    resendInvitation.mockReset()
    revokeInvitation.mockReset()
    getInvitationCapability.mockReset()
    listAdminUsers.mockReset()
    listInvitations.mockResolvedValue([INVITE])
    resendInvitation.mockResolvedValue(INVITE)
    revokeInvitation.mockResolvedValue(undefined)
    getInvitationCapability.mockResolvedValue({ enabled: true, missing: [] })
    listAdminUsers.mockResolvedValue(EMPTY_USERS)
  })

  it('désactive l’invitation quand l’envoi n’est pas configuré', async () => {
    getInvitationCapability.mockResolvedValue({
      enabled: false,
      missing: ['SOCLE_PUBLIC_BASE_URL', 'SOCLE_SMTP_HOST'],
    })
    renderPage()
    await waitFor(() => {
      const invite = screen.getByRole('button', { name: '+ Inviter un membre' }) as HTMLButtonElement
      expect(invite.disabled).toBe(true)
    })
    expect(
      screen.getByText(
        "L'envoi d'invitations n'est pas configuré (SOCLE_PUBLIC_BASE_URL, SOCLE_SMTP_HOST).",
      ),
    ).toBeTruthy()
  })

  it('Renvoyer et Annuler sont des boutons : focus, Entrée et Espace', async () => {
    renderPage()
    fireEvent.click(screen.getByRole('tab', { name: /Invitations/ }))
    const resend = await screen.findByRole('button', { name: 'Renvoyer' })
    const cancel = screen.getByRole('button', { name: 'Annuler' })
    expect(resend.tagName).toBe('BUTTON')
    expect(resend.getAttribute('type')).toBe('button')
    expect(cancel.tagName).toBe('BUTTON')
    expect(cancel.getAttribute('type')).toBe('button')
    expect(document.querySelector('[role="button"]')).toBeNull()

    await pressButton('Renvoyer', 'Enter')
    await pressButton('Renvoyer', ' ')
    expect(resendInvitation).toHaveBeenCalledTimes(2)
    expect(resendInvitation).toHaveBeenCalledWith(expect.anything(), 'i1')

    await pressButton('Annuler', 'Enter')
    await pressButton('Annuler', ' ')
    expect(revokeInvitation).toHaveBeenCalledTimes(2)
    expect(revokeInvitation).toHaveBeenCalledWith(expect.anything(), 'i1')

    expect(screen.getByText(/expirent après 14 jours/)).toBeTruthy()
  })

  it('désactive Désactiver pour soi-même et pour le dernier administrateur', async () => {
    listAdminUsers.mockResolvedValue({
      ...EMPTY_USERS,
      users: [
        {
          id: 'self-admin',
          displayName: 'Moi',
          email: 'moi@example.com',
          initials: 'MO',
          platformRoles: ['CONTRIBUTEUR'],
          teams: [],
          status: 'active',
          lastActivityAt: null,
        },
        {
          id: 'other',
          displayName: 'Autre',
          email: 'autre@example.com',
          initials: 'AU',
          platformRoles: ['ADMINISTRATEUR_SYSTEME'],
          teams: [],
          status: 'active',
          lastActivityAt: null,
        },
      ],
    })
    renderPage()
    const buttons = await screen.findAllByRole('button', { name: 'Désactiver' })
    const self = buttons[0] as HTMLButtonElement
    const other = buttons[1] as HTMLButtonElement
    expect(self.disabled).toBe(true)
    expect(self.parentElement?.getAttribute('title')).toBe(
      'Vous ne pouvez pas désactiver votre propre compte.',
    )
    expect(other.disabled).toBe(true)
    expect(other.parentElement?.getAttribute('title')).toBe(
      'Impossible de désactiver le dernier administrateur système.',
    )
  })

  it('désactive le bouton pendant la requête', async () => {
    let release: (value: unknown) => void = () => {}
    resendInvitation.mockImplementation(() => new Promise((resolve) => { release = resolve }))
    renderPage()
    fireEvent.click(screen.getByRole('tab', { name: /Invitations/ }))
    const resend = await screen.findByRole('button', { name: 'Renvoyer' })
    fireEvent.click(resend)
    await waitFor(() => expect((resend as HTMLButtonElement).disabled).toBe(true))
    await pressButton('Renvoyer', 'Enter')
    await pressButton('Renvoyer', ' ')
    expect(resendInvitation).toHaveBeenCalledTimes(1)
    release(INVITE)
    await waitFor(() => expect((resend as HTMLButtonElement).disabled).toBe(false))
  })
})
