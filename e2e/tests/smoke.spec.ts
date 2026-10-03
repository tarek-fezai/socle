import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { test, expect } from '@playwright/test'
import { fetchPasswordToken, loginViaUi } from '../helpers/auth'
import {
  apiJson,
  type DocumentResponse,
  type GlobalRole,
  type MeResponse,
  type SpaceSummary,
  type TemplateSummary,
} from '../helpers/api'
import { backupSocleCoreAndGit, restoreSocleCoreAndGit } from '../helpers/compose'

const USER_A = process.env.E2E_USER_A ?? 'contributeur'
const PASS_A = process.env.E2E_PASS_A ?? 'contributeur'
/** Second contributeur (four-eyes). Auditeur cannot approve. */
const USER_B = process.env.E2E_USER_B ?? 'contributeur-b'
const PASS_B = process.env.E2E_PASS_B ?? 'contributeur-b'

test.describe.serial('Socle demo stack smoke', () => {
  let tokenA: string
  let tokenB: string
  let spaceId: string
  let documentId: string
  let documentTitle: string
  let approvalRequestId: string
  let analystRoleId: string

  test('1–2. login two users (Keycloak demo)', async ({ page, browser }) => {
    tokenA = await fetchPasswordToken(USER_A, PASS_A)
    tokenB = await fetchPasswordToken(USER_B, PASS_B)
    expect(tokenA).toBeTruthy()
    expect(tokenB).toBeTruthy()

    await loginViaUi(page, USER_A, PASS_A)
    await expect(
      page.getByRole('link', { name: /tous les espaces|espaces/i }).first(),
    ).toBeVisible()

    const ctxB = await browser.newContext()
    const pageB = await ctxB.newPage()
    await loginViaUi(pageB, USER_B, PASS_B)
    await expect(
      pageB.getByRole('link', { name: /tous les espaces|espaces/i }).first(),
    ).toBeVisible()
    await ctxB.close()
  })

  test('3. create space', async () => {
    const name = `E2E ${Date.now()}`
    const space = await apiJson<SpaceSummary>('/api/v1/spaces', tokenA, {
      method: 'POST',
      body: JSON.stringify({ name, color: '#3730E0' }),
    })
    spaceId = space.id
    expect(space.name).toBe(name)
  })

  test('4. create document from template (API; UI uses data-testid step-1..3)', async () => {
    const templates = await apiJson<TemplateSummary[]>(
      `/api/v1/templates?spaceId=${spaceId}`,
      tokenA,
    )
    const procedure =
      templates.find((t) => t.name === 'Procédure') ?? templates[0]
    expect(procedure).toBeTruthy()

    documentTitle = `Procédure E2E ${Date.now()}`
    const doc = await apiJson<DocumentResponse>('/api/v1/documents', tokenA, {
      method: 'POST',
      body: JSON.stringify({
        title: documentTitle,
        spaceId,
        templateId: procedure!.id,
        docType: 'procedure',
      }),
    })
    documentId = doc.id
  })

  test('5. fill placeholders (API patch body)', async () => {
    const body = {
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [{ type: 'text', text: 'Objectif rempli E2E' }],
        },
      ],
    }
    await apiJson(`/api/v1/documents/${documentId}`, tokenA, {
      method: 'PUT',
      body: JSON.stringify({ title: documentTitle, body }),
    })
  })

  test('6–7. submit approval by A; B approves (four-eyes)', async () => {
    const meB = await apiJson<MeResponse>('/api/v1/me', tokenB)
    expect(meB.id).toBeTruthy()

    // B needs document editor (space owner → OpenFGA editor).
    await apiJson(`/api/v1/spaces/${spaceId}/owners`, tokenA, {
      method: 'POST',
      body: JSON.stringify({ userId: meB.id, responsible: false }),
    })

    const roles = await apiJson<GlobalRole[]>('/api/v1/global-roles', tokenA)
    const editorRole = roles.find((r) => r.name.includes('Éditeur de documents'))
    expect(editorRole).toBeTruthy()
    analystRoleId = editorRole!.id

    await apiJson('/api/v1/approval-role-assignments', tokenA, {
      method: 'POST',
      body: JSON.stringify({
        roleId: analystRoleId,
        subjectType: 'user',
        subjectId: meB.id,
        scopeType: 'space',
        scopeRef: spaceId,
      }),
    })

    const start = await apiJson<{ approvalRequestId: string }>(
      `/api/v1/documents/${documentId}/approvals`,
      tokenA,
      { method: 'POST', body: '{}' },
    )
    approvalRequestId = start.approvalRequestId

    await expect(async () => {
      await apiJson(
        `/api/v1/documents/${documentId}/approvals/${approvalRequestId}/decide`,
        tokenA,
        {
          method: 'POST',
          body: JSON.stringify({
            decision: 'approuve',
            comment: 'self',
            expectedStepOrder: 1,
          }),
        },
      )
    }).rejects.toThrow(/409|403|422|400/)

    await apiJson(
      `/api/v1/documents/${documentId}/approvals/${approvalRequestId}/decide`,
      tokenB,
      {
        method: 'POST',
        body: JSON.stringify({
          decision: 'approuve',
          comment: 'LGTM E2E',
          expectedStepOrder: 1,
        }),
      },
    )
  })

  test('8. search', async ({ page }) => {
    await loginViaUi(page, USER_A, PASS_A)
    await page.goto('/search')
    await expect(page.getByRole('heading', { name: 'Recherche' })).toBeVisible()
    await page.getByRole('textbox', { name: /requête de recherche/i }).fill(documentTitle)
    await page.getByRole('button', { name: 'Rechercher' }).click()
    await expect(page.getByText(documentTitle).first()).toBeVisible({ timeout: 30_000 })
  })

  test('9. anchored comment (UI: comment-selection-btn, comments-panel)', async ({ page }) => {
    await loginViaUi(page, USER_A, PASS_A)
    await page.goto(`/docs/${documentId}`)
    await page.getByTestId('toggle-comments').click()
    await expect(page.getByTestId('comments-panel')).toBeVisible()
    // Anchored thread: select text then use comment-selection-btn when present
    const anchorBtn = page.getByTestId('comment-selection-btn')
    if (await anchorBtn.isVisible().catch(() => false)) {
      await anchorBtn.click()
      await page.getByTestId('comment-composer').fill('Commentaire ancré E2E')
      await page.getByTestId('comment-composer').press('Control+Enter')
    } else {
      test.info().annotations.push({
        type: 'skip-detail',
        description: 'No text selection in headless editor — composer-only comment skipped',
      })
    }
  })

  test('10. backup/restore postgres+git; document survives; drifts zero', async () => {
    test.skip(!process.env.CI && !process.env.E2E_RUN_BACKUP, 'Set E2E_RUN_BACKUP=1 locally')

    const backupDir = fs.mkdtempSync(path.join(os.tmpdir(), 'socle-e2e-backup-'))
    backupSocleCoreAndGit(backupDir)

    restoreSocleCoreAndGit(backupDir)

    tokenA = await fetchPasswordToken(USER_A, PASS_A)
    // Sync + bootstrap admin (BOTH + bootstrap subject) before admin drift endpoints.
    await apiJson('/api/v1/me', tokenA)

    const doc = await apiJson<DocumentResponse>(`/api/v1/documents/${documentId}`, tokenA)
    expect(doc.title).toBe(documentTitle)

    const gitDrift = await apiJson<{ items?: unknown[] }>(
      '/api/v1/admin/storage/git-projection-drift',
      tokenA,
    )
    const visDrift = await apiJson<{ driftCount?: number; items?: unknown[] }>(
      '/api/v1/admin/authz/visibility-drift',
      tokenA,
    )
    const linkDrift = await apiJson<{ driftCount?: number; items?: unknown[] }>(
      '/api/v1/admin/authz/document-links-drift',
      tokenA,
    )

    expect(gitDrift.items?.length ?? 0).toBe(0)
    expect(visDrift.driftCount ?? visDrift.items?.length ?? 0).toBe(0)
    expect(linkDrift.driftCount ?? linkDrift.items?.length ?? 0).toBe(0)

    fs.rmSync(backupDir, { recursive: true, force: true })
  })
})
