import { expect, test, type Page } from '@playwright/test'
import { resetMocks, setMocks } from '../fixtures/mock-server'

const repositoryId = '44444444-4444-4444-4444-444444444444'
const versionId = '55555555-5555-5555-5555-555555555555'
const destination = {
  id: '66666666-6666-6666-6666-666666666666', key: 'github', owner: 'bosca-io',
  githubRepository: 'bosca', githubRepositoryId: 42, tagPrefix: '',
  tokenSecretName: 'cli-github', enabled: true, version: 0,
}
const files = [
  'bosca-1.2.3-linux-x86_64.tar.gz', 'bosca-1.2.3-macos-arm64.pkg', 'SHA256SUMS', 'install.sh',
].map((filename, index) => ({ filename, digest: `sha256:${String(index).repeat(64)}`, size: 128 + index }))
const publication = {
  id: '77777777-7777-7777-7777-777777777777', destinationId: destination.id,
  tagName: '1.2.3', commitSha: 'a'.repeat(40), attempts: 1,
  published: '2026-10-01T12:00:00Z', verified: null, verifiedAttempt: null, error: 'Verification failed', files,
}
const mocks = {
  ArtifactRepositorySettings: { artifactsAdmin: { repository: {
    id: repositoryId, name: 'bosca-cli', type: 'raw', namespace: { name: 'bosca' },
  } } },
  ArtifactPublicationSettings: { artifactsAdmin: { publicationDestinations: [] }, pipelines: { secrets: [] } },
  ArtifactPublicationVersions: { artifactsAdmin: { repository: {
    versionCount: 1, versions: [{ id: versionId, version: '1.2.3', completed: true, finalized: false, completedCommitSha: 'a'.repeat(40) }],
  } } },
  ArtifactPublicationHistory: { artifactsAdmin: { publications: [] } },
  ArtifactPublicationVersionFiles: { artifactsAdmin: { version: { id: versionId, version: '1.2.3', completed: true, finalized: false, completedCommitSha: 'a'.repeat(40), blobs: files.map(file => ({ ...file, role: 'file', mediaType: 'application/octet-stream' })) } } },
  SetGitHubSyncSecret: { pipelines: { setSecret: { name: 'cli-github' } } },
  CreateArtifactPublicationDestination: { artifactsAdmin: { createPublicationDestination: destination } },
  PublishArtifactRelease: { artifactsAdmin: { publishRelease: '88888888-8888-8888-8888-888888888888' } },
  UpdateArtifactPublicationDestination: { artifactsAdmin: { updatePublicationDestination: {
    ...destination, enabled: false, version: 1,
  } } },
}

