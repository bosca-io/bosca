import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import SMediaPlayer from './SMediaPlayer.vue'
import type { Metadata } from '~/types/graphql'

type EventHandler = (...args: unknown[]) => void

const plyrEventHandlers: Record<string, EventHandler> = {}
let lastPlyrInstance: MockPlyrClass | null = null

function setLastPlyrInstance(instance: MockPlyrClass) {
  lastPlyrInstance = instance
}

class MockPlyrClass {
  on = vi.fn((event: string, handler: EventHandler) => {
    plyrEventHandlers[event] = handler
  })
  destroy = vi.fn()
  currentTime = 0
  duration = 120
  paused = true
  play = vi.fn()
  pause = vi.fn()
  options?: Record<string, unknown>

  constructor(_el: unknown, options?: Record<string, unknown>) {
    this.options = options
    setLastPlyrInstance(this)
  }
}

const hlsEventHandlers: Record<string, EventHandler> = {}
let lastHlsInstance: MockHlsClass | null = null

function setLastHlsInstance(instance: MockHlsClass) {
  lastHlsInstance = instance
}

class MockHlsClass {
  static isSupported = vi.fn(() => true)
  static Events = { MANIFEST_PARSED: 'hlsManifestParsed' }

  loadSource = vi.fn()
  attachMedia = vi.fn()
  on = vi.fn((event: string, handler: EventHandler) => {
    hlsEventHandlers[event] = handler
  })
  destroy = vi.fn()

  constructor() {
    setLastHlsInstance(this)
  }
}

vi.mock('plyr', () => ({ default: MockPlyrClass }))
vi.mock('hls.js', () => ({ default: MockHlsClass }))
vi.mock('plyr/dist/plyr.css', () => ({}))

function makeAudioItem() {
  return {
    __typename: 'Metadata' as const,
    id: 'audio-1',
    name: 'test-audio',
    attributes: {},
    content: { type: 'audio/mpeg' },
    media: null,
  }
}

function makeVideoItem() {
  return {
    __typename: 'Metadata' as const,
    id: 'video-1',
    name: 'test-video',
    attributes: {},
    content: { type: 'video/mp4' },
    media: null,
  }
}

function makeYouTubeItem() {
  return {
    __typename: 'Metadata' as const,
    id: 'yt-1',
    name: 'test-youtube',
    attributes: { 'youtube.id': 'dQw4w9WgXcQ' },
    content: { type: 'bosca/x-youtube-video' },
    media: null,
  }
}

function makeHlsItem() {
  return {
    __typename: 'Metadata' as const,
    id: 'hls-1',
    name: 'test-hls',
    attributes: {},
    content: { type: 'video/mp4' },
    media: { hls: { url: 'https://example.com/stream.m3u8' } },
  }
}

// Stub ClientOnly as a pass-through
const stubs = {
  ClientOnly: { template: '<slot />' },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
}

