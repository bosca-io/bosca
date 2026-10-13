import { expect, test, type Page } from '@playwright/test'
import { resetMocks, setMocks } from './fixtures/mock-server'

const configuration = { id: 'config-1', name: 'Scripture', provider: 'API_BIBLE', enabled: true, hasApiKey: true }
const attribution = { copyright: 'Publisher copyright', citation: 'Publisher citation', informationUrl: null }
const edition = { id: 'edition-1', kind: 'TEXT', name: 'Translation', nameLocal: 'Translation', abbreviation: 'TR', description: 'Translation description', languages: [{ name: 'English', iso: 'eng' }], attribution }
const recording = { ...edition, id: 'recording-1', name: 'Recording', kind: 'AUDIO' }
const source = { id: 'source-1', configurationId: configuration.id, name: 'Saved translation', kind: 'TEXT', metadataId: 'metadata-1', metadataVersion: 2, variant: 'reader', version: 7, allowedUses: ['DISPLAY'], attribution }
const mocks = {
  GetIntegrationConfigs: { content: { bibleProviders: { configurations: [{ enabled: true, hasApiKey: true }] } } },
  GetBibleProviderConfigurations: { content: { bibleProviders: { providers: ['API_BIBLE'], configurations: [configuration] } } },
  GetBibleProviderSources: { content: { bibleProviders: { sources: [source] } } },
  GetBibleProviderCatalog: { content: { bibleProviders: { catalog: [edition] } } },
  GetBibleProviderAudioTargets: { content: { bibleProviders: { audioTargets: [
    { metadataId: 'metadata-1', version: 1, variant: 'reader', name: 'Translation', enabled: true },
    { metadataId: 'metadata-1', version: 2, variant: 'study', name: 'Translation', enabled: false },
  ] } } },
  SaveBibleProviderConfiguration: { content: { bibleProviders: { saveConfiguration: configuration } } },
  AddProviderBible: { content: { bibleProviders: { addBible: { metadataId: 'metadata-1', version: 1, variant: 'reader' } } } },
  AddProviderAudio: { content: { bibleProviders: { addAudio: { id: 'audio-1' } } } },
  RemoveBibleProviderConfiguration: { content: { bibleProviders: { removeConfiguration: true } } },
}

function waitForOperation(page: Page, name: string) {
  return page.waitForRequest(request => request.url().endsWith('/graphql')
    && request.method() === 'POST' && request.postDataJSON()?.query?.includes(`mutation ${name}(`))
}

test.beforeEach(async ({ request }) => { await setMocks(request, mocks) })
test.afterEach(async ({ request }) => { await resetMocks(request) })

test('edits saved configuration and registers a text Bible through the authenticated Studio page', async ({ page }, testInfo) => {
  await page.goto('/system/integrations')
  const card = page.locator('a[href="/system/integrations/bible"]')
  await expect(card).toContainText('Connected')
  await page.locator('.collab-window').getByRole('button', { name: 'Close', exact: true }).click()
  await card.click()
  await expect(page.getByRole('heading', { level: 1, name: 'Bible Providers' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add Bible', exact: true })).toBeVisible()
  await expect(page.getByText('Saved translation', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'Edit configuration', exact: true }).click()
  const modal = page.locator('.modal-box')
  await expect(modal.locator('input[type="password"]')).toHaveValue('')
  await expect(modal.getByText('Leave blank to keep the saved key.')).toBeVisible()
  const save = waitForOperation(page, 'SaveBibleProviderConfiguration')
  await modal.getByRole('button', { name: 'Save', exact: true }).click()
  expect((await save).postDataJSON().variables.input).toEqual({ id: configuration.id, name: 'Scripture', provider: 'API_BIBLE', enabled: true, apiKey: null })
  await expect(modal).toBeHidden()

  await page.getByRole('button', { name: 'Add Bible', exact: true }).click()
  await expect(modal.getByRole('button', { name: 'Register', exact: true })).toBeDisabled()
  await expect(modal.locator('.checkbox-box.checked')).toHaveCount(0)
  await modal.locator('.text-input-root').filter({ hasText: 'Variant' }).locator('input').fill('reader')
  await modal.locator('.checkbox-row').filter({ hasText: 'Display' }).locator('button').click()
  const add = waitForOperation(page, 'AddProviderBible')
  await modal.getByRole('button', { name: 'Register', exact: true }).click()
  expect((await add).postDataJSON().variables.input).toEqual({ configurationId: configuration.id, providerBibleId: edition.id, variant: 'reader', allowedUses: ['DISPLAY'] })
  await expect(modal).toBeHidden()
  await page.screenshot({ path: testInfo.outputPath('bible-provider-studio-desktop.png'), fullPage: true })
})

test('selects the exact audio target and confirms configuration source removal', async ({ page, request }) => {
  await page.goto('/system/integrations/bible')
  await expect(page.getByRole('button', { name: 'Add Bible', exact: true })).toBeVisible()
  await page.locator('.collab-window').getByRole('button', { name: 'Close', exact: true }).click()
  await setMocks(request, { ...mocks, GetBibleProviderCatalog: { content: { bibleProviders: { catalog: [recording] } } } })
  await page.locator('.select-field').filter({ has: page.locator('.select-label', { hasText: /^Catalog$/ }) }).locator('.select-trigger').click()
  await page.locator('.select-option').filter({ hasText: /^Audio$/ }).click()
  await page.getByRole('button', { name: 'Add audio', exact: true }).click()
  const modal = page.locator('.modal-box')
  await expect(modal.getByRole('button', { name: 'Register', exact: true })).toBeDisabled()
  await modal.locator('.select-trigger').click()
  await page.locator('.select-option').filter({ hasText: 'Translation · version 2 · study (disabled)' }).click()
  await modal.locator('.checkbox-row').filter({ hasText: 'Playback' }).locator('button').click()
  const add = waitForOperation(page, 'AddProviderAudio')
  await modal.getByRole('button', { name: 'Register', exact: true }).click()
  expect((await add).postDataJSON().variables).toEqual({ bibleId: 'metadata-1', version: 2, variant: 'study', input: { configurationId: configuration.id, providerBibleId: recording.id, allowedUses: ['PLAYBACK'] } })
  await expect(modal).toBeHidden()

  await page.getByRole('button', { name: 'Remove configuration', exact: true }).click()
  await expect(modal.getByText('Saved translation · reader · version 2')).toBeVisible()
  await expect(modal.getByRole('button', { name: 'Remove configuration', exact: true })).toBeDisabled()
  await modal.locator('.checkbox-row').filter({ hasText: 'Remove all listed sources' }).locator('button').click()
  const remove = waitForOperation(page, 'RemoveBibleProviderConfiguration')
  await modal.getByRole('button', { name: 'Remove configuration', exact: true }).click()
  expect((await remove).postDataJSON().variables).toEqual({ configurationId: configuration.id, purgeSources: true })
  await expect(modal).toBeHidden()
})
