import { test, expect } from '@playwright/test'
import { fetchPasswordToken, loginViaUi } from '../helpers/auth'
import { apiJson, type SpaceSummary } from '../helpers/api'

const USER = process.env.E2E_USER_A ?? 'contributeur'
const PASS = process.env.E2E_PASS_A ?? 'contributeur'

type Folder = { id: string; name: string }
type Doc = { id: string; title: string }

test('la sidebar montre le dossier, le sous-dossier et le document', async ({ page }) => {
  const token = await fetchPasswordToken(USER, PASS)
  const stamp = Date.now()
  const space = await apiJson<SpaceSummary>('/api/v1/spaces', token, {
    method: 'POST',
    body: JSON.stringify({ name: `E2E sidebar ${stamp}`, color: '#3730E0' }),
  })
  const folder = await apiJson<Folder>('/api/v1/folders', token, {
    method: 'POST',
    body: JSON.stringify({ spaceId: space.id, name: `Dossier ${stamp}` }),
  })
  const child = await apiJson<Folder>('/api/v1/folders', token, {
    method: 'POST',
    body: JSON.stringify({
      spaceId: space.id,
      parentFolderId: folder.id,
      name: `Sous-dossier ${stamp}`,
    }),
  })
  const doc = await apiJson<Doc>('/api/v1/documents', token, {
    method: 'POST',
    body: JSON.stringify({
      title: `Document ${stamp}`,
      spaceId: space.id,
      folderId: child.id,
      docType: 'page',
      body: { type: 'doc', content: [{ type: 'paragraph' }] },
    }),
  })

  await loginViaUi(page, USER, PASS)
  await page.goto('/')
  const sidebar = page.locator('.shell-sidebar')
  const spaceBtn = sidebar.getByRole('button', { name: space.name, exact: true })
  await expect(spaceBtn).toBeVisible({ timeout: 20_000 })
  if ((await spaceBtn.getAttribute('aria-expanded')) !== 'true') {
    await spaceBtn.click()
  }

  const folderLink = sidebar.getByRole('link', { name: folder.name, exact: true })
  await folderLink.scrollIntoViewIfNeeded()
  await expect(folderLink).toBeVisible()
  await sidebar.getByRole('button', { name: `Déplier ${folder.name}` }).click()

  const childLink = sidebar.getByRole('link', { name: child.name, exact: true })
  await expect(childLink).toBeVisible()
  await sidebar.getByRole('button', { name: `Déplier ${child.name}` }).click()
  await expect(sidebar.getByRole('link', { name: doc.title })).toBeVisible()

  await childLink.click()
  await expect(page).toHaveURL(new RegExp(`/folders/${child.id}$`))
  await expect(page.getByTestId('folder-page')).toBeVisible({ timeout: 20_000 })
})
