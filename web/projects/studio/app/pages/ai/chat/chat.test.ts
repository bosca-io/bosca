import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import Chat from './[id].vue'
import KitHarnessControls from '~/components/ai/KitHarnessControls.vue'
import KitHarnessTurn from '~/components/ai/KitHarnessTurn.vue'
import KitHarnessContext from '~/components/ai/KitHarnessContext.vue'

vi.mock('json-editor-vue', () => ({ default: { template: '<div />' } }))

describe('Kit chat page', () => {
  afterEach(() => vi.useRealTimers())

  it('refreshes warm archival and recoverable cleanup without sending activity mutations', async () => {
    vi.useFakeTimers()
    const data = ref({ chatSessions: { session: { id: 'chat', title: 'Conversation', processing: false, messages: [],
      harnessState: { state: 'READY', cancellationRequested: false, failure: null as string | null }, harnessApprovals: [],
    } } })
    const mutation = vi.fn().mockResolvedValue({})
    const refresh = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('useRoute', () => ({ params: { id: 'chat' }, query: {} }))
    vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#123' }))
    vi.stubGlobal('buildBreadcrumb', () => [])
    vi.stubGlobal('useGraphQL', () => ({ mutation,
      useAsyncQuery: () => ({ data, status: ref('success'), refresh }), useSubscription: vi.fn(),
    }))
    const wrapper = mount(Chat, { global: { components: { KitHarnessControls }, stubs: {
      PageShell: { template: '<main><slot /></main>' }, PageHeader: true, Icon: true,
      Button: { template: '<button><slot /></button>' }, KitMessageContent: true, KitAnalyticsResult: true,
      KitHarnessTurn: true, KitHarnessContent: true, KitHarnessContext: true, KitHarnessSettings: true,
      KitGraphQLResult: true, MetadataPickerModal: true, KitContextPicker: true,
    } } })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(3000)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(mutation).not.toHaveBeenCalled()
    await wrapper.findAll('button').find(button => button.text() === 'Archive session')!.trigger('click')
    await flushPromises()
    expect(refresh).toHaveBeenCalledTimes(2)
    data.value.chatSessions.session.harnessState = { state: 'STOPPING', cancellationRequested: false, failure: 'Saved harness storage is full.' }
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('storage is full')
    await vi.advanceTimersByTimeAsync(3000)
    expect(refresh).toHaveBeenCalledTimes(3)
    data.value.chatSessions.session.harnessState = { state: 'STOPPED', cancellationRequested: false, failure: 'Saved harness cleanup failed.' }
    await flushPromises()
    await vi.advanceTimersByTimeAsync(3000)
    expect(refresh).toHaveBeenCalledTimes(4)
    data.value.chatSessions.session.harnessState.failure = null
    await flushPromises()
    await vi.advanceTimersByTimeAsync(6000)
    expect(refresh).toHaveBeenCalledTimes(4)
    expect(mutation).toHaveBeenCalledTimes(1)
    expect(mutation.mock.calls[0]![1]).toEqual({ id: 'chat' })
    wrapper.unmount()
  })

  it('merges snapshots without duplicates and sends permission and cancellation mutations', async () => {
    vi.useFakeTimers()
    const snapshot = (revision: number, text: string) => ({ id: 'turn', author: 'kit', sessionId: 'chat', created: 'now',
      event: { type: 'harness-turn', turnId: 'turn', generation: 1, epoch: 2, revision, status: 'STREAMING', text },
    })
    const storedGraphQL = { data: { title: 'Stored answer', value: null }, errors: [{ message: 'A field was denied' }] }
    const legacy = { id: 'saved-graphql', author: 'kit', sessionId: 'chat', created: 'earlier',
      event: JSON.stringify({ type: 'graphql-result', dataType: '[Metadata]', sdl: 'type Metadata { title: String }', data: storedGraphQL }) }
    const session = { id: 'chat', title: 'Conversation', processing: true, messages: [snapshot(1, 'First'), legacy],
      harnessContext: { sources: [], integrations: ['bosca'], notices: [] },
      harnessState: { state: 'WAITING_FOR_APPROVAL', cancellationRequested: false },
      harnessApprovals: [{ id: 'approval', expires: 'later', request: { toolCall: { title: 'Write file' }, permissions: [{ optionId: 'deny', name: 'Deny', kind: 'reject_once' }] } }],
    }
    const data = ref({ chatSessions: { session } })
    const subscriptions: Record<string, (data: unknown) => void> = {}
    const mutation = vi.fn().mockResolvedValue({})
    const refresh = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('useRoute', () => ({ params: { id: 'chat' }, query: {} }))
    vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#123' }))
    vi.stubGlobal('buildBreadcrumb', () => [])
    vi.stubGlobal('useGraphQL', () => ({ mutation,
      useAsyncQuery: () => ({ data, status: ref('success'), refresh }),
      useSubscription: (document: { definitions: { name: { value: string } }[] }, _: unknown, callback: (data: unknown) => void) => {
        subscriptions[document.definitions[0]!.name.value] = callback
      },
    }))
    const wrapper = mount(Chat, { global: {
      components: { KitHarnessControls, KitHarnessTurn, KitHarnessContext },
      stubs: {
        PageShell: { template: '<main><slot /></main>' }, PageHeader: true, Icon: true,
        Button: { template: '<button><slot /></button>' },
        KitMessageContent: { props: ['text'], template: '<p>{{ text }}</p>' },
        KitAnalyticsResult: true, KitHarnessContent: true,
        KitGraphQLResult: { name: 'KitGraphQLResult', props: ['data', 'dataType', 'sdl'], template: '<div class="graphql-result" />' },
        MetadataPickerModal: { emits: ['select', 'close'], template: '<button data-testid="select-source" @click="$emit(\'select\', { id: \'document\', name: \'Document\' })">Select document</button>' },
        KitContextPicker: { props: ['git'], emits: ['select', 'close'], template: '<button data-testid="select-git" @click="$emit(\'select\', { kind: \'GIT_FILE\', id: \'repository\', ref: \'main\', path: \'plan.md\' })">Select Git file</button>' },
      },
    } })
    subscriptions.AiChatHistory!({ aiChatHistory: snapshot(3, 'Newest') })
    subscriptions.AiChatHistory!({ aiChatHistory: snapshot(2, 'Delayed') })
    await flushPromises()
    expect(wrapper.findAllComponents(KitHarnessTurn)).toHaveLength(1)
    expect(wrapper.text()).toContain('Newest')
    expect(wrapper.text()).not.toContain('Delayed')
    expect(wrapper.getComponent({ name: 'KitGraphQLResult' }).props()).toEqual({ data: storedGraphQL, dataType: '[Metadata]', sdl: 'type Metadata { title: String }' })
    subscriptions.AiChatHistory!({ aiChatHistory: legacy })
    subscriptions.AiChatHistory!({ aiChatHistory: { id: 'null-result', author: 'kit', event: { type: 'graphql-result', data: null } } })
    subscriptions.AiChatHistory!({ aiChatHistory: { id: 'false-result', author: 'kit', event: JSON.stringify({ type: 'graphql-result', data: false }) } })
    await flushPromises()
    expect(wrapper.findAllComponents({ name: 'KitGraphQLResult' }).map(component => component.props('data'))).toEqual([storedGraphQL, null, false])
    expect(mutation).not.toHaveBeenCalled()
    subscriptions.AiChatHistory!({ aiChatHistory: { id: 'legacy', author: 'kit', event: {
      type: 'assistant', parts: [{ text: 'Earlier answer' }],
    } } })
    subscriptions.AiChatHistory!({ aiChatHistory: { id: 'chart', author: 'kit', event: {
      type: 'tool-display', title: 'Earlier chart', visualizationType: 'TABLE', data: [{ count: 3 }], configuration: {},
    } } })
    await flushPromises()
    expect(wrapper.find('.chat-bubble.assistant .chat-bubble-text').text()).toBe('Earlier answer')
    expect(wrapper.get('kit-analytics-result-stub').attributes('name')).toBe('Earlier chart')
    data.value = { chatSessions: { session: { ...session, messages: [snapshot(1, 'Stale poll')] } } }
    await flushPromises()
    expect(wrapper.text()).toContain('Newest')
    await wrapper.findAll('button').find(button => button.text() === 'Deny')!.trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[0]![1]).toEqual({ id: 'chat', approvalId: 'approval', optionId: 'deny' })
    await wrapper.findAll('button').find(button => button.text() === 'Stop')!.trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[1]![1]).toEqual({ id: 'chat' })
    expect(refresh).toHaveBeenCalledTimes(2)
    await wrapper.findAll('button').find(button => button.text() === 'Add content')!.trigger('click')
    await wrapper.get('[data-testid="select-source"]').trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[2]![1]).toEqual({ id: 'chat', source: 'document' })
    expect(mutation.mock.calls[2]![0].definitions[0].name.value).toBe('AddKitContextContent')
    expect(wrapper.find('[data-testid="select-source"]').exists()).toBe(false)
    expect(refresh).toHaveBeenCalledTimes(3)
    await wrapper.findAll('button').find(button => button.text() === 'Add Git file')!.trigger('click')
    await wrapper.get('[data-testid="select-git"]').trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[3]![1]).toEqual({ id: 'chat', source: { kind: 'GIT_FILE', id: 'repository', ref: 'main', path: 'plan.md' } })
    expect(mutation.mock.calls[3]![0].definitions[0].name.value).toBe('AddKitHarnessSource')
    expect(wrapper.find('[data-testid="select-git"]').exists()).toBe(false)
    expect(refresh).toHaveBeenCalledTimes(4)
    wrapper.unmount()
    vi.advanceTimersByTime(5000)
    expect(refresh).toHaveBeenCalledTimes(4)
  })
})
