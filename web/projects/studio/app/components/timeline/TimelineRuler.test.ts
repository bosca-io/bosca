import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TimelineRuler from './TimelineRuler.vue'

describe('TimelineRuler', () => {
  const defaultProps = {
    durationMs: 60000,
    currentTimeMs: 5000,
    pixelsPerMs: 0.1,
  }

  it('renders the ruler container', () => {
    const wrapper = mount(TimelineRuler, { props: defaultProps })
    expect(wrapper.find('.ruler').exists()).toBe(true)
  })

  it('sets track width based on duration and zoom', () => {
    const wrapper = mount(TimelineRuler, { props: defaultProps })
    const track = wrapper.find('.ruler-track')
    expect(track.attributes('style')).toContain('width: 6000px')
  })

  it('positions playhead at currentTimeMs', () => {
    const wrapper = mount(TimelineRuler, { props: defaultProps })
    const playhead = wrapper.find('.ruler-playhead')
    expect(playhead.attributes('style')).toContain('left: 500px')
  })

  it('renders tick marks', () => {
    const wrapper = mount(TimelineRuler, { props: defaultProps })
    const ticks = wrapper.findAll('.ruler-tick')
    expect(ticks.length).toBeGreaterThan(0)
  })

  it('renders major tick labels', () => {
    const wrapper = mount(TimelineRuler, { props: defaultProps })
    const labels = wrapper.findAll('.ruler-label')
    expect(labels.length).toBeGreaterThan(0)
    expect(labels[0]!.text()).toBe('0:00.0')
  })

  it('uses 1-second intervals at high zoom', () => {
    const wrapper = mount(TimelineRuler, {
      props: { ...defaultProps, pixelsPerMs: 0.2 },
    })
    const ticks = wrapper.findAll('.ruler-tick')
    expect(ticks.length).toBe(61)
  })

  it('uses larger intervals at low zoom', () => {
    const wrapper = mount(TimelineRuler, {
      props: { ...defaultProps, pixelsPerMs: 0.001 },
    })
    const ticks = wrapper.findAll('.ruler-tick')
    expect(ticks.length).toBeLessThan(10)
  })

  it('emits seek on click', async () => {
    const wrapper = mount(TimelineRuler, { props: defaultProps })
    const ruler = wrapper.find('.ruler')

    const el = ruler.element as HTMLElement
    Object.defineProperty(el, 'getBoundingClientRect', {
      value: () => ({ left: 0, top: 0, width: 6000, height: 32 }),
    })
    Object.defineProperty(el, 'scrollLeft', { value: 0 })

    await ruler.trigger('click', { clientX: 300 })

    expect(wrapper.emitted('seek')).toBeTruthy()
    const seekMs = (wrapper.emitted('seek')![0] as number[])[0]
    expect(seekMs).toBe(3000)
  })
})
