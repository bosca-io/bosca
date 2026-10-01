import { describe, expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { mount } from '@vue/test-utils'
import LiveSessionsVisualization from './LiveSessionsVisualization.vue'

const session = {
  id: 'session-123',
  sessionId: 'session-123',
  lat: 40.7,
  lon: -74,
  appId: 'studio',
  appVersion: '2.4.1',
  color: '#5ec5ff',
}

vi.stubGlobal('useLiveSessions', () => ({
  rows: ref([session]),
  sessionCount: ref(1),
  status: ref('open'),
}))

const navigateTo = vi.fn()
vi.stubGlobal('navigateTo', navigateTo)

const AnalyticsVisualizationStub = defineComponent({
  props: ['data'],
  emits: ['pointClick'],
  template: `
    <div class="analytics-visualization">
      <slot name="pointTooltip" :point="data[0]" />
      <button class="session-point" @click="$emit('pointClick', data[0], $event)" />
    </div>
  `,
})

function mountVisualization() {
  return mount(LiveSessionsVisualization, {
    global: {
      stubs: {
        ClientOnly: { template: '<div><slot /></div>' },
        AnalyticsVisualization: AnalyticsVisualizationStub,
      },
    },
  })
}

describe('LiveSessionsVisualization', () => {
  it('shows application, version, and session details in the point tooltip', () => {
    const wrapper = mountVisualization()
    const tooltip = wrapper.get('.session-tooltip')

    expect(tooltip.text()).toContain('Active session')
    expect(tooltip.text()).toContain('studio')
    expect(tooltip.text()).toContain('2.4.1')
    expect(tooltip.text()).toContain('session-123')
    expect(tooltip.text()).toContain('Click dot to view session')
  })

  it('opens Raw Events filtered to the clicked application and session', async () => {
    navigateTo.mockClear()
    const wrapper = mountVisualization()

    await wrapper.get('.session-point').trigger('click')

    expect(navigateTo).toHaveBeenCalledWith({
      path: '/analytics/events',
      query: { appId: 'studio', sessionId: 'session-123' },
    })
  })
})
