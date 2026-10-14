import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { EditorView } from '@codemirror/view'
import WorkOpsGuideEditor from './WorkOpsGuideEditor.vue'

describe('WorkOps markdown editor', () => {
  it('renders Markdown, emits edits, synchronizes guide changes, and releases the editor', async () => {
    const wrapper = mount(WorkOpsGuideEditor, { props: { modelValue: '# Contract' } })
    expect(wrapper.find('.cm-content').text()).toContain('# Contract')
    const editor = EditorView.findFromDOM(wrapper.find('.cm-editor').element as HTMLElement)
    expect(editor).not.toBeNull()
    editor?.dispatch({ selection: { anchor: 0 } })
    editor?.dispatch({ changes: { from: editor.state.doc.length, insert: '\n\nUse tests.' } })
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['# Contract\n\nUse tests.'])
    await wrapper.setProps({ modelValue: '# Contract\n\nUse tests.' })
    await wrapper.setProps({ modelValue: '# Verification' })
    expect(editor?.state.doc.toString()).toBe('# Verification')
    wrapper.unmount()
    expect(editor?.dom.isConnected).toBe(false)
  })
})
