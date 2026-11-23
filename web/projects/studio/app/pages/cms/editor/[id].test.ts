import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, ref, shallowRef, Suspense } from 'vue'
import { getOperationAST, print, type DocumentNode } from 'graphql'
import * as Y from 'yjs'
import AttributesSidebar from '~/components/attributes/AttributesSidebar.vue'
import CommonSlugEditor from '~/components/common/CommonSlugEditor.vue'
import { getYDocSlugs, trackYDocChanges } from '~/utils/editor/ydoc'
import EditorPage from './[id].vue'

vi.mock('~/utils/editor/attributes', () => ({ applyAttributes: vi.fn() }))
vi.mock('~/components/document/DocumentEditor.vue', () => ({
  default: defineComponent({
    inheritAttrs: false,
    props: {
      metadata: { type: Object, required: true },
      ydoc: { type: Y.Doc, required: true },
      onTitleUpdate: { type: Function, required: true },
    },
    setup(props, { expose }) {
      const title = ref(props.metadata.document.title)
      props.onTitleUpdate(title.value)
      expose({ getDocument: () => ({ title: title.value }) })
      return {
        title,
        updateTitle(event: Event) {
          title.value = (event.target as HTMLInputElement).value
          props.ydoc.getMap('testEditor').set('title', title.value)
          props.onTitleUpdate(title.value)
        },
      }
    },
    template: '<input class="document-title" :value="title" @input="updateTitle">',
  }),
}))

function newMetadata() {
  return {
    __typename: 'Metadata',
    id: 'doc-1',
    version: 1,
    name: 'New Document',
    slug: 'new-document-1234',
    document: { title: 'New Document' },
    workflow: { state: 'draft' },
  }
}

let persisted: ReturnType<typeof newMetadata>
let mutationFailure: { operation: string; message: string } | undefined
let ydoc: Y.Doc
let wrapper: VueWrapper
const data = shallowRef()
const refresh = vi.fn(async () => {
  data.value = { content: { metadata: { ...persisted } } }
})
const mutation = vi.fn<(document: DocumentNode, variables: Record<string, unknown>) => Promise<unknown>>()

vi.stubGlobal('useGraphQL', () => ({
  mutation,
  useAsyncQuery: (key: string) => key === 'editor-metadata'
    ? { data, status: ref('success'), refresh }
    : { data: ref(null) },
  useSubscription: vi.fn(),
}))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '' }))
vi.stubGlobal('useRoute', () => ({ params: { id: 'doc-1' } }))
vi.stubGlobal('usePersonas', () => ({ isAdmin: ref(true), hasGroup: vi.fn(), load: vi.fn() }))
vi.stubGlobal('useServerFeatures', () => ({ commentsEnabled: ref(false), load: vi.fn() }))
vi.stubGlobal('usePreviewableUrl', () => ({ canPreview: ref(false), load: vi.fn() }))
vi.stubGlobal('useJobCancel', () => ({ requestCancel: vi.fn(), cancellingJobId: ref(null) }))
vi.stubGlobal('useUploader', () => ({}))
vi.stubGlobal('useCollaborationAndAttributes', () => ({
  ydoc: shallowRef(ydoc),
  ready: ref(true),
  attributes: new Map(),
  rawAttributes: ref({}),
  reloadParentCollections: vi.fn(),
  reloadRelationships: vi.fn(),
}))

const stubs = {
  PageShell: { template: '<div><slot name="header" /><slot /></div>' },
  PageHeader: { template: '<header><slot name="actions" /></header>' },
  Button: { template: '<button><slot /></button>' },
  ClientOnly: { template: '<div><slot /></div>' },
  Modal: { template: '<div class="slug-modal"><slot /><slot name="footer" /></div>' },
  SlugInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input class="modal-slug" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">',
  },
  AttributesSidebar,
  CommonSlugEditor,
  AttributesPublishModal: true,
  Badge: { template: '<span><slot /></span>' },
  MetadataLanguageMenu: true,
  OverflowMenu: true,
}

async function mountEditor() {
  await refresh()
  const Host = defineComponent({
    render: () => h(Suspense, null, { default: () => h(EditorPage) }),
  })
  wrapper = mount(Host, { global: { stubs, mocks: { buildBreadcrumb: vi.fn() } } })
  await flushPromises()
}

async function clickButton(label: string, container = 'header') {
  const button = wrapper.get(container).findAll('button').find(b => b.text() === label)
  if (!button) throw new Error(`Missing ${label} button`)
  await button.trigger('click')
  await flushPromises()
}

