import { expect, test as setup } from '@playwright/test'

import { ADMIN_EMAIL, ADMIN_PASSWORD, STORAGE_STATE } from './fixtures/mock-server'

/**
 * Authenticates the seeded admin account through the real /auth/login UI
 * and persists the resulting session (cookies + storage) to
 * {@link STORAGE_STATE}. The `chromium` project depends on this `setup`
 * project and reuses the saved state, so every spec runs as a genuinely
 * logged-in user — exercising the real auth → SSR profile lookup →
 * persona-routing flow rather than bypassing it.
 */
setup('authenticate as admin', async ({ page }) => {
  await page.goto('/auth/login')
  await page.waitForFunction(() => {
    const root = document.querySelector('#__nuxt') as Element & { __vue_app__?: { $nuxt?: { isHydrating: boolean } } }
    return root?.__vue_app__?.$nuxt?.isHydrating === false
  })

  // Mirrors app/pages/auth/login.vue: email by its placeholder, password by
  // its input type (robust to placeholder churn — it's the only one on the page).
  await page.getByPlaceholder('you@company.com').fill(ADMIN_EMAIL)
  await page.locator('input[type="password"]').fill(ADMIN_PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()

  // A successful login leaves /auth/* — the admin lands on /welcome (no studio
  // persona is assigned; access comes from the administrators group).
  await page.waitForURL(url => !url.pathname.startsWith('/auth'), { timeout: 20_000 })

  // Fail fast if the session cookie wasn't written, rather than saving an
  // unauthenticated state that makes every downstream spec fail opaquely.
  const cookies = await page.context().cookies()
  expect(cookies.some(c => c.name === '_bat'), 'expected a _bat session cookie after login').toBeTruthy()

  await page.context().storageState({ path: STORAGE_STATE })
})
