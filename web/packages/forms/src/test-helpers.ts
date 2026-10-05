/**
 * Shared test helpers for Vue component testing in the forms package.
 * Re-exports @vue/test-utils utilities and provides common stubs for
 * Nuxt UI components that are unavailable in the test environment.
 */
import { shallowMount, mount as _mount, type ComponentMountingOptions } from '@vue/test-utils'
import { defineComponent, h, type Component } from 'vue'

export { shallowMount, defineComponent, h }
export { defineComponent as defineComponent_ } from 'vue'

/**
 * Mount wrapper that provides global stubs for Nuxt UI components.
 * Use this instead of raw mount() to avoid "component not found" warnings.
 */
export function mount(component: Component, options?: ComponentMountingOptions<any>) {
  return _mount(component, {
    ...options,
    global: {
      ...options?.global,
      stubs: {
        UInput: defineComponent({
          props: ['modelValue', 'placeholder', 'disabled', 'maxlength', 'minlength', 'type'],
          emits: ['update:modelValue'],
          template: '<input :value="modelValue" :placeholder="placeholder" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        }),
        UTextarea: defineComponent({
          props: ['modelValue', 'placeholder', 'disabled', 'rows', 'maxlength'],
          emits: ['update:modelValue'],
          template: '<textarea :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        }),
        UInputNumber: defineComponent({
          props: ['modelValue', 'placeholder', 'disabled', 'min', 'max'],
          emits: ['update:modelValue'],
          template: '<input type="number" :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', Number($event.target.value))" />',
        }),
        USelect: defineComponent({
          props: ['modelValue', 'items', 'placeholder', 'disabled'],
          emits: ['update:modelValue'],
          template: '<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="item in items" :key="item.value" :value="item.value">{{ item.label }}</option></select>',
        }),
        USelectMenu: defineComponent({
          props: ['modelValue', 'items', 'placeholder', 'disabled', 'loading', 'searchTerm', 'valueKey', 'labelKey', 'ignoreFilter', 'searchInput', 'createItem'],
          emits: ['update:modelValue', 'update:searchTerm', 'open'],
          template: '<div><select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="item in items" :key="item.value" :value="item.value">{{ item.label }}</option></select><slot v-if="!items || items.length === 0" name="empty" /></div>',
        }),
        UCheckbox: defineComponent({
          props: ['modelValue', 'label', 'disabled'],
          emits: ['update:modelValue'],
          template: '<input type="checkbox" :checked="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
        }),
        USwitch: defineComponent({
          props: ['modelValue', 'disabled'],
          emits: ['update:modelValue'],
          template: '<button role="switch" :aria-checked="modelValue" :disabled="disabled" @click="$emit(\'update:modelValue\', !modelValue)">switch</button>',
        }),
        URadioGroup: defineComponent({
          props: ['modelValue', 'items', 'disabled'],
          emits: ['update:modelValue'],
          template: '<div role="radiogroup"><div v-for="item in items" :key="item.value"><input type="radio" :value="item.value" :checked="modelValue === item.value" :disabled="disabled" @change="$emit(\'update:modelValue\', item.value)" /><label>{{ item.label }}</label></div></div>',
        }),
        UColorPicker: defineComponent({
          props: ['modelValue', 'disabled'],
          emits: ['update:modelValue'],
          template: '<input type="color" :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        }),
        UInputTags: defineComponent({
          props: ['modelValue', 'placeholder', 'disabled'],
          emits: ['update:modelValue'],
          template: '<div class="input-tags"><span v-for="t in modelValue" :key="t">{{ t }}</span></div>',
        }),
        UButton: defineComponent({
          props: ['label', 'loading', 'disabled'],
          template: '<button :disabled="disabled || loading">{{ label }}</button>',
        }),
        ...options?.global?.stubs,
      },
    },
  })
}
