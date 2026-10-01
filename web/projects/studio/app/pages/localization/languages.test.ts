import { mount } from '@vue/test-utils'
import { print, type DocumentNode } from 'graphql'
import { describe, expect, it, vi } from 'vitest'
import LanguagesPage from './languages.vue'

let languagesDocument: DocumentNode

vi.mock('@bosca/auth-client-browser', () => ({
  useAuth: () => ({ auth: { getAuthHeaders: vi.fn() } }),
}))

vi.stubGlobal('useGraphQL', () => ({
  mutation: vi.fn().mockResolvedValue({}),
  useAsyncQuery: (_key: string, document: DocumentNode) => {
    languagesDocument = document
    return {
      data: ref({
        languages: {
          all: [
            { tag: 'en', name: 'English', localName: 'English' },
            { tag: 'es', name: 'Spanish', localName: 'Español' },
          ],
        },
      }),
      status: ref('success'),
      refresh: vi.fn(),
    }
  },
}))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#34d99a' }))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)

const stubs = {
  PageShell: { template: '<div><slot name="header" /><slot /></div>' },
  PageHeader: { template: '<header><slot name="actions" /></header>' },
  Button: { template: '<button><slot /></button>' },
  Icon: { template: '<span />' },
  SectionCard: { template: '<section><slot name="right" /><slot /></section>' },
  GlassTable: {
    props: ['rows', 'rowKey'],
    template: `
      <div>
        <div v-for="row in rows" :key="row[rowKey]">
          <slot name="col-tag" :row="row" />
          <slot name="col-name" :row="row" />
          <slot name="actions" :row="row" />
        </div>
      </div>
    `,
  },
  Modal: { template: '<div><slot /><slot name="footer" /></div>' },
  ConfirmModal: { template: '<div><slot /></div>' },
}

describe('Localization locales page', () => {
  it('loads and renders registered locales without loading mappings', () => {
    const wrapper = mount(LanguagesPage, {
      global: {
        stubs,
        mocks: { buildBreadcrumb: (...parts: string[]) => parts },
      },
    })
    const query = print(languagesDocument)

    expect(query).toContain('GetLanguagesList')
    expect(query).not.toContain('resolutionContexts')
    expect(wrapper.text()).toContain('EN')
    expect(wrapper.text()).toContain('English')
    expect(wrapper.text()).not.toContain('Language mappings')
  })
})
