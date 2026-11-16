import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import * as Y from 'yjs'
import { print } from 'graphql'
import TextInput from '../../../../../packages/ui/src/components/TextInput.vue'
import Button from '../../../../../packages/ui/src/components/Button.vue'
import AttributesFile from './AttributesFile.vue'
import AttributesFileSelectorModal from './AttributesFileSelectorModal.vue'
import { AttributeState } from '~/utils/editor/attribute'
import { detectMediaType } from '~/composables/useMediaType'
import { trackYDocChanges } from '~/utils/editor/ydoc'
import type { Metadata, TemplateAttribute } from '~/types/graphql'
import type { Uploader } from '~/utils/editor/uploader'

const asset = {
  id: 'youtube-1',
  name: 'YouTube Video (dQw4w9WgXcQ)',
  attributes: { type: 'YouTube Video', 'youtube.id': 'dQw4w9WgXcQ' },
  content: { type: 'bosca/x-youtube-video' },
}
const mutation = vi.fn()
const query = vi.fn()
vi.stubGlobal('useGraphQL', () => ({ query, mutation, useSubscription: vi.fn() }))
vi.stubGlobal('useLanguage', () => ({ current: ref({ tag: 'es' }) }))

const cleanups: Array<() => void> = []
afterEach(() => cleanups.splice(0).forEach(cleanup => cleanup()))

function openFile(doc: Y.Doc) {
  const attribute = new AttributeState(doc, 'Metadata', 'document-1', ref({}), ref([]), ref([]), {
    key: 'video.featured', name: 'Video', type: 'METADATA', list: false,
    configuration: { allowYouTube: true, relationship: 'video.featured' },
  } as TemplateAttribute)
  const wrapper = mount(AttributesFile, {
    attachTo: document.body,
    props: { item: null, attribute, uploader: {} as Uploader, editable: true, toolsEnabled: false, onRunTool: vi.fn() },
    global: {
      components: { AttributesFileSelectorModal, TextInput, Button },
      stubs: {
        ClientOnly: { template: '<slot />' },
        Modal: { template: '<div><slot /></div>' },
        AttributesTitlebar: true,
        Icon: true,
        Badge: true,
        SMediaPlayer: { template: '<div class="player" />', props: ['item'] },
        LazyTimeEventsEditor: true,
      },
    },
  })
  cleanups.push(() => { wrapper.unmount(); attribute.dispose() })
  return { attribute, wrapper }
}

beforeEach(() => {
  vi.clearAllMocks()
  mutation.mockResolvedValue({ content: { metadata: { add: asset } } })
  query.mockImplementation(async (document) => print(document).includes('SearchFileAssets')
    ? { search: { search: { documents: [{ metadata: asset }], estimatedHits: 1 } } }
    : { content: { metadata: { ...asset, public: true, publicContent: true, workflow: { state: 'published' } } } })
})

describe('YouTube file attachments', () => {
  it.each(['create', 'select'])('preserves a YouTube asset through %s, Yjs serialization, and reopening', async (action) => {
    const doc = new Y.Doc()
    const restored = new Y.Doc()
    // Document cleanup follows component cleanup so observers can be removed first.
    const { attribute, wrapper } = openFile(doc)
    cleanups.push(() => doc.destroy())
    trackYDocChanges(doc)
    await wrapper.get('.file-dropzone').trigger('click')
    await flushPromises()
    if (action === 'create') {
      await wrapper.get('input[placeholder="YouTube URL or ID"]').setValue('https://youtu.be/dQw4w9WgXcQ')
      const button = wrapper.get('.youtube-form button').element as HTMLButtonElement
      expect(button.type).toBe('submit')
      expect(button.disabled).toBe(false)
      button.click()
    } else {
      await wrapper.get('.asset-row').trigger('click')
    }
    await flushPromises()

    expect(attribute.metadata).toEqual({
      id: asset.id, name: asset.name, contentType: asset.content.type,
      relationship: 'video.featured', attributes: { sort: 0, ...asset.attributes },
    })
    expect(doc.getText('changes').getAttribute('changes')).toBe('true')
    expect(wrapper.findComponent(AttributesFileSelectorModal).exists()).toBe(false)
    const player = (wrapper.getComponent('.player') as VueWrapper).props() as { item: Metadata }
    expect(detectMediaType(player.item).youtubeId).toBe('dQw4w9WgXcQ')
    expect(mutation).toHaveBeenCalledTimes(action === 'create' ? 1 : 0)

    Y.applyUpdate(restored, Y.encodeStateAsUpdate(doc))
    const reopened = openFile(restored)
    cleanups.push(() => restored.destroy())
    expect(reopened.attribute.metadata).toEqual(attribute.metadata)
    const reopenedPlayer = (reopened.wrapper.getComponent('.player') as VueWrapper).props() as { item: Metadata }
    expect(detectMediaType(reopenedPlayer.item).youtubeId).toBe('dQw4w9WgXcQ')
  })
})
