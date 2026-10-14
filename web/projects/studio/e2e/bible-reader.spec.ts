import { test, expect } from '@playwright/test'
import { resetMocks, setMocks } from './fixtures/mock-server'

const bibleId = '44444444-4444-4444-4444-444444444444'
const audioId = '55555555-5555-5555-5555-555555555555'
const reference = { usfm: 'JHN.1', human: 'John 1', humanShort: 'Jn 1' }
const attribution = { copyright: 'Test translation copyright', citation: 'Test translation citation', informationUrl: null }

// A short PCM recording exercises the browser's actual media playback events.
function recording() {
  const data = Buffer.alloc(44 + 32000)
  data.write('RIFF', 0); data.writeUInt32LE(data.length - 8, 4); data.write('WAVEfmt ', 8)
  data.writeUInt32LE(16, 16); data.writeUInt16LE(1, 20); data.writeUInt16LE(1, 22)
  data.writeUInt32LE(8000, 24); data.writeUInt32LE(16000, 28)
  data.writeUInt16LE(2, 32); data.writeUInt16LE(16, 34)
  data.write('data', 36); data.writeUInt32LE(32000, 40)
  return data
}

test.afterEach(async ({ request }) => { await resetMocks(request) })

test('Studio sends Bible impressions and playback interactions through its Analytics client', async ({ page, request }, testInfo) => {
  await setMocks(request, {
    GetBibleReader: { content: { metadata: { id: bibleId, name: 'Test Bible', bible: {
      metadataId: bibleId, version: 2, variant: 'reader', name: 'Test Bible', nameLocal: '', abbreviation: 'TB', abbreviationLocal: '', description: '',
      styles: [], languages: [{ name: 'English', nameLocal: 'English', iso: 'eng', scriptDirection: 'LTR' }],
      audio: [{ id: audioId, name: 'Test recording', nameLocal: '' }],
      books: [{ abbreviation: 'Jn', nameShort: 'John', nameLong: 'John', reference: { usfm: 'JHN', human: 'John', humanShort: 'Jn' }, chapters: [{ reference }] }],
    } } } },
    GetBibleChapter: { content: { metadata: { bible: { chapter: {
      reference, attribution, verses: [], component: { _: 'cc', type: 'DIV', components: [{ _: 't', text: 'In the beginning was the Word.' }] },
    } } } } },
    GetBibleReaderAudio: { content: { metadata: { bible: { audioChapter: {
      audio: { id: audioId }, usfm: 'JHN.1', url: '/api/v1/content/metadata/bible/audio?recordingId=test', attribution,
    } } } } },
  })
  const batches: { context: { session_id: string; device: { installation_id: string } }; events: { type: string; element: { type: string; extras: Record<string, string> } }[] }[] = []
  await page.route('**/api/v1/installation', route => route.fulfill({ json: { id: 'studio-installation' } }))
  await page.route('**/api/v1/events', async route => {
    batches.push(route.request().postDataJSON())
    await route.fulfill({ status: 202, body: '' })
  })
  await page.route('**/api/v1/content/metadata/bible/audio?*', route => route.fulfill({ status: 200, contentType: 'audio/wav', body: recording() }))
  await page.goto(`/cms/bibles/${bibleId}/reader`)
  await expect(page.getByText('In the beginning was the Word.')).toBeVisible()
  await expect(page.getByText('Test translation copyright')).toBeVisible()
  const events = () => batches.flatMap(batch => batch.events).filter(event => event.element.type.startsWith('bible-'))
  await expect.poll(() => events().length, { timeout: 10_000 }).toBe(1)
  expect(events()[0]).toMatchObject({ type: 'impression', element: { type: 'bible-text', extras: {
    bible_id: bibleId, bible_version: '2', bible_variant: 'reader', bible_usfm: 'JHN.1', visible_ms: '1000',
  } } })
  expect(events()[0]!.element.extras.element_text).toBeUndefined()
  const batch = batches.find(batch => batch.events.some(event => event.element.type === 'bible-text'))!
  expect(batch.context.session_id).toBeTruthy()
  expect(batch.context.device.installation_id).toBeTruthy()

  const close = page.locator('.collab-window').getByRole('button', { name: 'Close', exact: true })
  if (await close.isVisible()) await close.click()
  await page.locator('.bible-audio .select-trigger').click()
  await page.locator('.select-option').filter({ hasText: 'Test recording' }).click()
  await expect(page.locator('audio')).toBeVisible()
  expect(events()).toHaveLength(1)
  await page.locator('audio').evaluate((audio: HTMLAudioElement) => audio.play())
  await expect.poll(() => events().length, { timeout: 10_000 }).toBe(2)
  expect(events()[1]).toMatchObject({ type: 'interaction', element: { type: 'bible-audio', extras: {
    bible_id: bibleId, bible_version: '2', bible_variant: 'reader', bible_usfm: 'JHN.1', bible_audio_id: audioId,
  } } })
  await page.screenshot({ path: testInfo.outputPath('bible-reader.png'), fullPage: true })
})
