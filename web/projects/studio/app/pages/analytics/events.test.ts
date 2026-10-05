import { describe, expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { mount } from '@vue/test-utils'
import EventsPage from './events.vue'

vi.stubGlobal('useRoute', () => ({
  query: { appId: 'studio', sessionId: 'session-123' },
}))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#5ec5ff' }))
vi.stubGlobal('useGraphQL', () => ({
  useAsyncQuery: () => ({
    data: ref({ analytics: { queries: { executeByKey: { records: [] } } } }),
    status: ref('success'),
    refresh: vi.fn(),
  }),
}))

const TextInputStub = defineComponent({
  name: 'TextInput',
  props: ['modelValue', 'label'],
  template: '<input class="text-input" :data-label="label" :value="modelValue" />',
})

function mountPage() {
  return mount(EventsPage, {
    global: {
      mocks: { buildBreadcrumb: (...parts: string[]) => parts },
      stubs: {
        PageShell: { template: '<div><slot name="header" /><slot /></div>' },
        PageHeader: { template: '<div><slot name="actions" /></div>', props: ['accent', 'breadcrumb', 'title', 'subtitle'] },
        Button: { template: '<button><slot /></button>', props: ['size', 'icon', 'disabled'] },
        DateInput: { template: '<input />', props: ['modelValue', 'label', 'type'] },
        Select: { template: '<select />', props: ['modelValue', 'options', 'label', 'size'] },
        TextInput: TextInputStub,
        SectionCard: { template: '<div><slot /></div>', props: ['title'] },
        GlassTable: { template: '<div />', props: ['columns', 'rows', 'loading', 'emptyText', 'arrow'] },
        Modal: { template: '<div><slot /></div>', props: ['title', 'icon', 'accent', 'width'] },
        Badge: { template: '<span><slot /></span>', props: ['color'] },
      },
    },
  })
}

describe('Raw Events session links', () => {
  it('initializes application and session filters from the URL', () => {
    const wrapper = mountPage()

    expect((wrapper.get('[data-label="App ID"]').element as HTMLInputElement).value).toBe('studio')
    expect((wrapper.get('[data-label="Session"]').element as HTMLInputElement).value).toBe('session-123')
  })
})
