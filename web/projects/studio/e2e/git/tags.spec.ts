import { expect, test } from '@playwright/test'
import { resetMocks, setMocks } from '../fixtures/mock-server'

const repositoryId = '44444444-4444-4444-4444-444444444444'
const names = ['v2', 'v10', 'v1', ...Array.from({ length: 997 }, (_, index) => `older-${index}`)]
const tags = names.map(name => ({
  name, sha: 'a'.repeat(40), targetSha: null, isAnnotated: false,
  message: null, taggerName: null, taggerEmail: null,
}))

test.afterEach(async ({ request }) => { await resetMocks(request) })

test('tag lists and pipeline dropdowns preserve API order with bounded pages and search', async ({ page, request }) => {
  const errors: string[] = []
  const tagRequests: Record<string, unknown>[] = []
  const inputRefs: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await setMocks(request, {
    GetRepo: { git: { repositoryById: {
      id: repositoryId, name: 'Tag pages', slug: 'tag-pages', description: '', visibility: 'PRIVATE',
      defaultBranch: 'main', archived: false, deleted: false, diskSizeBytes: 1024,
      ownerId: '11111111-1111-1111-1111-111111111111', contentType: null, forkedFromId: null,
      created: '2026-10-01T00:00:00Z', updated: '2026-10-01T00:00:00Z',
      configuration: { deleteBranchOnMerge: false, mergeStrategies: ['MERGE'], requireSignedCommits: false, squashByDefault: false },
    } } },
    OwnerProfile: { profiles: { profile: { slug: 'admin' } } },
    RepoStats: { git: { stats: null } },
    Branches: { git: { branches: [{ name: 'main', sha: 'a'.repeat(40), ahead: 0, behind: 0 }] } },
    Tree: { git: { tree: [] } },
    ReposForPipelines: { git: { repositories: [{ id: repositoryId, name: 'Tag pages', canExecute: true }] } },
    Pipelines: { git: { pipelines: [{
      id: 'pipeline', repositoryId, name: 'Release', filePath: '.bosca/release.yaml', triggerTypes: ['MANUAL'], runs: [],
    }] } },
  })
  await page.route('**/graphql', async route => {
    const body = route.request().postDataJSON()
    const operation = body.query.match(/\bquery\s+(\w+)/)?.[1]
    if (operation === 'Tags' || operation === 'PipelineRunRefs') {
      const variables = body.variables
      tagRequests.push(variables)
      const filtered = tags.filter(tag => tag.name.includes(variables.query ?? ''))
      await route.fulfill({ json: { data: { git: {
        tags: filtered.slice(variables.offset, variables.offset + variables.limit),
        ...(variables.includeBranches ? { branches: [{ name: 'main' }] } : {}),
      } } } })
    } else if (operation === 'PipelineRunInputs') {
      inputRefs.push(body.variables.ref)
      await route.fulfill({ json: { data: { git: { pipelineInputs: [] } } } })
    } else await route.continue()
  })
  await page.goto(`/git/repositories/${repositoryId}?tab=Tags`)
  await page.waitForFunction(() => {
    const root = document.querySelector('#__nuxt') as Element & { __vue_app__?: { $nuxt?: { isHydrating: boolean } } }
    return root?.__vue_app__?.$nuxt?.isHydrating === false
  })
  const close = page.getByRole('button', { name: 'Close', exact: true })
  if (await close.isVisible()) await close.click()
  await expect(page.locator('.branch-name')).toHaveText(names.slice(0, 25))
  await page.getByRole('button', { name: 'Older tags', exact: true }).click()
  await expect(page.locator('.branch-name')).toHaveText(names.slice(25, 50))
  await page.getByRole('button', { name: 'Newer tags', exact: true }).click()
  await expect(page.locator('.branch-name')).toHaveText(names.slice(0, 25))

  await page.goto('/git/pipelines')
  await page.getByRole('button', { name: 'Run', exact: true }).click()
  const picker = page.locator('.select-field').filter({ has: page.locator('.select-label', { hasText: /^Ref$/ }) })
  await expect(picker.locator('.select-display')).toHaveText('v2 (tag)')
  await picker.locator('.select-trigger').click()
  const dropdown = page.locator('.select-dropdown')
  await expect(dropdown.locator('.select-option')).toHaveCount(26)
  await expect(dropdown.locator('.select-option').first()).toHaveText('v2 (tag)')
  await picker.locator('input').press('Tab')
  await expect(dropdown.getByRole('button', { name: 'Next', exact: true })).toBeFocused()
  await page.keyboard.press('Shift+Tab')
  await expect(picker.locator('input')).toBeFocused()
  await page.keyboard.press('Tab')
  await page.keyboard.press('Escape')
  await expect(dropdown).not.toBeVisible()
  await expect(picker.locator('.select-trigger')).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(dropdown.locator('.select-option')).toHaveCount(26)
  await dropdown.getByRole('button', { name: 'Next', exact: true }).click()
  await expect(dropdown.locator('.select-option')).toHaveText(names.slice(25, 50).map(name => `${name} (tag)`))
  await dropdown.getByRole('button', { name: 'Previous', exact: true }).click()
  await expect(dropdown.locator('.select-option').first()).toHaveText('v2 (tag)')
  await picker.locator('input').fill('older-996')
  await expect(dropdown.locator('.select-option')).toHaveText(['older-996 (tag)'])
  await picker.locator('input').press('Enter')
  await expect(picker.locator('.select-display')).toHaveText('older-996 (tag)')
  await expect.poll(() => inputRefs.at(-1)).toBe('refs/tags/older-996')
  expect(tagRequests.length).toBeGreaterThan(5)
  expect(tagRequests.every(variables => variables.limit === 26 && typeof variables.offset === 'number')).toBe(true)
  expect(errors).toEqual([])
})
