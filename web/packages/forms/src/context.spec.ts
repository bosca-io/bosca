import { describe, it, expect } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { provideBoscaFormContext, useBoscaFormContext, BoscaFormContextKey } from './context'

describe('BoscaFormContext provide/inject', () => {
  it('injects the context provided by an ancestor', () => {
    const ctx = { apiUrl: 'https://api.test.com', getToken: () => 'tok' }
    let injected: ReturnType<typeof useBoscaFormContext> = null

    const Child = defineComponent({
      setup() {
        injected = useBoscaFormContext()
        return () => h('div', 'child')
      },
    })

    const Parent = defineComponent({
      setup() {
        provideBoscaFormContext(ctx)
        return () => h(Child)
      },
    })

    mount(Parent)
    expect(injected!.apiUrl).toBe('https://api.test.com')
    expect(injected!.getToken()).toBe('tok')
  })

  it('returns null when no ancestor provides the context', () => {
    let injected: ReturnType<typeof useBoscaFormContext> = 'not-called' as any

    const Standalone = defineComponent({
      setup() {
        injected = useBoscaFormContext()
        return () => h('div', 'standalone')
      },
    })

    mount(Standalone)
    expect(injected).toBeNull()
  })

  it('BoscaFormContextKey is a Symbol', () => {
    expect(typeof BoscaFormContextKey).toBe('symbol')
  })
})
