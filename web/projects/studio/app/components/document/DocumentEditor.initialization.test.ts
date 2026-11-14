import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { markRaw, nextTick, ref } from 'vue'
import * as Y from 'yjs'
import type { HocuspocusProvider } from '@hocuspocus/provider'
import { EditorContent } from '@tiptap/vue-3'
import { getSchema } from '@tiptap/core'
import { prosemirrorJSONToYXmlFragment } from 'y-prosemirror'
import DocumentEditor from './DocumentEditor.vue'
import { isYDocInitializing, newYDoc } from '~/utils/editor/ydoc'
import type { Metadata, Profile } from '~/types/graphql'
import { newExtensions } from '~/utils/editor/extensions'

const state = vi.hoisted(() => ({
  providers: [] as HocuspocusProvider[],
  snapshot: new Uint8Array() as Uint8Array<ArrayBufferLike>,
}))

vi.mock('~/utils/editor/api', () => ({ executeYDocRequest: async () => state.snapshot }))
vi.mock('@bosca/auth-client-browser', () => ({
  useAuth: () => ({ auth: { getToken: async () => '', on: () => () => {} } }),
}))
vi.mock('@hocuspocus/provider', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@hocuspocus/provider')>()
  return {
    ...actual,
    HocuspocusProvider: class extends actual.HocuspocusProvider {
      constructor(configuration: ConstructorParameters<typeof actual.HocuspocusProvider>[0]) {
        const socket = new actual.HocuspocusProviderWebsocket({ url: 'ws://localhost', autoConnect: false })
        super({ ...configuration, websocketProvider: socket })
        this.on('destroy', () => socket.destroy())
        state.providers.push(this)
      }
    },
  }
})

const cleanups: Array<() => void> = []
afterEach(() => {
  cleanups.splice(0).forEach(cleanup => cleanup())
  state.providers.length = 0
})

describe('DocumentEditor initialization', () => {
  it.each([
    { syncBeforeRender: true, dirty: false },
    { syncBeforeRender: false, dirty: false },
    { syncBeforeRender: true, dirty: true },
    { syncBeforeRender: false, dirty: true },
  ])('preserves dirty=$dirty without date fields (sync before render: $syncBeforeRender)', async ({ syncBeforeRender, dirty }) => {
    vi.stubGlobal('useEntitySearch', () => ({ searchEntities: vi.fn().mockResolvedValue([]) }))
    const metadata = {
      __typename: 'Metadata', id: 'item-1', version: 1,
      document: { content: { document: { type: 'doc', content: [] } } },
    } as unknown as Metadata
    const profile = { name: 'Editor' } as Profile
    const stored = new Y.Doc()
    prosemirrorJSONToYXmlFragment(getSchema(newExtensions(metadata, profile)), {
      type: 'doc', content: [
        { type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: 'Saved title' }] },
        { type: 'paragraph' },
      ],
    }, stored.getXmlFragment('default'))
    stored.getText('changes').setAttribute('changes', String(dirty))
    state.snapshot = Y.encodeStateAsUpdate(stored)
    stored.destroy()

    const doc = markRaw(await newYDoc(metadata, ref({}), ref([]), ref([]), profile, '', vi.fn()))
    const wrapper = mount(DocumentEditor, {
      props: { metadata, profile, ydoc: doc, attributes: new Map(), editable: false, onDocument: vi.fn() },
      global: { stubs: { DocumentBubbleMenu: true, DocumentDragHandler: true, Modal: true, Button: true, Select: true } },
    })
    cleanups.push(() => { wrapper.unmount(); doc.destroy() })
    if (!syncBeforeRender) await nextTick()
    state.providers[0]!.synced = true
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 10))
    await wrapper.setProps({ editable: true })

    expect(isYDocInitializing(doc)).toBe(false)
    expect(doc.getText('changes').getAttribute('changes')).toBe(String(dirty))
    expect(wrapper.get('.editor-textarea').text()).toContain('Saved title')

    const editor = wrapper.getComponent(EditorContent).props('editor')
    expect(editor).toBeDefined()
    editor!.commands.insertContent('Edited')
    expect(doc.getText('changes').getAttribute('changes')).toBe('true')
  })
})
