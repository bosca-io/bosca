import { expect, test } from '@playwright/test'
import { resetMocks, setMocks } from '../fixtures/mock-server'

const repositoryId = '44444444-4444-4444-4444-444444444444'
const boscaSha = 'a'.repeat(40)
const githubSha = 'b'.repeat(40)
const pair = {
  repositoryId, githubRepositoryId: 42, owner: 'bosca', name: 'sync-check',
  webhookSecretName: 'sync-webhook', tokenSecretName: 'sync-token', enabled: true, version: 1,
}

const syncMocks = {
    GetRepo: { git: { repositoryById: {
      id: repositoryId, name: 'Sync check', slug: 'sync-check', description: '', visibility: 'PRIVATE',
      contentType: null, defaultBranch: 'main', archived: false, deleted: false, diskSizeBytes: 1024,
      forkedFromId: null, ownerId: '55555555-5555-5555-5555-555555555555',
      created: '2026-10-01T00:00:00Z', updated: '2026-10-01T00:00:00Z',
      configuration: { deleteBranchOnMerge: false, mergeStrategies: ['MERGE'], requireSignedCommits: false, squashByDefault: false },
    } } },
    OwnerProfile: { profiles: { profile: { slug: 'owner' } } },
    RepoStats: { git: { stats: { branchCount: 1, commitCount: 1, contributorCount: 1, diskSizeBytes: 1024, tagCount: 0 } } },
    Branches: { git: { branches: [{ name: 'main', sha: boscaSha, ahead: 0, behind: 0 }] } },
    Webhooks: { git: { webhooks: [] } },
    BranchProtection: { git: { branchProtectionRules: [] } },
    RepoPerms: { git: { repositoryById: { id: repositoryId, permissions: [] } } },
    PermGroups: { security: { groups: { all: [] } } },
    GitHubSyncPair: { github: { pair }, pipelines: { secrets: [{ name: 'sync-webhook' }, { name: 'sync-token' }] } },
    GitHubSyncUsers: { github: { users: [] } },
    GitHubSyncPrincipals: { security: { principals: { all: [] } } },
    GitHubSyncRefs: { github: { refStates: [{
      ref: 'refs/heads/main', sha: 'c'.repeat(40), synchronized: true,
      boscaSha, githubSha, conflict: true, modified: '2026-10-01T00:00:00Z',
    }] } },
    GitHubSyncDeliveries: { github: { deliveries: [{
      deliveryId: 'delivery-check', event: 'push', principalId: null,
      githubUserId: 99, ignored: false, created: '2026-10-01T00:00:00Z',
    }] } },
    SaveGitHubSyncPair: { github: { savePair: { ...pair, version: 2 } } },
    ReconcileGitHubSyncRefs: { github: { reconcileRefs: [] } },
    PullGitHubSyncRefs: { github: { pullRefs: [] } },
    PushGitHubSyncRefs: { github: { pushRefs: [] } },
    ResolveGitHubSyncRef: { github: { resolveRef: { ref: 'refs/heads/main', conflict: false, boscaSha: githubSha, githubSha } } },
}

test.beforeEach(async ({ request }) => { await setMocks(request, syncMocks) })

test.afterEach(async ({ request }) => { await resetMocks(request) })

