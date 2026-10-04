import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { test, expect } from '@playwright/test'
import { fetchPasswordToken, loginViaUi } from '../helpers/auth'
import {
  apiJson,
  type DocumentResponse,
  type SpaceSummary,
  type TemplateSummary,
} from '../helpers/api'

const USER = process.env.E2E_USER_A ?? 'contributeur'
const PASS = process.env.E2E_PASS_A ?? 'contributeur'

/** 1×1 PNG */
function writeTinyPng(filePath: string) {
  fs.writeFileSync(
    filePath,
    Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
      'base64',
    ),
  )
}

test.describe.serial('Editor image insert (toolbar + in-page drop)', () => {
  let token: string
  let documentId: string
  let pngPath: string

  test.beforeAll(() => {
    pngPath = path.join(os.tmpdir(), `socle-e2e-img-${Date.now()}.png`)
    writeTinyPng(pngPath)
  })

  test.afterAll(() => {
    try {
      fs.unlinkSync(pngPath)
    } catch {
      /* ignore */
    }
  })

  test('setup document', async () => {
    token = await fetchPasswordToken(USER, PASS)
    const space = await apiJson<SpaceSummary>('/api/v1/spaces', token, {
      method: 'POST',
      body: JSON.stringify({ name: `E2E img ${Date.now()}`, color: '#3730E0' }),
    })
    const templates = await apiJson<TemplateSummary[]>(
      `/api/v1/templates?spaceId=${space.id}`,
      token,
    )
    const tpl = templates.find((t) => t.name === 'Procédure') ?? templates[0]
    const doc = await apiJson<DocumentResponse>('/api/v1/documents', token, {
      method: 'POST',
      body: JSON.stringify({
        title: `E2E image insert ${Date.now()}`,
        spaceId: space.id,
        templateId: tpl!.id,
        docType: 'procedure',
      }),
    })
    documentId = doc.id
  })

  test('toolbar Image → POST /attachments 201 + node in editor', async ({ page }) => {
    await loginViaUi(page, USER, PASS)
    await page.goto(`/docs/${documentId}/edit`)
    await page.getByTestId('edit-prosemirror').waitFor({ timeout: 60_000 })

    const post = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/documents\/[^/]+\/attachments/.test(r.url()),
      { timeout: 30_000 },
    )

    // Real File from disk (same path as the toolbar file picker).
    await page.getByTestId('edit-image-input').setInputFiles(pngPath)
    const res = await post
    expect(res.status(), await res.text()).toBe(201)
    const body = (await res.json()) as { id: string; mediaType: string }
    expect(body.mediaType).toMatch(/^image\//)
    await expect(page.locator(`[data-attachment-id="${body.id}"]`)).toBeVisible({
      timeout: 15_000,
    })
  })

  test('in-page drop with File+DataTransfer+dragover → POST 201', async ({ page }) => {
    const space = await apiJson<SpaceSummary>('/api/v1/spaces', token, {
      method: 'POST',
      body: JSON.stringify({ name: `E2E drop ${Date.now()}`, color: '#3730E0' }),
    })
    const templates = await apiJson<TemplateSummary[]>(
      `/api/v1/templates?spaceId=${space.id}`,
      token,
    )
    const tpl = templates.find((t) => t.name === 'Procédure') ?? templates[0]
    const doc = await apiJson<DocumentResponse>('/api/v1/documents', token, {
      method: 'POST',
      body: JSON.stringify({
        title: `E2E drop ${Date.now()}`,
        spaceId: space.id,
        templateId: tpl!.id,
        docType: 'procedure',
      }),
    })

    await loginViaUi(page, USER, PASS)
    await page.goto(`/docs/${doc.id}/edit`)
    const editor = page.getByTestId('edit-prosemirror')
    await editor.waitFor({ timeout: 60_000 })
    const box = await editor.boundingBox()
    expect(box).toBeTruthy()

    const post = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/documents\/[^/]+\/attachments/.test(r.url()),
      { timeout: 30_000 },
    )

    const pngB64 = fs.readFileSync(pngPath).toString('base64')
    await page.evaluate(
      ({ x, y, b64 }) => {
        const root = document.querySelector('[data-testid="edit-prosemirror"]')
        const dom = root?.querySelector('.ProseMirror') || root
        if (!dom) throw new Error('no ProseMirror')
        const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
        const file = new File([bytes], 'drop.png', { type: 'image/png' })
        const dt = new DataTransfer()
        dt.items.add(file)
        const over = new DragEvent('dragover', {
          bubbles: true,
          cancelable: true,
          clientX: x,
          clientY: y,
          dataTransfer: dt,
        })
        Object.defineProperty(over, 'dataTransfer', { get: () => dt })
        dom.dispatchEvent(over)
        const drop = new DragEvent('drop', {
          bubbles: true,
          cancelable: true,
          clientX: x,
          clientY: y,
          dataTransfer: dt,
        })
        Object.defineProperty(drop, 'dataTransfer', { get: () => dt })
        dom.dispatchEvent(drop)
      },
      { x: box!.x + box!.width / 2, y: box!.y + Math.min(80, box!.height / 2), b64: pngB64 },
    )

    const res = await post
    expect(res.status(), await res.text()).toBe(201)
  })
})
