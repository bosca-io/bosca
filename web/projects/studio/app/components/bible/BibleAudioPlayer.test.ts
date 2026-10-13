import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { logInteraction } from '@bosca/analytics-client-browser'
import BibleAudioPlayer from './BibleAudioPlayer.vue'

vi.mock('@bosca/analytics-client-browser', () => ({ logInteraction: vi.fn(async () => {}) }))
const audioChapter = {
  audio: { id: 'audio-1' }, usfm: 'JHN.1', url: '/api/v1/content/metadata/bible/audio?recordingId=recording-1',
  attribution: { copyright: 'Audio copyright', citation: 'Audio citation', informationUrl: null },
}
const result = (chapter = audioChapter) => ({ content: { metadata: { bible: { audioChapter: chapter } } } })
const query = vi.fn(async (..._args: unknown[]) => result())
const props = {
  bible: { metadataId: 'bible-1', version: 2, variant: 'reader' },
  usfm: 'JHN.1',
  recordings: [{ id: 'audio-1', name: 'Recording', nameLocal: 'Local recording' }, { id: 'audio-2', name: 'Other recording', nameLocal: '' }],
}
const Select = defineComponent({
  props: ['modelValue', 'options'], emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h('select', { value: props.modelValue, onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value) }, [
      h('option', { value: '' }, 'Choose'), ...props.options.map((option: { value: string; label: string }) => h('option', { value: option.value }, option.label)),
    ])
  },
})
const create = () => mount(BibleAudioPlayer, { props, global: { stubs: { Select } } })

beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {})
  query.mockReset().mockResolvedValue(result())
  vi.stubGlobal('useGraphQL', () => ({ query }))
})

describe('Bible audio playback', () => {
  it('loads only the selected recording and reports once when playback actually starts', async () => {
    const wrapper = create()
    await flushPromises()
    expect(query).not.toHaveBeenCalled()
    await wrapper.get('select').setValue('audio-1')
    await flushPromises()
    expect(query).toHaveBeenCalledWith(expect.anything(), { id: 'bible-1', version: 2, variant: 'reader', audioId: 'audio-1', usfm: 'JHN.1' })
    expect(wrapper.get('audio').attributes('src')).toBe(audioChapter.url)
    expect(wrapper.text()).toContain('Audio copyright')
    expect(logInteraction).not.toHaveBeenCalled()
    await wrapper.get('audio').trigger('play')
    expect(logInteraction).not.toHaveBeenCalled()
    await wrapper.get('audio').trigger('playing')
    expect(logInteraction).toHaveBeenCalledExactlyOnceWith({
      id: 'JHN.1', type: 'bible-audio', extras: { bible_id: 'bible-1', bible_version: '2', bible_variant: 'reader', bible_usfm: 'JHN.1', bible_audio_id: 'audio-1' },
    })
    await wrapper.get('audio').trigger('pause')
    await wrapper.get('audio').trigger('playing')
    expect(logInteraction).toHaveBeenCalledTimes(1)
    wrapper.unmount()
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled()
  })

  it('discards an old recording response when the selected source changes', async () => {
    let finish!: (value: ReturnType<typeof result>) => void
    query.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = create()
    await wrapper.get('select').setValue('audio-1')
    query.mockResolvedValueOnce(result({ ...audioChapter, audio: { id: 'audio-2' }, url: '/second' }))
    await wrapper.get('select').setValue('audio-2')
    await flushPromises()
    finish(result())
    await flushPromises()
    expect(wrapper.get('audio').attributes('src')).toBe('/second')
    await wrapper.get('audio').trigger('playing')
    expect(logInteraction).toHaveBeenCalledWith(expect.objectContaining({ extras: expect.objectContaining({ bible_audio_id: 'audio-2' }) }))
    wrapper.unmount()
  })

  it('shows loading and playback failures without reporting usage', async () => {
    query.mockRejectedValueOnce(new Error('Recording unavailable'))
    const wrapper = create()
    await wrapper.get('select').setValue('audio-1')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe('Recording unavailable')
    expect(wrapper.find('audio').exists()).toBe(false)
    await wrapper.get('select').setValue('audio-2')
    await flushPromises()
    await wrapper.get('audio').trigger('error')
    expect(wrapper.get('[role="alert"]').text()).toBe('Unable to play this recording.')
    expect(logInteraction).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
