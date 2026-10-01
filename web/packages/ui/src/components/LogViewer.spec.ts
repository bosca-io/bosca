import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import LogViewer, { type LogLine } from './LogViewer.vue'

const SAMPLE: LogLine[] = [
  { t: '16:42:01.024', lvl: 'info', msg: 'starting service' },
  { t: '16:42:01.408', lvl: 'info', msg: 'listening on :8080' },
  { t: '16:42:18.401', lvl: 'warn', msg: 'slow query took 482ms' },
  { t: '16:42:29.502', lvl: 'error', msg: 'circuit breaker tripped' },
]

describe('LogViewer', () => {
  it('renders every line by default', () => {
    const w = mount(LogViewer, { props: { lines: SAMPLE } })
    expect(w.findAll('.log-line')).toHaveLength(SAMPLE.length)
  })

  it('applies the level class for tone coloring', () => {
    const w = mount(LogViewer, { props: { lines: SAMPLE } })
    const lines = w.findAll('.log-line')
    expect(lines[0].classes()).toContain('lvl-info')
    expect(lines[2].classes()).toContain('lvl-warn')
    expect(lines[3].classes()).toContain('lvl-error')
  })

  it('uppercases the level pill', () => {
    const w = mount(LogViewer, { props: { lines: SAMPLE } })
    expect(w.findAll('.lvl')[2].text()).toBe('WARN')
  })

  it('filters lines via the search input (case-insensitive)', async () => {
    const w = mount(LogViewer, { props: { lines: SAMPLE } })
    const input = w.find('.log-search')
    await input.setValue('SLOW')
    expect(w.findAll('.log-line')).toHaveLength(1)
    expect(w.find('.log-line .msg').text()).toContain('slow query')
  })

  it('shows the empty state when no lines match the filter', async () => {
    const w = mount(LogViewer, { props: { lines: SAMPLE } })
    await w.find('.log-search').setValue('zzzz-no-match')
    expect(w.find('.empty').exists()).toBe(true)
    expect(w.findAll('.log-line')).toHaveLength(0)
  })

  it('hides the controls row when controls=false', () => {
    const w = mount(LogViewer, { props: { lines: SAMPLE, controls: false } })
    expect(w.find('.logs-head').exists()).toBe(false)
  })

  it('honors the maxHeight prop on the log window', () => {
    const w = mount(LogViewer, { props: { lines: SAMPLE, maxHeight: '200px' } })
    const win = w.find('.log-window').element as HTMLElement
    expect(win.style.maxHeight).toBe('200px')
  })

  it('respects defaultFollow', () => {
    const w = mount(LogViewer, { props: { lines: SAMPLE, defaultFollow: false } })
    const cb = w.find('input[type="checkbox"]').element as HTMLInputElement
    expect(cb.checked).toBe(false)
  })

  it('exposes controls-before and controls-after slots', () => {
    const w = mount(LogViewer, {
      props: { lines: SAMPLE },
      slots: {
        'controls-before': '<button class="cb">Before</button>',
        'controls-after': '<button class="ca">After</button>',
      },
    })
    expect(w.find('.cb').exists()).toBe(true)
    expect(w.find('.ca').exists()).toBe(true)
  })

  it('reactively updates when lines change', async () => {
    const w = mount(LogViewer, { props: { lines: SAMPLE.slice(0, 2) } })
    expect(w.findAll('.log-line')).toHaveLength(2)
    await w.setProps({ lines: SAMPLE })
    await flushPromises()
    expect(w.findAll('.log-line')).toHaveLength(4)
  })
})