async function openSettings(page: Page) {
  await page.goto(`/artifacts/settings/repositories/${repositoryId}`)
  await page.waitForFunction(() => {
    const root = document.querySelector('#__nuxt') as Element & { __vue_app__?: { $nuxt?: { isHydrating: boolean } } }
    return root?.__vue_app__?.$nuxt?.isHydrating === false
  })
  const welcome = page.getByRole('button', { name: 'Close', exact: true })
  if (await welcome.isVisible()) await welcome.click()
  await expect(page.getByRole('heading', { level: 1, name: 'bosca/bosca-cli Settings' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add destination', exact: true })).toBeEnabled()
}

function operationRequest(page: Page, operation: string) {
  return page.waitForRequest(request => request.method() === 'POST'
    && new URL(request.url()).pathname === '/graphql'
    && request.postDataJSON()?.query?.includes(`mutation ${operation}`))
}

test.beforeEach(async ({ request }) => { await setMocks(request, mocks) })
test.afterEach(async ({ request }) => { await resetMocks(request) })

test('configures CLI release publication and reviews all four stored files', async ({ page, request }, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => {
    if (/hydration.*mismatch/i.test(message.text())) errors.push(message.text())
  })
  await openSettings(page)
  await expect(page.getByRole('heading', { name: 'GitHub Container Registry sync', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Add destination', exact: true }).click()
  const form = page.locator('.modal-box').first()
  for (const [label, value] of Object.entries({
    'Destination name': 'github', 'GitHub owner': 'bosca-io', 'GitHub repository': 'bosca',
    'GitHub repository ID': '42', 'Tag prefix': '',
  })) {
    await form.locator('.text-input-root').filter({ hasText: new RegExp(`^${label}$`) }).locator('input').fill(value)
  }
  await form.getByRole('button', { name: 'Add token secret', exact: true }).click()
  const secret = page.locator('.modal-box').last()
  await secret.locator('.text-input-root').filter({ hasText: 'Secret name' }).locator('input').fill('cli-github')
  await secret.locator('input[type="password"]').fill('fixture-cli-token')
  const saveSecret = operationRequest(page, 'SetGitHubSyncSecret')
  await secret.getByRole('button', { name: 'Save secret', exact: true }).click()
  expect((await saveSecret).postDataJSON().variables).toEqual({ name: 'cli-github', value: 'fixture-cli-token' })
  await expect(page.locator('.modal-box')).toHaveCount(1)
  await expect(form.getByRole('button', { name: 'Replace token secret', exact: true })).toBeVisible()
  await expect(form.locator('.text-input-root').filter({ hasText: 'Tag prefix' }).locator('input')).toHaveValue('')
  await form.getByRole('checkbox', { name: 'Enable synchronization' }).check()
  const create = operationRequest(page, 'CreateArtifactPublicationDestination')
  await form.getByRole('button', { name: 'Save destination', exact: true }).click()
  expect((await create).postDataJSON().variables).toEqual({ input: {
    repositoryId, key: 'github', owner: 'bosca-io', githubRepository: 'bosca',
    githubRepositoryId: 42, tagPrefix: '', tokenSecretName: 'cli-github', enabled: true,
  } })
  await expect(page.locator('.modal-box')).toHaveCount(0)
  await expect(page.getByRole('status')).toHaveText('Release destination saved.')
  await expect(page.getByText('bosca-io/bosca', { exact: true })).toBeVisible()
  await expect(page.getByText('Enabled · Tag: <version> · Secret: cli-github', { exact: true })).toBeVisible()
  await expect(page.getByText('fixture-cli-token', { exact: false })).toHaveCount(0)

  await setMocks(request, { ...mocks, ArtifactPublicationHistory: { artifactsAdmin: { publications: [publication] } } })
  await page.getByRole('button', { name: 'Refresh status', exact: true }).click()
  await expect(page.getByText('github · 1.2.3', { exact: true })).toBeVisible()
  await expect(page.getByText('Verification failed', { exact: true })).toBeVisible()
  await expect(page.getByText('Published:', { exact: false })).toBeVisible()
  for (const file of files) await expect(page.getByText(file.filename, { exact: false })).toBeVisible()

  await setMocks(request, { ...mocks, ArtifactPublicationHistory: { artifactsAdmin: { publications: [{
    ...publication, attempts: 2, error: null, verified: '2026-10-01T13:00:00Z', verifiedAttempt: 2,
  }] } } })
  await page.getByRole('button', { name: 'Refresh status', exact: true }).click()
  await expect(page.getByText('Verified · 2 attempts', { exact: true })).toBeVisible()
  await expect(page.getByText('Verification failed', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Publish release', exact: true }).click()
  const publicationDialog = page.locator('.modal-box')
  await expect(publicationDialog.locator('.text-input-root').filter({ hasText: 'Build commit SHA' }).locator('input')).toHaveValue(publication.commitSha)
  const queuedPublication = operationRequest(page, 'PublishArtifactRelease')
  await publicationDialog.getByRole('button', { name: 'Queue publication', exact: true }).click()
  expect((await queuedPublication).postDataJSON().variables).toEqual({ destinationId: destination.id, versionId, commitSha: publication.commitSha })
  await expect(publicationDialog).toHaveCount(0)
  await expect(page.getByRole('status')).toHaveText('Publication queued.')
  await expect(page.getByRole('link', { name: 'View publication run', exact: true })).toHaveAttribute('href', '/pipelines/runs?runId=88888888-8888-8888-8888-888888888888')
  await page.screenshot({ path: testInfo.outputPath('cli-publication-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('button', { name: 'Edit destination', exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('cli-publication-mobile.png'), fullPage: true })
  await page.getByRole('button', { name: 'Edit destination', exact: true }).click()
  for (const label of ['Destination name', 'GitHub owner', 'GitHub repository', 'GitHub repository ID', 'Tag prefix']) {
    await expect(form.locator('.text-input-root').filter({ hasText: new RegExp(`^${label}$`) }).locator('input')).toBeDisabled()
  }
  await form.getByRole('checkbox', { name: 'Enable synchronization' }).uncheck()
  const update = operationRequest(page, 'UpdateArtifactPublicationDestination')
  await form.getByRole('button', { name: 'Save destination', exact: true }).click()
  expect((await update).postDataJSON().variables).toEqual({
    id: destination.id, version: 0, enabled: false, tokenSecretName: 'cli-github',
  })
  await expect(page.getByText('Disabled · Tag: <version> · Secret: cli-github', { exact: true })).toBeVisible()
  expect(errors).toEqual([])
})

test('keeps destination edits when the provider rejects configuration', async ({ page, request }) => {
  await setMocks(request, { ...mocks, ArtifactPublicationSettings: {
    artifactsAdmin: { publicationDestinations: [destination] }, pipelines: { secrets: [{ name: 'cli-github' }] },
  } })
  await page.route('**/graphql', async route => {
    if (route.request().postDataJSON()?.query?.includes('mutation UpdateArtifactPublicationDestination')) {
      await route.fulfill({ json: { errors: [{ message: 'GitHub repository is inaccessible to this token' }] } })
    } else await route.continue()
  })
  await openSettings(page)
  await page.getByRole('button', { name: 'Edit destination', exact: true }).click()
  const form = page.locator('.modal-box')
  await form.getByRole('button', { name: 'Save destination', exact: true }).click()
  await expect(form.getByRole('alert')).toHaveText('GitHub repository is inaccessible to this token')
  await expect(form.locator('.text-input-root').filter({ hasText: 'Tag prefix' }).locator('input')).toHaveValue('')
  await expect(form.getByRole('checkbox', { name: 'Enable synchronization' })).toBeChecked()
  await expect(page.getByRole('status')).toHaveCount(0)
})

test('recovers completion for existing files and displays a failed repeat with its previous verification', async ({ page, request }) => {
  await setMocks(request, { ...mocks,
    ArtifactPublicationSettings: { artifactsAdmin: { publicationDestinations: [destination] }, pipelines: { secrets: [{ name: 'cli-github' }] } },
    ArtifactPublicationVersions: { artifactsAdmin: { repository: { versionCount: 1, versions: [{ id: versionId, version: '1.2.3', completed: false, finalized: false, completedCommitSha: null }] } } },
    ArtifactPublicationVersionFiles: { artifactsAdmin: { version: { ...mocks.ArtifactPublicationVersionFiles.artifactsAdmin.version, completed: false, completedCommitSha: null } } },
    CompleteArtifactUploads: { artifactsAdmin: { completeVersion: { id: versionId, version: '1.2.3', completed: true, finalized: false, completedCommitSha: publication.commitSha } } },
  })
  await openSettings(page)
  await page.getByRole('button', { name: 'Publish release', exact: true }).click()
  const dialog = page.locator('.modal-box')
  await expect(dialog.getByText('This version has no recorded upload completion.', { exact: false })).toBeVisible()
  for (const file of files) await expect(dialog.getByText(file.filename, { exact: false })).toBeVisible()
  await expect(dialog.getByRole('button', { name: 'Queue publication', exact: true })).toBeDisabled()
  await dialog.locator('.text-input-root').filter({ hasText: 'Build commit SHA' }).locator('input').fill(publication.commitSha)
  await dialog.getByRole('checkbox', { name: 'All files have finished uploading and match this build commit' }).check()
  const completed = operationRequest(page, 'CompleteArtifactUploads')
  await dialog.getByRole('button', { name: 'Confirm completed uploads', exact: true }).click()
  expect((await completed).postDataJSON().variables).toEqual({
    versionId, commitSha: publication.commitSha,
    expectedBlobs: files.map(({ digest, filename }) => ({ versionId, digest, role: 'file', filename, mediaType: 'application/octet-stream' })),
  })
  await expect(dialog.getByRole('button', { name: 'Queue publication', exact: true })).toBeEnabled()
  const queued = operationRequest(page, 'PublishArtifactRelease')
  await dialog.getByRole('button', { name: 'Queue publication', exact: true }).click()
  expect((await queued).postDataJSON().variables).toEqual({ destinationId: destination.id, versionId, commitSha: publication.commitSha })
  await expect(dialog).toHaveCount(0)
  await setMocks(request, { ...mocks, ArtifactPublicationHistory: { artifactsAdmin: { publications: [{
    ...publication, attempts: 2, verifiedAttempt: 1, verified: '2026-10-01T13:00:00Z', error: null, modified: '2026-10-01T12:59:00Z',
  }] } } })
  await page.getByRole('button', { name: 'Refresh status', exact: true }).click()
  await expect(page.getByText('Published · awaiting verification · 2 attempts', { exact: true })).toBeVisible()
  await expect(page.getByText('Last verified:', { exact: false })).toBeVisible()
  await setMocks(request, { ...mocks, ArtifactPublicationHistory: { artifactsAdmin: { publications: [{
    ...publication, attempts: 2, verifiedAttempt: 1, verified: '2026-10-01T13:00:00Z', error: 'GitHub asset content differs', modified: '2026-10-02T13:00:00Z',
  }] } } })
  await page.getByRole('button', { name: 'Refresh status', exact: true }).click()
  await expect(page.getByText('Failed · 2 attempts', { exact: true })).toBeVisible()
  await expect(page.getByText('Last verified:', { exact: false })).toBeVisible()
  await expect(page.getByText('GitHub asset content differs', { exact: true })).toBeVisible()
})
