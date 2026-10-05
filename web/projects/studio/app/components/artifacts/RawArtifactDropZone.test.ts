import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RawArtifactDropZone from './RawArtifactDropZone.vue'

describe('RawArtifactDropZone', () => {
  it('emits every dropped file', async () => {
    const wrapper = mount(RawArtifactDropZone, {
      global: {
        stubs: { Icon: { template: '<span />' } },
      },
    })
    const files = [
      new File(['first'], 'first.bin', { type: 'application/octet-stream' }),
      new File(['second'], 'second.txt', { type: 'text/plain' }),
    ]

    await wrapper.get('.raw-drop-zone').trigger('drop', {
      dataTransfer: { files },
    })

    expect(wrapper.emitted('files')).toEqual([[files]])
  })

  it('does not accept files while disabled', async () => {
    const wrapper = mount(RawArtifactDropZone, {
      props: { disabled: true },
      global: {
        stubs: { Icon: { template: '<span />' } },
      },
    })

    await wrapper.get('.raw-drop-zone').trigger('drop', {
      dataTransfer: { files: [new File(['data'], 'artifact.bin')] },
    })

    expect(wrapper.emitted('files')).toBeUndefined()
  })
})
