import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChangedFileTree from './ChangedFileTree.vue'
import type { ChangedFileTreeEntry } from '~/utils/gitChangedFileTree'

const files: ChangedFileTreeEntry[] = [
  {
    path: 'core/src/test/kotlin/bosca/db/ConnectionTest.kt',
    changeType: 'MODIFY',
    viewed: false,
    commentCount: 2,
  },
  {
    path: 'core/src/test/kotlin/bosca/db/mapper/MapperTest.kt',
    changeType: 'ADD',
    viewed: true,
    commentCount: 0,
  },
  {
    path: 'README.md',
    changeType: 'MODIFY',
    viewed: false,
    commentCount: 0,
  },
]

function mountTree(selectedPath = 'README.md') {
  return mount(ChangedFileTree, {
    props: { files, selectedPath },
    global: { stubs: { Icon: true } },
  })
}

describe('ChangedFileTree', () => {
  beforeEach(() => {
    vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#34d99a' }))
  })

  it('renders path segments as directories and filenames as selectable leaves', () => {
    const wrapper = mountTree()

    expect(wrapper.get('[role="tree"]').attributes('aria-label')).toBe('Changed file tree')
    expect(wrapper.findAll('.changed-file-link')).toHaveLength(3)
    expect(wrapper.get('.changed-file-link[title="core/src/test/kotlin/bosca/db/ConnectionTest.kt"]').text()).toContain('ConnectionTest.kt')
    expect(wrapper.get('.changed-file-link[title="core/src/test/kotlin/bosca/db/ConnectionTest.kt"]').text()).not.toContain('core/src/test')
    expect(wrapper.get('button[aria-label="Collapse core/src/test/kotlin/bosca/db"]').text()).toContain('db')
    expect(wrapper.get('.changed-file-link[title="core/src/test/kotlin/bosca/db/ConnectionTest.kt"] .changed-file-comments').text()).toBe('2')
    expect(wrapper.get('.changed-file-link[title="core/src/test/kotlin/bosca/db/mapper/MapperTest.kt"]').classes()).toContain('viewed')
  })

  it('collapses directories and reopens the ancestors of a newly selected file', async () => {
    const wrapper = mountTree()
    const core = wrapper.get('button[aria-label="Collapse core"]')

    await core.trigger('click')
    expect(wrapper.find('.changed-file-link[title="core/src/test/kotlin/bosca/db/ConnectionTest.kt"]').exists()).toBe(false)
    expect(wrapper.get('button[aria-label="Expand core"]').attributes('aria-label')).toBe('Expand core')

    await wrapper.setProps({ selectedPath: 'core/src/test/kotlin/bosca/db/ConnectionTest.kt' })
    expect(wrapper.get('button[aria-label="Collapse core"]').attributes('aria-label')).toBe('Collapse core')
    expect(wrapper.get('.changed-file-link[title="core/src/test/kotlin/bosca/db/ConnectionTest.kt"]').classes()).toContain('active')
  })

  it('emits the full path when a filename is selected', async () => {
    const wrapper = mountTree()

    await wrapper.get('.changed-file-link[title="core/src/test/kotlin/bosca/db/ConnectionTest.kt"]').trigger('click')

    expect(wrapper.emitted('select')).toEqual([['core/src/test/kotlin/bosca/db/ConnectionTest.kt']])
  })
})
