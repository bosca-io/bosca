import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import NotificationPreferenceMatrix from './NotificationPreferenceMatrix.vue'

const GlassTable = {
  props: ['rows'],
  template: `
    <div>
      <div v-for="row in rows" :key="row.key" class="type-row">
        <slot name="col-type" :row="row" />
        <slot name="col-channel-EMAIL" :row="row" />
        <slot name="col-channel-PUSH" :row="row" />
      </div>
    </div>
  `,
}

const Switch = {
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: '<button class="preference-switch" :data-enabled="String(modelValue)" @click="$emit(\'update:modelValue\', !modelValue)" />',
}

const global = { stubs: { Badge: true, GlassTable, Switch } }

describe('NotificationPreferenceMatrix', () => {
  it('omits hidden notification types from user preference controls', () => {
    const wrapper = mount(NotificationPreferenceMatrix, {
      props: {
        types: [
          {
            key: 'marketing',
            name: 'Product news',
            description: null,
            optional: true,
            system: false,
            defaultEmailEnabled: true,
            defaultPushEnabled: true,
            hidden: false,
            displayOrder: 1,
          },
          {
            key: 'internal-updates',
            name: 'Internal updates',
            description: null,
            optional: true,
            system: false,
            defaultEmailEnabled: true,
            defaultPushEnabled: true,
            hidden: true,
            displayOrder: 2,
          },
        ],
        preferences: [],
      },
      global,
    })

    expect(wrapper.text()).toContain('Product news')
    expect(wrapper.text()).not.toContain('Internal updates')
    expect(wrapper.findAll('.type-row')).toHaveLength(1)
  })

  it('uses each channel default when no explicit preference exists', () => {
    const wrapper = mount(NotificationPreferenceMatrix, {
      props: {
        types: [{
          key: 'announcements',
          name: 'Announcements',
          description: null,
          optional: true,
          system: false,
          defaultEmailEnabled: false,
          defaultPushEnabled: true,
          hidden: false,
          displayOrder: 1,
        }],
        preferences: [],
        channels: ['EMAIL', 'PUSH'],
      },
      global,
    })

    const switches = wrapper.findAll('.preference-switch')
    expect(switches[0]?.attributes('data-enabled')).toBe('false')
    expect(switches[1]?.attributes('data-enabled')).toBe('true')
  })

  it('shows an explicit preference instead of the notification type default', () => {
    const wrapper = mount(NotificationPreferenceMatrix, {
      props: {
        types: [{
          key: 'announcements',
          name: 'Announcements',
          description: null,
          optional: true,
          system: false,
          defaultEmailEnabled: false,
          defaultPushEnabled: true,
          hidden: false,
          displayOrder: 1,
        }],
        preferences: [{ channel: 'EMAIL', type: 'announcements', optedOut: false }],
        channels: ['EMAIL'],
      },
      global,
    })

    expect(wrapper.get('.preference-switch').attributes('data-enabled')).toBe('true')
  })
})
