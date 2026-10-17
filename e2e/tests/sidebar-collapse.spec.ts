import { test, expect } from '@playwright/test'
import { loginViaUi } from '../helpers/auth'

const USER = process.env.E2E_USER_A ?? 'contributeur'
const PASS = process.env.E2E_PASS_A ?? 'contributeur'

test('réduire le menu le laisse réduit après rechargement', async ({ page }) => {
  await loginViaUi(page, USER, PASS)
  const sidebar = page.locator('.shell-sidebar')
  const toggle = sidebar.getByRole('button', { name: 'Réduire ou agrandir le menu' })
  await expect(toggle).toBeVisible({ timeout: 20_000 })
  await expect(toggle).toHaveAttribute('aria-expanded', 'true')

  await toggle.click()
  await expect(sidebar).toHaveClass(/is-collapsed/)
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')

  await page.reload()
  await expect(sidebar).toHaveClass(/is-collapsed/, { timeout: 20_000 })
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
})