test('adds secrets and creates pairing without a repository ID then reviews synchronization', async ({ page, request }, testInfo) => {
  const creationMocks = {
    ...syncMocks,
    GitHubSyncPair: { github: { pair: null }, pipelines: { secrets: [] } },
    SaveGitHubSyncPair: { github: { savePair: { ...pair, version: 0 } } },
  }
  await setMocks(request, creationMocks)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => {
    if (/hydration.*mismatch/i.test(message.text())) errors.push(message.text())
  })
  await page.goto(`/git/repositories/${repositoryId}?tab=Settings&setting=github`)
  await page.waitForFunction(() => {
    const root = document.querySelector('#__nuxt') as Element & { __vue_app__?: { $nuxt?: { isHydrating: boolean } } }
    return root?.__vue_app__?.$nuxt?.isHydrating === false
  })
  await page.getByRole('button', { name: 'Close', exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Sync check' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Repository settings' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Repository settings' }).locator('.tab.active')).toHaveText('GitHub Sync')
  await expect(page.locator('.history-tabs .tab.active')).toHaveText('Branches and tags')
  await expect(page.locator('.select-label', { hasText: /^Repository$/ })).toHaveCount(0)
  await expect(page.getByText('Repository pairing', { exact: true })).toBeVisible()
  const gitServerUrl = process.env.NUXT_PUBLIC_GIT_SERVER_URL || process.env.GIT_SERVER_URL || 'http://localhost:8080'
  await expect(page.locator('.webhook-url')).toHaveText(`${gitServerUrl.replace(/\/+$/, '')}/api/webhooks/github/${repositoryId}`)
  await expect(page.getByText('Conflict', { exact: true })).toBeVisible()
  await expect(page.getByText(boscaSha, { exact: true })).toBeVisible()
  await expect(page.getByText(githubSha, { exact: true })).toBeVisible()
  await page.locator('.text-input-root').filter({ hasText: 'GitHub owner' }).locator('input').fill('bosca')
  await page.locator('.text-input-root').filter({ hasText: 'GitHub repository name' }).locator('input').fill('sync-check')
  for (const kind of ['webhook', 'token']) {
    const secret = `sync-${kind}`
    await setMocks(request, { ...creationMocks, SetGitHubSyncSecret: { pipelines: { setSecret: { name: secret } } } })
    await page.getByRole('button', { name: `Add ${kind} secret`, exact: true }).click()
    const modal = page.locator('.modal-box')
    await modal.locator('.text-input-root').filter({ hasText: 'Secret name' }).locator('input').fill(secret)
    await expect(modal.locator('input[type="password"]')).toBeVisible()
    await modal.locator('input[type="password"]').fill(`fixture-${kind}-credential`)
    await page.screenshot({ path: testInfo.outputPath(`github-${kind}-secret.png`) })
    const secretRequest = page.waitForRequest(request => request.method() === 'POST'
      && request.url().endsWith('/graphql') && request.postDataJSON()?.query?.includes('mutation SetGitHubSyncSecret'))
    await modal.getByRole('button', { name: 'Save secret', exact: true }).click()
    expect((await secretRequest).postDataJSON().variables).toEqual({ name: secret, value: `fixture-${kind}-credential` })
    await expect(modal).toHaveCount(0)
    await expect(page.getByRole('button', { name: `Replace ${kind} secret`, exact: true })).toBeVisible()
  }
  await page.getByRole('checkbox', { name: 'Enable synchronization' }).check()
  await expect(page.locator('.text-input-root').filter({ hasText: 'GitHub repository ID' })).toHaveCount(0)
  const saveRequest = page.waitForRequest(request => {
    if (request.method() !== 'POST' || !request.url().endsWith('/graphql')) return false
    return request.postDataJSON()?.query?.includes('mutation SaveGitHubSyncPair')
  })
  await page.getByRole('button', { name: 'Save pairing', exact: true }).click()
  expect((await saveRequest).postDataJSON().variables.input).toEqual({
    repositoryId, owner: 'bosca', name: 'sync-check', webhookSecretName: 'sync-webhook',
    tokenSecretName: 'sync-token', enabled: true, version: 0,
  })
  await expect(page.getByRole('status')).toContainText('Repository pairing saved.')
  for (const [label, operation, notice] of [
    ['Pull from GitHub', 'PullGitHubSyncRefs', 'Pull finished.'],
    ['Push to GitHub', 'PushGitHubSyncRefs', 'Push finished.'],
  ]) {
    const transfer = page.waitForRequest(request => request.method() === 'POST'
      && request.url().endsWith('/graphql') && request.postDataJSON()?.query?.includes(`mutation ${operation}`))
    await page.getByRole('button', { name: label, exact: true }).click()
    expect((await transfer).postDataJSON().variables).toEqual({ repositoryId })
    await expect(page.getByText(notice, { exact: false })).toBeVisible()
  }
  await page.getByRole('button', { name: 'Reconcile', exact: true }).click()
  await expect(page.getByText('Reconciliation finished.', { exact: false })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('github-sync-desktop.png'), fullPage: true })
  await page.getByText(boscaSha, { exact: true }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('github-sync-history.png'), fullPage: true })
  await page.getByRole('button', { name: 'Resolve', exact: true }).click()
  const resolution = page.locator('.modal-box')
  await expect(resolution.getByRole('button', { name: 'Resolve conflict', exact: true })).toBeDisabled()
  await expect(resolution.getByText(boscaSha, { exact: true })).toBeVisible()
  await expect(resolution.getByText(githubSha, { exact: true })).toBeVisible()
  await resolution.locator('.select-trigger').click()
  await page.locator('.select-option').filter({ hasText: /^GitHub$/ }).click()
  await expect(resolution.getByText('This replaces the Bosca value', { exact: false })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('github-resolution-dialog.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: testInfo.outputPath('github-resolution-mobile.png') })
  await page.setViewportSize({ width: 1280, height: 720 })
  await setMocks(request, { ...creationMocks, GitHubSyncRefs: { github: { refStates: [{
    ref: 'refs/heads/main', sha: githubSha, synchronized: true, boscaSha: githubSha, githubSha,
    conflict: false, modified: '2026-10-01T00:00:00Z',
  }] } } })
  const resolveRequest = page.waitForRequest(request => request.method() === 'POST'
    && request.url().endsWith('/graphql') && request.postDataJSON()?.query?.includes('mutation ResolveGitHubSyncRef'))
  await resolution.getByRole('button', { name: 'Keep GitHub', exact: true }).click()
  expect((await resolveRequest).postDataJSON().variables).toEqual({ input: {
    repositoryId, ref: 'refs/heads/main', resolution: 'GITHUB', expectedBoscaSha: boscaSha, expectedGitHubSha: githubSha,
  } })
  await expect(resolution).toHaveCount(0)
  await expect(page.getByText('In sync', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Resolve', exact: true })).toHaveCount(0)
  const advancedSha = 'd'.repeat(40)
  await setMocks(request, { ...creationMocks, GitHubSyncRefs: { github: { refStates: [{
    ref: 'refs/heads/main', sha: githubSha, synchronized: true, boscaSha: githubSha, githubSha: advancedSha,
    conflict: false, modified: '2026-10-01T00:00:00Z',
  }] } } })
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(page.getByText('Awaiting synchronization', { exact: true })).toBeVisible()
  await setMocks(request, { ...creationMocks, GitHubSyncRefs: { github: { refStates: [{
    ref: 'refs/heads/main', sha: advancedSha, synchronized: true, boscaSha: advancedSha, githubSha: advancedSha,
    conflict: false, modified: '2026-10-01T00:00:00Z',
  }] } } })
  const reconcileRequest = page.waitForRequest(request => request.method() === 'POST'
    && request.url().endsWith('/graphql') && request.postDataJSON()?.query?.includes('mutation ReconcileGitHubSyncRefs'))
  await page.getByRole('button', { name: 'Reconcile', exact: true }).click()
  expect((await reconcileRequest).postDataJSON().variables).toEqual({ repositoryId })
  await expect(page.getByText('In sync', { exact: true })).toBeVisible()
  await expect(page.getByText(advancedSha, { exact: true })).toHaveCount(3)
  await page.getByRole('button', { name: 'Deliveries', exact: true }).click()
  await expect(page.locator('.history-tabs .tab.active')).toHaveText('Deliveries')
  await expect(page.getByText('delivery-check', { exact: true })).toBeVisible()
  await expect(page.getByText('Unattributed', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Reconcile', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Pull from GitHub', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Push to GitHub', exact: true })).toHaveCount(0)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: testInfo.outputPath('github-sync-mobile.png'), fullPage: true })
  await page.setViewportSize({ width: 1280, height: 720 })
  const settings = page.getByRole('navigation', { name: 'Repository settings' })
  for (const [label, setting, empty] of [
    ['Webhooks', 'webhooks', 'No webhooks configured for this repository.'],
    ['Branch Protection', 'protection', 'No branch protection rules for this repository.'],
    ['Permissions', 'permissions', 'No permissions configured. Add a group below to grant access.'],
    ['Utilities', 'utilities', 'Garbage Collection'],
  ]) {
    await settings.getByRole('button', { name: label, exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`setting=${setting}`))
    await expect(settings.locator('.tab.active')).toHaveText(label)
    await expect(page.getByText(empty, { exact: true })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath(`repository-settings-${setting}.png`), fullPage: true })
  }
  await page.goBack()
  await expect(page.getByText('No permissions configured. Add a group below to grant access.', { exact: true })).toBeVisible()
  await page.getByRole('heading', { level: 1, name: 'Sync check' }).click({ button: 'right' })
  await page.locator('.ctx-menu').getByRole('button', { name: 'Settings', exact: true }).hover()
  await page.locator('.ctx-submenu').getByRole('button', { name: 'GitHub User Mappings', exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'GitHub User Mappings' })).toBeVisible()
  await expect(page.getByText('Repository pairing', { exact: true })).toHaveCount(0)
  expect(errors).toEqual([])
})
