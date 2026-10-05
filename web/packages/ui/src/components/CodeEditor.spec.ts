import { describe, it, expect, vi } from 'vitest'
import { mount } from '../test-helpers'
import CodeEditor from './CodeEditor.vue'

function mountEditor(props: Record<string, unknown> = {}) {
  return mount(CodeEditor, { props })
}

describe('CodeEditor', () => {
  it('renders textarea', () => {
    const w = mountEditor()
    expect(w.find('textarea.code-editor-el').exists()).toBe(true)
  })

  it('binds v-model value', () => {
    const w = mountEditor({ modelValue: 'const x = 1' })
    expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('const x = 1')
  })

  it('emits update:modelValue on input', async () => {
    const w = mountEditor({ modelValue: '' })
    await w.find('textarea').setValue('hello')
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted![emitted!.length - 1]).toEqual(['hello'])
  })

  it('renders label', () => {
    const w = mountEditor({ label: 'Query' })
    expect(w.find('.code-editor-label').text()).toContain('Query')
  })

  it('shows language badge when language is not text', () => {
    const w = mountEditor({ label: 'Code', language: 'json' })
    expect(w.find('.code-editor-lang').text()).toBe('json')
  })

  it('hides language badge when language is text', () => {
    const w = mountEditor({ label: 'Code', language: 'text' })
    expect(w.find('.code-editor-lang').exists()).toBe(false)
  })

  it('emits blur event', async () => {
    const w = mountEditor()
    await w.find('textarea').trigger('blur')
    expect(w.emitted('blur')).toHaveLength(1)
  })

  it('Tab key inserts 2 spaces', async () => {
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => cb(0))
    const w = mountEditor({ modelValue: 'abc' })
    const textarea = w.find('textarea')
    const el = textarea.element as HTMLTextAreaElement
    el.selectionStart = 1
    el.selectionEnd = 1
    await textarea.trigger('keydown', { key: 'Tab' })
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const last = emitted![emitted!.length - 1]![0] as string
    expect(last).toBe('a  bc')
    vi.unstubAllGlobals()
  })

  it('sets rows attribute', () => {
    const w = mountEditor({ rows: 20 })
    expect(w.find('textarea').attributes('rows')).toBe('20')
  })

  it('applies readonly attribute', () => {
    const w = mountEditor({ readonly: true })
    expect(w.find('textarea').attributes('readonly')).toBeDefined()
  })

  it('sets placeholder', () => {
    const w = mountEditor({ placeholder: 'Enter code…' })
    expect(w.find('textarea').attributes('placeholder')).toBe('Enter code…')
  })
})
