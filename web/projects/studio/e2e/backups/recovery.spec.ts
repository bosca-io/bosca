import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { resetMocks, setMocks } from '../fixtures/mock-server'

const totals = { total: 2, pending: 0, running: 0, completed: 1, failed: 1, totalBytes: 8, verifiedBytes: 4 }
const run = { key: 'recovery-1', status: 'PARTIAL', version: 7, attempts: 1, path: 'manifests/first.json',
  created: '2026-10-27T00:00:00Z', modified: '2026-10-27T00:01:00Z', error: 'Retained storage unavailable', summary: totals, result: totals }

async function openRecovery(page: Page) {
  await page.goto('/system/backups')
  await page.waitForFunction(() => {
    const root = document.querySelector('#__nuxt') as Element & { __vue_app__?: { $nuxt?: { isHydrating: boolean } } }
    return root?.__vue_app__?.$nuxt?.isHydrating === false
  })
  const welcome = page.getByRole('button', { name: 'Close', exact: true })
  if (await welcome.isVisible()) await welcome.click()
  const collapse = page.getByRole('button', { name: 'Collapse', exact: true })
  if (await collapse.isVisible()) await collapse.click()
}

test.beforeEach(async ({ request }) => {
  await setMocks(request, {
    ListBackups: { system: { backups: { list: [] } } },
    RecoveryRuns: { backups: { objectRuns: { list: [run], lastSuccessful: { ...run, key: 'earlier-success', status: 'COMPLETED' } } } },
    RecoveryItems: { backups: { objectRuns: { run: { items: [{ key: 'item-1', status: 'FAILED', error: 'Retained storage unavailable', verifiedBytes: 0, payload: { key: 'metadata/example/1/content', store: 'content', length: 4 } }] } } } },
    RetryObjectRecovery: { backups: { objectRuns: { retry: { ...run, status: 'PENDING', version: 8 } } } },
    PrepareObjectRecovery: { backups: { objectRuns: { prepareRestoreConfigured: { ...run, status: 'PENDING', version: 0, error: null, result: null } } } },
  })
})
test.afterEach(async ({ request }) => { await resetMocks(request) })

test('shows object recovery failures and retries the observed version without dispatching', async ({ page }, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await openRecovery(page)
  await expect(page.getByText('Database and object recovery', { exact: true })).toBeVisible()
  await page.locator('.text-input-root').filter({ has: page.getByText('Source identity', { exact: true }) }).getByRole('textbox').fill('source')
  await page.locator('.text-input-root').filter({ has: page.getByText('Destination identity', { exact: true }) }).getByRole('textbox').fill('recovery')
  await page.locator('.text-input-root').filter({ has: page.getByText('Scope', { exact: true }) }).getByRole('textbox').fill('content')
  await page.getByRole('button', { name: 'Load object runs', exact: true }).click()
  await expect(page.getByText('Current checkpoints: 1 completed · 0 pending · 0 running · 1 failed')).toBeVisible()
  await expect(page.getByText(/Last successful: earlier-success/)).toBeVisible()
  await page.getByRole('button', { name: 'View objects', exact: true }).click()
  await expect(page.getByText(/metadata\/example\/1\/content/)).toBeVisible()
  const retry = page.waitForRequest(request => request.method() === 'POST' && request.postDataJSON()?.query?.includes('mutation RetryObjectRecovery'))
  await page.getByRole('button', { name: 'Retry', exact: true }).click()
  expect((await retry).postDataJSON().variables).toEqual({ key: 'recovery-1', expectedVersion: 7 })
  await expect(page.getByRole('button', { name: 'Dispatch', exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('object-recovery.png'), fullPage: true })
  expect(errors).toEqual([])
})

test('prepares recovery with the JSON editor mapping and leaves copying for explicit dispatch', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await openRecovery(page)
  await page.getByText('Prepare an object restore', { exact: true }).click()
  for (const [label, value] of [
    ['Request key', 'restore-browser'], ['Restore destination', 'recovery'],
    ['Backup storage', 'retained'], ['Manifest path', 'manifests/first.json'],
  ]) {
    await page.locator('.text-input-root').filter({ has: page.getByText(label, { exact: true }) }).getByRole('textbox').fill(value)
  }
  await expect(page.locator('.json-editor')).toContainText('recovered-content')
  const request = page.waitForRequest(request => request.method() === 'POST' && request.postDataJSON()?.query?.includes('mutation PrepareObjectRecovery'))
  await page.getByRole('button', { name: 'Prepare object restore', exact: true }).click()
  expect((await request).postDataJSON().variables).toEqual({
    requestKey: 'restore-browser', destinationId: 'recovery', backupKey: 'retained', path: 'manifests/first.json',
    stores: [{ store: 'content', storageKey: 'recovered-content' }],
  })
  await expect(page.getByRole('button', { name: 'Dispatch', exact: true })).toBeVisible()
  expect(errors).toEqual([])
})
