import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import { extractAttributes, getElementType } from '@bosca/analytics-client-browser'
import DocumentBibleReferences from './DocumentBibleReferences.vue'

vi.mock('@bosca/auth-client-browser', () => ({ useAuth: () => ({ auth: { token: 'test' } }) }))

const fetch = vi.fn(async (_url: string) => [] as unknown[])

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('useGraphQL', () => ({ query: vi.fn(async () => ({ content: { metadata: { bible: { styles: [] } } } })) }))
  vi.stubGlobal('useCurrentSubsystem', () => ({ accent: 'red' }))
  fetch.mockReset().mockImplementation(async (url: string) => url.endsWith('/bibles') ? [] : [{
    usfm: 'JHN.1.1', human: 'John 1:1', content: [],
    chapter: { metadataId: 'bible-1', version: 3, variant: 'preview', usfm: 'JHN.1' },
    attribution: { copyright: 'Preview copyright', citation: '', informationUrl: null },
  }])
  vi.stubGlobal('$fetch', fetch)
})

it('reports the chapter identity returned with the displayed passage and shows its attribution', async () => {
  const wrapper = mount(DocumentBibleReferences, { props: { metadata: 'bible-1', references: [] }, global: { stubs: { Select: true, Icon: true, ScriptureRenderer: true } } })
  await flushPromises()
  await wrapper.setProps({ references: ['JHN.1.1'] })
  await flushPromises()
  const heading = wrapper.get('[data-ba-track="bible-text"]').element
  expect(getElementType(heading)).toBe('bible-text')
  expect(extractAttributes(heading).extras).toMatchObject({
    bible_id: 'bible-1', bible_version: '3', bible_variant: 'preview', bible_usfm: 'JHN.1',
  })
  expect(wrapper.text()).toContain('Preview copyright')
  wrapper.unmount()
})

it('loads saved references when the editor opens', async () => {
  const wrapper = mount(DocumentBibleReferences, { props: { metadata: 'bible-1', references: ['JHN.1.1'] }, global: { stubs: { Select: true, Icon: true, ScriptureRenderer: true } } })
  await flushPromises()
  expect(wrapper.get('[data-ba-track="bible-text"]').attributes('data-ba-bible-usfm')).toBe('JHN.1')
  expect(wrapper.text()).toContain('Preview copyright')
  wrapper.unmount()
})

it('reports a new impression when the same passage is displayed from another Bible', async () => {
  const wrapper = mount(DocumentBibleReferences, { props: { metadata: 'bible-1', references: [] }, global: { stubs: { Select: true, Icon: true, ScriptureRenderer: true } } })
  await flushPromises()
  await wrapper.setProps({ references: ['JHN.1.1'] })
  await flushPromises()
  const firstHeading = wrapper.get('[data-ba-track="bible-text"]').element
  fetch.mockResolvedValueOnce([{
    usfm: 'JHN.1.1', human: 'John 1:1', content: [], attribution: null,
    chapter: { metadataId: 'bible-2', version: 5, variant: 'other', usfm: 'JHN.1' },
  }])
  await wrapper.setProps({ metadata: 'bible-2' })
  await flushPromises()
  const heading = wrapper.get('[data-ba-track="bible-text"]').element
  expect(heading).not.toBe(firstHeading)
  expect(extractAttributes(heading).extras).toMatchObject({ bible_id: 'bible-2', bible_version: '5', bible_variant: 'other' })
  wrapper.unmount()
})