function sidebarSlug() {
  return wrapper.get<HTMLInputElement>('.slug-input').element.value
}

function operations() {
  return mutation.mock.calls.map(([document]) => getOperationAST(document)?.name?.value)
}

beforeEach(() => {
  persisted = newMetadata()
  mutationFailure = undefined
  ydoc = new Y.Doc()
  trackYDocChanges(ydoc)
  mutation.mockReset()
  mutation.mockImplementation(async (document, variables) => {
    const operation = getOperationAST(document)?.name?.value
    if (mutationFailure && mutationFailure.operation === operation) {
      throw new Error(mutationFailure.message)
    }
    switch (operation) {
      case 'SetMetadataSlug':
        expect(print(document)).toContain('setMetadataSlug(id: $id, slug: $slug)')
        persisted.slug = String(variables.slug)
        return { content: { metadata: { setMetadataSlug: persisted.slug } } }
      case 'SaveDocument':
        persisted.document = variables.document as typeof persisted.document
        break
      case 'SetMetadataName':
        persisted.name = String(variables.name)
        break
      case 'BeginTransition':
        persisted.workflow = { state: String(variables.state) }
        break
    }
    return {}
  })
})

afterEach(() => {
  wrapper?.unmount()
  ydoc.destroy()
})

describe('Studio document slug', () => {
  it('shows the same slug in the sidebar and dialog, then saves it before publishing', async () => {
    await mountEditor()
    await wrapper.get('.document-title').setValue('My New Article!')
    expect(sidebarSlug()).toBe('my-new-article')

    await wrapper.get('.slug-input').trigger('click')
    expect(wrapper.get<HTMLInputElement>('.modal-slug').element.value).toBe('my-new-article')
    await clickButton('Cancel', '.slug-modal')

    await clickButton('Publish')
    expect(operations()).toContain('BeginTransition')
    expect(operations().indexOf('SaveDocument')).toBeLessThan(operations().indexOf('SetMetadataSlug'))
    expect(operations().indexOf('SetMetadataSlug')).toBeLessThan(operations().indexOf('BeginTransition'))
    expect(persisted.slug).toBe('my-new-article')
    expect(persisted.workflow.state).toBe('published')

    await refresh()
    await flushPromises()
    expect(sidebarSlug()).toBe('my-new-article')
    await wrapper.get('.slug-input').trigger('click')
    expect(wrapper.get<HTMLInputElement>('.modal-slug').element.value).toBe('my-new-article')
  })

  it.each(['new-document', 'new-document-1234', 'new-document1795435590', 'new-documentdoc-1'])(
    'replaces the placeholder %s on first publish of a previously saved draft',
    async (slug) => {
      persisted.slug = slug
      persisted.document.title = persisted.name = 'Previously Saved Article'
      await mountEditor()

      expect(sidebarSlug()).toBe('previously-saved-article')
      await clickButton('Publish')
      expect(persisted.slug).toBe('previously-saved-article')
      expect(persisted.workflow.state).toBe('published')
    },
  )

  it('preserves an explicitly chosen slug that matches the placeholder format', async () => {
    persisted.document.title = persisted.name = 'Previously Saved Article'
    getYDocSlugs(ydoc).set('manual-slug', 'true')
    await mountEditor()

    expect(sidebarSlug()).toBe('new-document-1234')
    await clickButton('Publish')
    expect(operations()).toEqual(['BeginTransition'])
  })

  it('preserves the numbered placeholder while the title is still New Document', async () => {
    await mountEditor()

    expect(sidebarSlug()).toBe('new-document-1234')
    await clickButton('Save')
    expect(operations()).not.toContain('SetMetadataSlug')
  })

  it('saves the generated slug with the regular Save action', async () => {
    await mountEditor()
    await wrapper.get('.document-title').setValue('My Draft')
    await clickButton('Save')

    expect(persisted.slug).toBe('my-draft')
    expect(persisted.workflow.state).toBe('draft')
    expect(wrapper.get('.save-status').text()).toBe('Saved')
  })

  it('leaves the previous slug in place when the document save fails', async () => {
    mutationFailure = { operation: 'SaveDocument', message: 'Content rejected' }
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      await mountEditor()
      await wrapper.get('.document-title').setValue('New Draft Title')
      await clickButton('Publish')

      expect(operations()).toEqual(['SaveDocument'])
      expect(persisted.document.title).toBe('New Document')
      expect(persisted.slug).toBe('new-document-1234')
      expect(wrapper.get('.save-status').text()).toContain('Content rejected')
    } finally {
      errorLog.mockRestore()
    }
  })

  it('keeps a rejected slug pending across reload so publishing can retry the saved title', async () => {
    persisted.slug = 'previous-title'
    persisted.document.title = persisted.name = 'Previous Title'
    mutationFailure = { operation: 'SetMetadataSlug', message: 'Slug already exists' }
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      await mountEditor()
      await wrapper.get('.document-title').setValue('Taken Slug')
      await clickButton('Publish')

      expect(operations()).not.toContain('BeginTransition')
      expect(persisted.document.title).toBe('Taken Slug')
      expect(persisted.slug).toBe('previous-title')
      expect(sidebarSlug()).toBe('taken-slug')
      expect(ydoc.getText('changes').getAttribute('changes')).toBe('true')
      expect(wrapper.get('.save-status').text()).toContain('Slug already exists')

      wrapper.unmount()
      const savedCollaboration = Y.encodeStateAsUpdate(ydoc)
      ydoc.destroy()
      ydoc = new Y.Doc()
      Y.applyUpdate(ydoc, savedCollaboration)
      trackYDocChanges(ydoc)
      mutationFailure = undefined
      await mountEditor()

      expect(sidebarSlug()).toBe('taken-slug')
      await clickButton('Publish')
      expect(persisted.workflow.state).toBe('published')
      expect(persisted.slug).toBe('taken-slug')
      expect(getYDocSlugs(ydoc).has('pending-title')).toBe(false)
    } finally {
      errorLog.mockRestore()
    }
  })

  it('keeps a manually saved slug when the draft title changes again', async () => {
    await mountEditor()
    await wrapper.get('.document-title').setValue('My New Article')
    await wrapper.get('.slug-input').trigger('click')
    await wrapper.get('.modal-slug').setValue('custom-url')
    await clickButton('Save', '.slug-modal')
    expect(ydoc.getText('changes').getAttribute('changes')).toBe('true')
    await wrapper.get('.document-title').setValue('My Renamed Article')
    expect(sidebarSlug()).toBe('custom-url')

    await clickButton('Publish')
    expect(persisted.slug).toBe('custom-url')
    expect(operations().filter(operation => operation === 'SetMetadataSlug')).toHaveLength(1)
    expect(persisted.workflow.state).toBe('published')
  })

  it('keeps a published document clean when only its slug is edited', async () => {
    persisted.workflow.state = 'published'
    await mountEditor()
    await wrapper.get('.slug-input').trigger('click')
    await wrapper.get('.modal-slug').setValue('custom-url')
    await clickButton('Save', '.slug-modal')

    expect(sidebarSlug()).toBe('custom-url')
    expect(operations()).toEqual(['SetMetadataSlug'])
    expect(getYDocSlugs(ydoc).get('saved-slug')).toBe('custom-url')
    expect(ydoc.getText('changes').getAttribute('changes')).not.toBe('true')
    expect(wrapper.find('.save-status').exists()).toBe(false)
  })

  it('refreshes the sidebar and untouched dialog input when a collaborator saves a slug', async () => {
    await mountEditor()
    await wrapper.get('.slug-input').trigger('click')
    const peer = new Y.Doc()
    try {
      const peerSlugs = getYDocSlugs(peer)
      persisted.slug = 'custom-url'
      peer.transact(() => {
        peerSlugs.set('manual-slug', 'true')
        peerSlugs.set('saved-slug', persisted.slug)
      })
      Y.applyUpdate(ydoc, Y.encodeStateAsUpdate(peer))
      await flushPromises()

      expect(sidebarSlug()).toBe('custom-url')
      expect(wrapper.get<HTMLInputElement>('.modal-slug').element.value).toBe('custom-url')
      expect(ydoc.getText('changes').getAttribute('changes')).not.toBe('true')

      await wrapper.get('.modal-slug').setValue('my-unsaved-slug')
      persisted.slug = 'new-collaborator-slug'
      peerSlugs.set('saved-slug', persisted.slug)
      Y.applyUpdate(ydoc, Y.encodeStateAsUpdate(peer))
      await flushPromises()

      expect(sidebarSlug()).toBe('new-collaborator-slug')
      expect(wrapper.get<HTMLInputElement>('.modal-slug').element.value).toBe('my-unsaved-slug')
    } finally {
      peer.destroy()
    }
  })

  it('preserves the slug when editing a published document', async () => {
    persisted.workflow.state = 'published'
    persisted.slug = 'published-url'
    await mountEditor()
    await wrapper.get('.document-title').setValue('My Renamed Article')
    await clickButton('Save')

    expect(sidebarSlug()).toBe('published-url')
    expect(operations()).not.toContain('SetMetadataSlug')
  })
})
