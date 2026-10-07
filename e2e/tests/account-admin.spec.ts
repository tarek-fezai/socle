import { test, expect } from '@playwright/test'
import { loginViaUi } from '../helpers/auth'

const USER_ADMIN = process.env.E2E_USER_A ?? 'contributeur'
const PASS_ADMIN = process.env.E2E_PASS_A ?? 'contributeur'
const USER_AUDITOR = process.env.E2E_USER_AUDITOR ?? 'auditeur'
const PASS_AUDITOR = process.env.E2E_PASS_AUDITOR ?? 'auditeur'

test.describe('Account → Admin navigation', () => {
  test('contributeur (admin) : avatar → compte → /admin', async ({ page }) => {
    await loginViaUi(page, USER_ADMIN, PASS_ADMIN)
    await page.getByTestId('shell-user-menu-trigger').click()
    await page.getByRole('menuitem', { name: /Paramètres du compte/i }).click()
    await expect(page).toHaveURL(/\/account/)
    await expect(page.getByTestId('account-page')).toBeVisible()
    await expect(page.getByTestId('account-org-admin')).toBeVisible()
    await page.getByTestId('account-org-admin').click()
    await expect(page).toHaveURL(/\/admin\/?$/)
    await expect(page.getByTestId('admin-forbidden')).toHaveCount(0)
    await expect(page.locator('[data-mock-id="admin-home-title"]')).toBeVisible()
    await expect(page.locator('[data-mock-id="admin-stats-users"] .admin-rail__value')).toHaveText(/^\d+$/)
    await expect(page.locator('[data-mock-id="admin-sso-provider"] .admin-mono').first()).toContainText(/https?:\/\//)
  })

  test('auditeur : pas de carte Organisation, /admin = 403', async ({ page }) => {
    await loginViaUi(page, USER_AUDITOR, PASS_AUDITOR)
    await page.goto('/account')
    await expect(page.getByTestId('account-page')).toBeVisible()
    await expect(page.getByTestId('account-org-admin')).toHaveCount(0)
    await page.goto('/admin')
    await expect(page.getByTestId('admin-forbidden')).toBeVisible()
  })
})
