import { test, expect } from '@playwright/test'
import { fetchPasswordToken, loginViaUi } from '../helpers/auth'
import { apiJson, type MeResponse, type SpaceSummary } from '../helpers/api'

const USER = process.env.E2E_USER_A ?? 'contributeur'
const PASS = process.env.E2E_PASS_A ?? 'contributeur'
const READER = process.env.E2E_USER_AUDITOR ?? 'auditeur'
const READER_PASS = process.env.E2E_PASS_AUDITOR ?? 'auditeur'

type PageDoc = { id: string }

test.describe.serial("Accueil d'espace", () => {
  let token = ''
  let spaceId = ''
  let spaceName = ''

  test("la carte mène à l'accueil, le contributeur écrit la page puis la relit", async ({ page }) => {
    token = await fetchPasswordToken(USER, PASS)
    spaceName = `E2E accueil ${Date.now()}`
    const space = await apiJson<SpaceSummary>('/api/v1/spaces', token, {
      method: 'POST',
      body: JSON.stringify({ name: spaceName, color: '#3730E0' }),
    })
    spaceId = space.id

    await loginViaUi(page, USER, PASS)
    await page.goto('/spaces')
    await page.getByRole('link', { name: spaceName }).click()
    await expect(page).toHaveURL(new RegExp(`/spaces/${spaceId}$`))
    await expect(page.getByRole('heading', { name: spaceName })).toBeVisible()
    await expect(page.getByTestId('space-page-empty')).toBeVisible()

    await page.getByRole('tab', { name: 'Modifier' }).click()
    const editor = page.getByTestId('edit-prosemirror')
    await editor.waitFor({ timeout: 60_000 })
    await editor.click()
    await page.keyboard.type("Page d'accueil de l'espace.")
    await expect(editor).toContainText("Page d'accueil de l'espace.")

    const saved = page.waitForResponse(
      (r) => r.request().method() === 'PUT' && r.url().includes(`/spaces/${spaceId}/page`),
      { timeout: 20_000 },
    )
    await page.getByTestId('edit-save-version').click()
    await page.getByTestId('edit-save-version-confirm').click()
    const created = await saved
    expect(created.ok()).toBeTruthy()
    const createdBody = (await created.json()) as PageDoc
    expect(createdBody.id).toBeTruthy()

    await expect(page.getByTestId('edit-prosemirror')).toContainText("Page d'accueil de l'espace.", {
      timeout: 20_000,
    })
    await expect(page.getByTestId('edit-toolbar')).toHaveCount(0)
  })

  test('lecteur : page en lecture seule, sans boutons de création', async ({ page }) => {
    const readerToken = await fetchPasswordToken(READER, READER_PASS)
    const me = await apiJson<MeResponse>('/api/v1/me', readerToken)
    const base = process.env.API_BASE_URL ?? process.env.BASE_URL ?? 'http://127.0.0.1'
    const grant = await fetch(`${base}/api/v1/access/space/${spaceId}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ relation: 'viewer', subjectType: 'user', subjectId: me.id }),
    })
    expect(grant.ok).toBeTruthy()

    await loginViaUi(page, READER, READER_PASS)
    await page.goto(`/spaces/${spaceId}`)
    await expect(page.getByTestId('edit-prosemirror')).toContainText("Page d'accueil de l'espace.")
    await expect(page.getByTestId('edit-prosemirror')).toHaveAttribute('contenteditable', 'false')
    await expect(page.getByRole('tab', { name: 'Modifier' })).toHaveCount(0)
    await expect(page.getByTestId('space-new-folder')).toHaveCount(0)
    await expect(page.getByTestId('space-new-document')).toHaveCount(0)
  })

  test('/tree redirige vers l’accueil, Graphe ouvre le graphe, le ZIP se télécharge', async ({ page }) => {
    await loginViaUi(page, USER, PASS)
    await page.goto(`/spaces/${spaceId}/tree`)
    await expect(page).toHaveURL(new RegExp(`/spaces/${spaceId}$`))
    await expect(page.getByRole('heading', { name: spaceName })).toBeVisible()

    await page.getByRole('link', { name: 'Graphe' }).click()
    await expect(page).toHaveURL(new RegExp(`/spaces/${spaceId}/graph$`))
    await expect(page.getByText('Vue graphe')).toBeVisible()

    await page.goto(`/spaces/${spaceId}`)
    const download = page.waitForEvent('download')
    await page.getByTestId('space-export').click()
    const file = await download
    expect(file.suggestedFilename().toLowerCase()).toMatch(/\.zip$/)
  })
})
