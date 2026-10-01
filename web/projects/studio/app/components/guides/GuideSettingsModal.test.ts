import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import GuideSettingsModal from './GuideSettingsModal.vue'

const stubs = {
  Modal: {
    template: '<div class="modal"><slot /><div class="modal-footer"><slot name="footer" /></div></div>',
    props: ['title', 'subtitle', 'icon', 'accent', 'width'],
    emits: ['close'],
  },
  Button: {
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
    props: ['disabled', 'icon', 'primary', 'size', 'accent'],
    emits: ['click'],
  },
  Select: {
    template: '<select class="select" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option></select>',
    props: ['modelValue', 'options', 'label'],
    emits: ['update:modelValue'],
  },
  // Clicking the editor simulates the user picking a new recurrence pattern,
  // which the real RRuleEditor emits as a bare RRULE: line.
  TemplatesRRuleEditor: {
    template: '<div class="rrule-editor" @click="$emit(\'update:modelValue\', \'RRULE:FREQ=WEEKLY;INTERVAL=1\')">rrule</div>',
    props: ['modelValue'],
    emits: ['update:modelValue'],
  },
}

function mountModal(props: Record<string, unknown> = {}) {
  return mount(GuideSettingsModal, {
    props: { type: 'LINEAR', rrule: null, ...props },
    global: { stubs },
  })
}

describe('GuideSettingsModal', () => {
  it('preselects the current guide type', () => {
    const wrapper = mountModal({ type: 'CALENDAR_PROGRESS' })
    expect((wrapper.find('select').element as HTMLSelectElement).value).toBe('CALENDAR_PROGRESS')
  })

  it('hides the recurrence section for linear guides', () => {
    const wrapper = mountModal({ type: 'LINEAR' })
    expect(wrapper.find('.rrule-editor').exists()).toBe(false)
  })

  it('shows the recurrence section for calendar guides', () => {
    const wrapper = mountModal({ type: 'CALENDAR', rrule: 'RRULE:FREQ=DAILY;INTERVAL=1' })
    expect(wrapper.find('.rrule-editor').exists()).toBe(true)
  })

  it('emits close from the cancel button', async () => {
    const wrapper = mountModal()
    await wrapper.findAll('.modal-footer button')[0]!.trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('emits save with a null rrule for a linear guide', async () => {
    const wrapper = mountModal({ type: 'LINEAR' })
    await wrapper.findAll('.modal-footer button')[1]!.trigger('click')
    expect(wrapper.emitted('save')).toEqual([[{ type: 'LINEAR', rrule: null }]])
  })

  it('preserves the DTSTART anchor when saving an unchanged calendar recurrence', async () => {
    const wrapper = mountModal({
      type: 'CALENDAR',
      rrule: 'DTSTART:20250101T000000Z\nRRULE:FREQ=DAILY;INTERVAL=1',
    })
    await wrapper.findAll('.modal-footer button')[1]!.trigger('click')
    expect(wrapper.emitted('save')).toEqual([[{
      type: 'CALENDAR',
      rrule: 'DTSTART:20250101T000000Z\nRRULE:FREQ=DAILY;INTERVAL=1',
    }]])
  })

  it('splices an edited recurrence back onto the preserved DTSTART anchor', async () => {
    const wrapper = mountModal({
      type: 'CALENDAR',
      rrule: 'DTSTART:20250101T000000Z\nDTEND:20250301T000000Z\nRRULE:FREQ=DAILY;INTERVAL=1',
    })
    await wrapper.find('.rrule-editor').trigger('click')
    await wrapper.findAll('.modal-footer button')[1]!.trigger('click')
    expect(wrapper.emitted('save')).toEqual([[{
      type: 'CALENDAR',
      rrule: 'DTSTART:20250101T000000Z\nDTEND:20250301T000000Z\nRRULE:FREQ=WEEKLY;INTERVAL=1',
    }]])
  })

  it('switching to a calendar type reveals recurrence and emits the new rrule', async () => {
    const wrapper = mountModal({ type: 'LINEAR', rrule: null })
    await wrapper.find('select').setValue('CALENDAR')
    expect(wrapper.find('.rrule-editor').exists()).toBe(true)
    await wrapper.find('.rrule-editor').trigger('click')
    await wrapper.findAll('.modal-footer button')[1]!.trigger('click')
    expect(wrapper.emitted('save')).toEqual([[{ type: 'CALENDAR', rrule: 'RRULE:FREQ=WEEKLY;INTERVAL=1' }]])
  })
})
