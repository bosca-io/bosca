import { mount as _mount, shallowMount, type ComponentMountingOptions } from '@vue/test-utils'
import { type Component } from 'vue'

export { shallowMount }

export function mount(component: Component, options?: ComponentMountingOptions<any>) {
  return _mount(component, {
    ...options,
    global: {
      ...options?.global,
      stubs: {
        ...options?.global?.stubs,
      },
    },
  })
}
