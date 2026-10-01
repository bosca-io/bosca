import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import GuideProgressModal from './GuideProgressModal.vue'

const query = vi.fn()

function activeProfile(
  id: string,
  name: string,
  version: number,
  completedStepIds: number[],
  steps: Array<{ id: number; metadata: { id: string; name: string } }>,
) {
  return {
    profile: { id, name },
    progressions: [{
      version,
      completedStepIds,
      started: '2026-08-01T12:00:00Z',
      modified: '2026-08-03T12:00:00Z',
      guide: { steps },
    }],
  }
}

const profiles = {
  ada: activeProfile('profile-1', 'Ada Lovelace', 3, [10], [
    { id: 10, metadata: { id: 'step-1', name: 'Welcome' } },
    { id: 20, metadata: { id: 'step-2', name: 'Practice' } },
    { id: 30, metadata: { id: 'step-3', name: 'Finish' } },
  ]),
  grace: activeProfile('profile-2', 'Grace Hopper', 3, [], [
    { id: 10, metadata: { id: 'step-1', name: 'Welcome' } },
  ]),
  katherine: activeProfile('profile-3', 'Katherine Johnson', 4, [], []),
}

const guideProgress = {
  content: {
    guides: {
      progress: {
        statistics: {
          activeProgressions: 2,
          activeProfiles: 2,
          historicalProgressions: 8,
          completions: 7,
          totalProgressions: 10,
          uniqueProfiles: 9,
        },
        activeProfiles: [profiles.ada, profiles.grace],
      },
    },
  },
}

const stubs = {
  Modal: {
    template: '<div class="modal"><slot /><div class="modal-footer"><slot name="footer" /></div></div>',
    props: ['title', 'subtitle', 'icon', 'accent', 'width'],
    emits: ['close'],
  },
  Button: {
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
    props: ['disabled', 'icon', 'size'],
    emits: ['click'],
  },
  Icon: {
    template: '<span class="icon" :data-name="name" />',
    props: ['name', 'size'],
  },
}

function mountModal() {
  return mount(GuideProgressModal, {
    props: { guideId: 'guide-1', guideName: 'Learning Guide' },
    global: { stubs },
  })
}

describe('GuideProgressModal', () => {
  beforeEach(() => {
    query.mockReset()
    query.mockResolvedValue(guideProgress)
    vi.stubGlobal('useGraphQL', () => ({ query }))
  })

  it('loads statistics and progress from the returned profiles', async () => {
    const wrapper = mountModal()
    await flushPromises()

    expect(query).toHaveBeenCalledTimes(1)
    expect(query).toHaveBeenCalledWith(expect.anything(), {
      id: 'guide-1',
      limit: 50,
      offset: 0,
    })
    const document = query.mock.calls[0]![0]
    expect(document.loc?.source.body).toContain('activeProfiles(limit: $limit, offset: $offset)')
    expect(document.loc?.source.body).toContain('progressions {')
    expect(document.loc?.source.body).not.toContain('all(limit: 100')
    expect(wrapper.text()).toContain('Ada Lovelace')
    expect(wrapper.text()).toContain('Grace Hopper')
    expect(wrapper.text()).toContain('Version 3')
    expect(wrapper.text()).toContain('8')
    expect(wrapper.text()).toContain('7')
    expect(wrapper.text()).toContain('70% of all progressions')
  })

  it('renders each progression against its own guide version', async () => {
    const response = structuredClone(guideProgress)
    const ada = response.content.guides.progress.activeProfiles[0]!
    ada.progressions.push({
      version: 1,
      completedStepIds: [100],
      started: '2025-01-01T12:00:00Z',
      modified: '2025-01-02T12:00:00Z',
      guide: {
        steps: [{ id: 100, metadata: { id: 'old-step', name: 'Original step' } }],
      },
    })
    query.mockResolvedValueOnce(response)

    const wrapper = mountModal()
    await flushPromises()

    expect(wrapper.text()).toContain('Version 3')
    expect(wrapper.text()).toContain('Version 1')
    const headers = wrapper.findAll('.progression-header')
    await headers[1]!.trigger('click')
    expect(wrapper.text()).toContain('Original step')
  })

  it('expands a profile into completed, current, and remaining step statuses', async () => {
    const wrapper = mountModal()
    await flushPromises()
    await wrapper.findAll('.progression-header')[0]!.trigger('click')

    const steps = wrapper.findAll('.step-status')
    expect(steps).toHaveLength(3)
    expect(steps[0]!.classes()).toContain('step-status--completed')
    expect(steps[0]!.text()).toContain('Completed')
    expect(steps[1]!.classes()).toContain('step-status--current')
    expect(steps[1]!.text()).toContain('In progress')
    expect(steps[2]!.classes()).toContain('step-status--remaining')
    expect(steps[2]!.text()).toContain('Not started')
  })

  it('surfaces loading failures and allows retry', async () => {
    query.mockRejectedValueOnce(new Error('Not authorized'))
    const wrapper = mountModal()
    await flushPromises()

    expect(wrapper.find('.state-message--error').text()).toContain('Not authorized')
    await wrapper.find('.state-message--error button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Ada Lovelace')
  })

  it('loads the next profile page without replacing profiles already shown', async () => {
    const firstPage = structuredClone(guideProgress)
    firstPage.content.guides.progress.statistics.activeProfiles = 3
    firstPage.content.guides.progress.statistics.activeProgressions = 3
    const nextPage = structuredClone(firstPage)
    nextPage.content.guides.progress.activeProfiles = [profiles.katherine]
    query.mockResolvedValueOnce(firstPage).mockResolvedValueOnce(nextPage)

    const wrapper = mountModal()
    await flushPromises()
    await wrapper.find('.load-more').trigger('click')
    await flushPromises()

    expect(query).toHaveBeenLastCalledWith(expect.anything(), {
      id: 'guide-1',
      limit: 50,
      offset: 2,
    })
    expect(wrapper.text()).toContain('Ada Lovelace')
    expect(wrapper.text()).toContain('Katherine Johnson')
  })
})
