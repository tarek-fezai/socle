import { test, expect } from '@playwright/test'
import { fetchPasswordToken, loginViaUi } from '../helpers/auth'
import { apiJson, type MeResponse, type SpaceSummary } from '../helpers/api'

const USER = process.env.E2E_USER_A ?? 'contributeur'
const PASS = process.env.E2E_PASS_A ?? 'contributeur'
const READER = process.env.E2E_USER_AUDITOR ?? 'auditeur'
const READER_PASS = process.env.E2E_PASS_AUDITOR ?? 'auditeur'

type Folder = { id: string; name: string; pageDocumentId?: string | null }
type PageDoc = { id: string; body?: { content?: Array<{ content?: Array<{ text?: string }> }> } }
type TrashItem = { resourceType?: string; resourceId?: string; title?: string }
type TrashPage = { items?: TrashItem[]; total?: number }

test.describe.serial('Page de dossier', () => {
  let token = ''
  let spaceId = ''
  let folderId = ''
  let pageId = ''

  test('contributeur modifie la page, voit la version et commente', async ({ page }) => {
    token = await fetchPasswordToken(USER, PASS)
    const space = await apiJson<SpaceSummary>('/api/v1/spaces', token, {
      method: 'POST',
      body: JSON.stringify({ name: `E2E dossier ${Date.now()}`, color: '#3730E0' }),
    })
    spaceId = space.id
    const folder = await apiJson<Folder>('/api/v1/folders', token, {
      method: 'POST',
      body: JSON.stringify({ spaceId, name: `Dossier E2E ${Date.now()}` }),
    })
    folderId = folder.id

    await loginViaUi(page, USER, PASS)
    await page.goto(`/folders/${folderId}`)
    await page.getByRole('tab', { name: 'Modifier' }).click()
    const editor = page.getByTestId('edit-prosemirror')
    await editor.waitFor({ timeout: 60_000 })
    await editor.click()
    await page.keyboard.type('Première phrase de la page.')
    await expect(editor).toContainText('Première phrase de la page.')

    const saved = page.waitForResponse(
      (r) => r.request().method() === 'PUT' && r.url().includes(`/folders/${folderId}/page`),
      { timeout: 20_000 },
    )
    await page.getByTestId('edit-save-version').click()
    await page.getByTestId('edit-save-version-confirm').click()
    const created = await saved
    expect(created.ok()).toBeTruthy()
    const createdBody = (await created.json()) as PageDoc
    pageId = createdBody.id

    await expect(page.getByTestId('edit-toolbar')).toHaveCount(0)
    await expect(page.getByTestId('edit-prosemirror')).toContainText('Première phrase de la page.', {
      timeout: 20_000,
    })

    await page.getByRole('tab', { name: 'Historique' }).click()
    await expect(page.getByTestId('folder-page-history')).toContainText('1 version publiée', { timeout: 20_000 })

    await page.getByRole('tab', { name: 'Commentaires' }).click()
    await page.getByTestId('comment-composer').fill('Note de revue e2e')
    const posted = page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().includes(`/documents/${pageId}/comments`),
      { timeout: 20_000 },
    )
    await page.getByRole('button', { name: 'Envoyer' }).click()
    const comment = await posted
    expect(comment.ok()).toBeTruthy()
    await expect(page.getByText('Note de revue e2e')).toBeVisible()
  })

  test('lecteur : la page n’est pas modifiable', async ({ page }) => {
    const readerToken = await fetchPasswordToken(READER, READER_PASS)
    const me = await apiJson<MeResponse>('/api/v1/me', readerToken)
    const grant = await fetch(`${process.env.API_BASE_URL ?? process.env.BASE_URL ?? 'http://127.0.0.1'}/api/v1/access/space/${spaceId}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ relation: 'viewer', subjectType: 'user', subjectId: me.id }),
    })
    const grantBody = await grant.text()
    expect([200, 201, 204, 409].includes(grant.status), `${grant.status} ${grantBody}`).toBeTruthy()

    await loginViaUi(page, READER, READER_PASS)
    await page.goto(`/folders/${folderId}`)
    await page.getByTestId('edit-prosemirror').waitFor({ timeout: 60_000 })
    await expect(page.getByRole('tab', { name: 'Modifier' })).toHaveCount(0)
    await expect(page.getByTestId('folder-format-bar')).toHaveCount(0)
    await expect(page.getByTestId('edit-toolbar')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '+ Sous-dossier' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '+ Nouveau document' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Autres actions du dossier' })).toHaveCount(0)
    await expect(page.getByTestId('edit-prosemirror')).toHaveAttribute('contenteditable', 'false')
  })

  test('déplacer le dossier depuis le menu', async ({ page }) => {
    const dest = await apiJson<Folder>('/api/v1/folders', token, {
      method: 'POST',
      body: JSON.stringify({ spaceId, name: `Destination ${Date.now()}` }),
    })
    await loginViaUi(page, USER, PASS)
    await page.goto(`/folders/${folderId}`)
    await page.getByRole('button', { name: 'Autres actions du dossier' }).click()
    await page.getByRole('menuitem', { name: 'Déplacer' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('radio', { name: dest.name }).click()
    const moved = page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().includes(`/folders/${folderId}/move`),
    )
    await dialog.getByRole('button', { name: 'Déplacer ici' }).click()
    const res = await moved
    expect(res.ok()).toBeTruthy()
    const body = (await res.json()) as { parentFolderId?: string | null }
    expect(body.parentFolderId).toBe(dest.id)
  })

  test('supprimer le dossier confirme dans la page et envoie la page à la corbeille', async ({ page }) => {
    await loginViaUi(page, USER, PASS)
    await page.goto(`/folders/${folderId}`)
    await page.getByRole('button', { name: 'Autres actions du dossier' }).click()
    await page.getByRole('menuitem', { name: 'Supprimer' }).click()
    const deleted = page.waitForResponse(
      (r) => r.request().method() === 'DELETE' && r.url().includes(`/folders/${folderId}`),
    )
    await page.getByTestId('folder-delete-confirm').click()
    const del = await deleted
    expect(del.ok()).toBeTruthy()
    const counts = (await del.json()) as { documents?: number; folders?: number }
    expect(counts.folders).toBeGreaterThanOrEqual(1)
    expect(counts.documents).toBeGreaterThanOrEqual(1)
    await page.waitForURL(/\/(spaces|trash|folders)\//, { timeout: 20_000 })

    const items: TrashItem[] = []
    let total = 0
    for (const resourceType of ['folder', 'document'] as const) {
      const trash = await apiJson<TrashPage>(`/api/v1/trash?resourceType=${resourceType}&limit=200`, token)
      total += trash.total ?? trash.items?.length ?? 0
      items.push(...(trash.items ?? []))
    }
    const dump = JSON.stringify({
      total,
      folderId,
      pageId,
      sample: items.slice(0, 8).map((i) => ({ t: i.resourceType, id: i.resourceId, title: i.title })),
    })
    expect(items.some((i) => i.resourceType === 'folder' && i.resourceId === folderId), dump).toBeTruthy()
    expect(items.some((i) => i.resourceType === 'document' && i.resourceId === pageId), dump).toBeTruthy()
  })
})
