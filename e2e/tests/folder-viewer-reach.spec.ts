import { test, expect } from '@playwright/test'
import { fetchPasswordToken, loginViaUi } from '../helpers/auth'
import { apiJson, type MeResponse, type SpaceSummary } from '../helpers/api'
import { createGuest, deleteGuest, type Guest } from '../helpers/keycloak-admin'

const USER = process.env.E2E_USER_A ?? 'contributeur'
const PASS = process.env.E2E_PASS_A ?? 'contributeur'

type Folder = { id: string; name: string }
type Doc = { id: string; title: string }

test('un lecteur de dossier atteint l’espace, le dossier vide et le document', async ({ page }) => {
  const token = await fetchPasswordToken(USER, PASS)
  const stamp = Date.now()
  const spaceName = `E2E atteinte ${stamp}`
  const emptyName = `Vide ${stamp}`
  const docsName = `Documents ${stamp}`
  const hiddenName = `Caché ${stamp}`
  const docTitle = `Page ${stamp}`

  const space = await apiJson<SpaceSummary>('/api/v1/spaces', token, {
    method: 'POST',
    body: JSON.stringify({ name: spaceName, color: '#3730E0' }),
  })
  const empty = await apiJson<Folder>('/api/v1/folders', token, {
    method: 'POST',
    body: JSON.stringify({ spaceId: space.id, name: emptyName }),
  })
  const docs = await apiJson<Folder>('/api/v1/folders', token, {
    method: 'POST',
    body: JSON.stringify({ spaceId: space.id, name: docsName }),
  })
  await apiJson<Folder>('/api/v1/folders', token, {
    method: 'POST',
    body: JSON.stringify({ spaceId: space.id, name: hiddenName }),
  })
  const doc = await apiJson<Doc>('/api/v1/documents', token, {
    method: 'POST',
    body: JSON.stringify({
      title: docTitle,
      spaceId: space.id,
      folderId: docs.id,
      docType: 'page',
      body: { type: 'doc', content: [{ type: 'paragraph' }] },
    }),
  })
  await apiJson(`/api/v1/spaces/${space.id}/page`, token, {
    method: 'PUT',
    body: JSON.stringify({
      body: {
        type: 'doc',
        content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Page privée' }] }],
      },
      changeSummary: 'Création',
    }),
  })

  let guest: Guest | null = null
  try {
    guest = await createGuest()
    const guestToken = await fetchPasswordToken(guest.username, guest.password)
    const me = await apiJson<MeResponse>('/api/v1/me', guestToken)
    for (const folderId of [empty.id, docs.id]) {
      const grant = await fetch(
        `${process.env.API_BASE_URL ?? process.env.BASE_URL ?? 'http://127.0.0.1'}/api/v1/access/folder/${folderId}`,
        {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ relation: 'viewer', subjectType: 'user', subjectId: me.id }),
        },
      )
      expect(grant.ok).toBeTruthy()
    }

    const base = process.env.API_BASE_URL ?? process.env.BASE_URL ?? 'http://127.0.0.1'
    const auth = { Authorization: `Bearer ${guestToken}` }
    expect((await fetch(`${base}/api/v1/folders/${empty.id}`, { headers: auth })).status).toBe(200)
    expect((await fetch(`${base}/api/v1/spaces/${space.id}/page`, { headers: auth })).status).toBe(204)

    await loginViaUi(page, guest.username, guest.password)
    const sidebar = page.locator('.shell-sidebar')
    await expect(sidebar.getByRole('button', { name: spaceName })).toBeVisible()
    await expect(sidebar.getByRole('link', { name: emptyName })).toBeVisible()
    await expect(sidebar.getByRole('link', { name: docsName })).toBeVisible()
    await expect(sidebar.getByRole('link', { name: hiddenName })).toHaveCount(0)

    await sidebar.getByRole('link', { name: 'Tous les espaces' }).click()
    await page.getByRole('link', { name: spaceName }).click()
    await expect(page).toHaveURL(new RegExp(`/spaces/${space.id}$`))
    await expect(page.getByRole('heading', { name: spaceName })).toBeVisible()
    await expect(page.getByRole('tab', { name: 'Modifier' })).toHaveCount(0)
    await expect(page.getByRole('tab', { name: 'Accès' })).toHaveCount(0)
    await expect(page.getByTestId('space-new-folder')).toHaveCount(0)
    await expect(page.getByTestId('space-new-document')).toHaveCount(0)
    await expect(page.getByRole('tab', { name: 'Historique' })).toBeDisabled()
    await expect(page.getByTestId('toggle-comments')).toBeDisabled()
    await expect(page.getByText('Page privée')).toHaveCount(0)

    await page.getByTestId('space-home').getByRole('link', { name: docsName }).click()
    await expect(page).toHaveURL(new RegExp(`/folders/${docs.id}$`))
    await expect(page.getByRole('heading', { name: docsName })).toBeVisible()
    await page.getByTestId('folder-page').getByRole('link', { name: docTitle }).click()
    await expect(page).toHaveURL(new RegExp(`/docs/${doc.id}$`))
    await expect(page.getByRole('heading', { name: docTitle })).toBeVisible()

    await sidebar.getByRole('link', { name: emptyName }).click()
    await expect(page).toHaveURL(new RegExp(`/folders/${empty.id}$`))
    await expect(page.getByTestId('folder-page')).toBeVisible()
    await expect(page.getByRole('heading', { name: emptyName })).toBeVisible()
    await expect(page.getByText('introuvable')).toHaveCount(0)

    await page.getByTestId('folder-page').getByRole('link', { name: spaceName }).first().click()
    await expect(page).toHaveURL(new RegExp(`/spaces/${space.id}$`))
    await expect(page.getByRole('heading', { name: spaceName })).toBeVisible()
    await expect(page.getByRole('tab', { name: 'Modifier' })).toHaveCount(0)
  } finally {
    await deleteGuest(guest)
  }
})