describe('SMediaPlayer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Object.keys(plyrEventHandlers).forEach((k) => Reflect.deleteProperty(plyrEventHandlers, k))
    Object.keys(hlsEventHandlers).forEach((k) => Reflect.deleteProperty(hlsEventHandlers, k))
    lastPlyrInstance = null
    lastHlsInstance = null
  })

  it('renders audio element for audio items', async () => {
    const wrapper = mount(SMediaPlayer, {
      props: { item: makeAudioItem() as unknown as Metadata },
      global: { stubs },
    })
    await nextTick()
    await nextTick()

    expect(wrapper.find('audio').exists()).toBe(true)
    expect(wrapper.find('video').exists()).toBe(false)
  })

  it('renders YouTube div with correct data attributes', async () => {
    const wrapper = mount(SMediaPlayer, {
      props: { item: makeYouTubeItem() as unknown as Metadata },
      global: { stubs },
    })
    await nextTick()

    const ytDiv = wrapper.find('[data-plyr-provider="youtube"]')
    expect(ytDiv.exists()).toBe(true)
    expect(ytDiv.attributes('data-plyr-embed-id')).toBe('dQw4w9WgXcQ')
  })

  it('renders video element for MP4 items', async () => {
    const wrapper = mount(SMediaPlayer, {
      props: { item: makeVideoItem() as unknown as Metadata },
      global: { stubs },
    })
    await nextTick()

    expect(wrapper.find('video').exists()).toBe(true)
    expect(wrapper.find('audio').exists()).toBe(false)
  })

  it('renders video element for HLS items', async () => {
    const wrapper = mount(SMediaPlayer, {
      props: { item: makeHlsItem() as unknown as Metadata },
      global: { stubs },
    })
    await nextTick()

    expect(wrapper.find('video').exists()).toBe(true)
  })

  it('renders video element when only src is provided', async () => {
    const wrapper = mount(SMediaPlayer, {
      props: { src: 'https://example.com/video.m3u8' },
      global: { stubs },
    })
    await nextTick()

    expect(wrapper.find('video').exists()).toBe(true)
  })

  it('renders nothing when no item or src', async () => {
    const wrapper = mount(SMediaPlayer, {
      props: {},
      global: { stubs },
    })
    await nextTick()

    expect(wrapper.find('audio').exists()).toBe(false)
    expect(wrapper.find('video').exists()).toBe(false)
    expect(wrapper.find('[data-plyr-provider]').exists()).toBe(false)
  })

  it('shows poster placeholder before ready', async () => {
    const wrapper = mount(SMediaPlayer, {
      props: { item: makeVideoItem() as unknown as Metadata },
      global: { stubs },
    })
    await nextTick()

    expect(wrapper.find('.s-media-poster').exists()).toBe(true)
  })

  it('initializes Plyr for audio playback', async () => {
    mount(SMediaPlayer, {
      props: { item: makeAudioItem() as unknown as Metadata },
      global: { stubs },
    })
    await flushPromises()

    expect(lastPlyrInstance).not.toBeNull()
    expect(lastPlyrInstance!.options?.autoplay).toBe(false)
  })

  it('initializes Plyr with YouTube options for YouTube items', async () => {
    mount(SMediaPlayer, {
      props: { item: makeYouTubeItem() as unknown as Metadata },
      global: { stubs },
    })
    await flushPromises()

    expect(lastPlyrInstance).not.toBeNull()
    expect((lastPlyrInstance!.options?.youtube as Record<string, unknown> | undefined)?.noCookie).toBe(true)
  })

  it('sets 16:9 ratio for non-audio content', async () => {
    mount(SMediaPlayer, {
      props: { item: makeVideoItem() as unknown as Metadata },
      global: { stubs },
    })
    await flushPromises()

    expect(lastPlyrInstance).not.toBeNull()
    expect(lastPlyrInstance!.options?.ratio).toBe('16:9')
  })

  it('does not set ratio for audio content', async () => {
    mount(SMediaPlayer, {
      props: { item: makeAudioItem() as unknown as Metadata },
      global: { stubs },
    })
    await flushPromises()

    expect(lastPlyrInstance).not.toBeNull()
    expect(lastPlyrInstance!.options?.ratio).toBeUndefined()
  })

  it('loads HLS source for HLS items', async () => {
    mount(SMediaPlayer, {
      props: { item: makeHlsItem() as unknown as Metadata },
      global: { stubs },
    })
    await flushPromises()

    expect(lastHlsInstance).not.toBeNull()
    expect(lastHlsInstance!.loadSource).toHaveBeenCalledWith(
      'https://example.com/stream.m3u8',
    )
    expect(lastHlsInstance!.attachMedia).toHaveBeenCalled()
  })

  it('destroys player and HLS on unmount', async () => {
    const wrapper = mount(SMediaPlayer, {
      props: { item: makeAudioItem() as unknown as Metadata },
      global: { stubs },
    })
    await flushPromises()

    wrapper.unmount()

    expect(lastPlyrInstance?.destroy).toHaveBeenCalled()
  })

  it('exposes seek method', async () => {
    const wrapper = mount(SMediaPlayer, {
      props: { item: makeAudioItem() as unknown as Metadata },
      global: { stubs },
    })
    await flushPromises()

    wrapper.vm.seek(5000)
    expect(lastPlyrInstance!.currentTime).toBe(5)
  })

  it('exposes play and pause methods', async () => {
    const wrapper = mount(SMediaPlayer, {
      props: { item: makeAudioItem() as unknown as Metadata },
      global: { stubs },
    })
    await flushPromises()

    wrapper.vm.play()
    expect(lastPlyrInstance!.play).toHaveBeenCalled()

    wrapper.vm.pause()
    expect(lastPlyrInstance!.pause).toHaveBeenCalled()
  })

  it('exposes paused state', async () => {
    const wrapper = mount(SMediaPlayer, {
      props: { item: makeAudioItem() as unknown as Metadata },
      global: { stubs },
    })
    await flushPromises()

    expect(wrapper.vm.paused).toBe(true)
  })
})
