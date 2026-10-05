import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ChannelSettings from './ChannelSettings.vue'

vi.stubGlobal('useGraphQL', () => ({
  query: vi.fn().mockResolvedValue({
    chat: { channel: { members: [
      { profileId: 'p-1', role: 'owner', profile: { id: 'p-1', name: 'Alice', slug: 'alice' } },
      { profileId: 'p-2', role: 'member', profile: { id: 'p-2', name: 'Bob', slug: 'bob' } },
    ] } },
  }),
  mutation: vi.fn().mockResolvedValue({}),
}))

vi.stubGlobal('useProfileSearch', () => ({
  searchProfiles: vi.fn().mockResolvedValue([]),
}))

const stubs = {
  Icon: { template: '<span />', props: ['name', 'size'] },
  Select: { template: '<div class="mock-select" />', props: ['placeholder', 'searchable', 'onSearch'] },
}

function cleanupBody() {
  while (document.body.firstChild) {
    document.body.removeChild(document.body.firstChild)
  }
}

afterEach(() => cleanupBody())

describe('ChannelSettings', () => {
  it('renders channel name', async () => {
    mount(ChannelSettings, {
      props: { channelId: 'ch-1', channelName: 'General' },
      global: { stubs },
    })
    await new Promise(r => setTimeout(r, 10))
    expect(document.querySelector('.settings-title')?.textContent).toContain('General')
  })

  it('renders member list after loading', async () => {
    mount(ChannelSettings, {
      props: { channelId: 'ch-1', channelName: 'General' },
      global: { stubs },
    })
    await new Promise(r => setTimeout(r, 50))
    const members = document.querySelectorAll('.member-row')
    expect(members.length).toBe(2)
  })

  it('shows add member button', async () => {
    mount(ChannelSettings, {
      props: { channelId: 'ch-1', channelName: 'General' },
      global: { stubs },
    })
    await new Promise(r => setTimeout(r, 10))
    expect(document.querySelector('.add-member-btn')).not.toBeNull()
  })

  it('emits close on close button', async () => {
    const wrapper = mount(ChannelSettings, {
      props: { channelId: 'ch-1', channelName: 'General' },
      global: { stubs },
    })
    await new Promise(r => setTimeout(r, 10))
    const closeBtn = document.querySelector('.settings-close') as HTMLElement
    closeBtn?.click()
    expect(wrapper.emitted('close')).toBeTruthy()
  })
})
