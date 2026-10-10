import { test, expect } from '@playwright/test'
import { fetchPasswordToken, loginViaUi } from '../helpers/auth'
import { apiJson, type DocumentResponse, type SpaceSummary, type TemplateSummary } from '../helpers/api'

const USER_A = process.env.E2E_USER_A ?? 'contributeur'
const PASS_A = process.env.E2E_PASS_A ?? 'contributeur'
const apiBase = process.env.API_BASE_URL ?? process.env.BASE_URL ?? 'http://127.0.0.1'

async function withPat(path: string, pat: string, init: RequestInit = {}) {
  return fetch(`${apiBase}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${pat}`, 'Content-Type': 'application/json', ...(init.headers ?? {}) },
  })
}

test.describe.serial('Jetons d’accès personnels', () => {
  let documentId: string
  let pat: string
  const tokenName = `E2E lecture ${Date.now()}`

  test('préparation : un document lisible par contributeur', async () => {
    const jwt = await fetchPasswordToken(USER_A, PASS_A)
    const space = await apiJson<SpaceSummary>('/api/v1/spaces', jwt, {
      method: 'POST',
      body: JSON.stringify({ name: `E2E PAT ${Date.now()}`, color: '#3730E0' }),
    })
    const templates = await apiJson<TemplateSummary[]>(`/api/v1/templates?spaceId=${space.id}`, jwt)
    const template = templates.find((t) => t.name === 'Procédure') ?? templates[0]
    expect(template).toBeTruthy()
    const doc = await apiJson<DocumentResponse>('/api/v1/documents', jwt, {
      method: 'POST',
      body: JSON.stringify({
        title: `Procédure PAT ${Date.now()}`,
        spaceId: space.id,
        templateId: template!.id,
        docType: 'procedure',
      }),
    })
    documentId = doc.id
  })

  test('création dans l’UI → jeton affiché une seule fois', async ({ page }) => {
    await loginViaUi(page, USER_A, PASS_A)
    await page.goto('/account')
    await page.getByTestId('pat-generate-link').click()
    await expect(page).toHaveURL(/\/account\/tokens\/new$/)

    const modal = page.getByTestId('pat-generate-modal')
    await modal.getByLabel('Nom du jeton').fill(tokenName)
    await modal.getByRole('radio', { name: '7 jours' }).click()
    await modal.getByRole('button', { name: 'Générer le jeton' }).click()

    const plaintext = page.getByTestId('pat-plaintext')
    await expect(plaintext).toHaveText(/^pat_[0-9A-Za-z]{12}_[0-9A-Za-z]{43}$/)
    pat = (await plaintext.textContent())!.trim()
    await expect(page.getByText('Copiez ce jeton maintenant : il ne sera plus affiché.')).toBeVisible()

    await page.getByRole('button', { name: 'Terminé' }).click()
    await expect(page).toHaveURL(/\/account$/)
    await expect(page.getByText(pat)).toHaveCount(0)
    const row = page.getByTestId('pat-row').filter({ hasText: tokenName })
    await expect(row).toContainText(`pat_••••••••••••${pat.slice(-4)}`)
    await expect(row).toContainText('lecture seule')
    await expect(row).toContainText('expire le')
  })

  test('lecture seule : GET document 200, POST 403 pat_scope_insufficient', async () => {
    const get = await withPat(`/api/v1/documents/${documentId}`, pat)
    expect(get.status).toBe(200)
    expect(((await get.json()) as DocumentResponse).id).toBe(documentId)

    const post = await withPat('/api/v1/spaces', pat, {
      method: 'POST',
      body: JSON.stringify({ name: `E2E PAT refusé ${Date.now()}`, color: '#3730E0' }),
    })
    expect(post.status).toBe(403)
    expect(await post.text()).toContain('pat_scope_insufficient')
  })

  test('révocation dans l’UI → 401', async ({ page }) => {
    await loginViaUi(page, USER_A, PASS_A)
    await page.goto('/account')
    const row = page.getByTestId('pat-row').filter({ hasText: tokenName })
    await row.getByRole('button', { name: 'Révoquer' }).click()
    await row.getByRole('button', { name: 'Confirmer' }).click()
    await expect(page.getByTestId('pat-row').filter({ hasText: tokenName })).toHaveCount(0)

    const after = await withPat(`/api/v1/documents/${documentId}`, pat)
    expect(after.status).toBe(401)
  })
})
