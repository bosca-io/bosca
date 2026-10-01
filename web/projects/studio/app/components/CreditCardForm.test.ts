import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { detectCardBrand, luhnValid } from '~/utils/commerce'
import CreditCardForm from './CreditCardForm.vue'

// The component reaches for these as Nuxt auto-imports; wire the real implementations.
beforeEach(() => {
  vi.stubGlobal('detectCardBrand', detectCardBrand)
  vi.stubGlobal('luhnValid', luhnValid)
})

const TextInputStub = {
  props: ['modelValue', 'label', 'placeholder', 'mono'],
  emits: ['update:modelValue'],
  template: '<input :aria-label="label" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
}
const CardBrandIconStub = { props: ['brand'], template: '<span class="brand">{{ brand }}</span>' }

function mountForm() {
  return mount(CreditCardForm, {
    props: { modelValue: { name: '', number: '', cvv: '', expirationMonth: 1, expirationYear: 2000 }, valid: false },
    global: { stubs: { TextInput: TextInputStub, CardBrandIcon: CardBrandIconStub } },
  })
}

async function field(w: ReturnType<typeof mountForm>, label: string, value: string) {
  await w.find(`[aria-label="${label}"]`).setValue(value)
  await nextTick()
}

function last<T>(events: T[][] | undefined): T | undefined {
  return events?.[events.length - 1]?.[0]
}

describe('CreditCardForm', () => {
  it('emits a normalized card and valid=true for a complete valid card', async () => {
    const w = mountForm()
    await field(w, 'Cardholder name', 'Ada Lovelace')
    await field(w, 'Card number', '4242 4242 4242 4242')
    await field(w, 'Expiry (MM/YY)', '1299')
    await field(w, 'CVV', '123')

    const card = last<{ number: string; expirationMonth: number; expirationYear: number; name: string }>(w.emitted('update:modelValue') as never)
    expect(card?.number).toBe('4242424242424242') // digits only
    expect(card?.expirationMonth).toBe(12)
    expect(card?.expirationYear).toBe(2099)
    expect(card?.name).toBe('Ada Lovelace')
    expect(last<boolean>(w.emitted('update:valid') as never)).toBe(true)
  })

  it('stays invalid when the number fails the Luhn check', async () => {
    const w = mountForm()
    await field(w, 'Cardholder name', 'Ada')
    await field(w, 'Card number', '4242 4242 4242 4241') // bad checksum
    await field(w, 'Expiry (MM/YY)', '1299')
    await field(w, 'CVV', '123')
    // valid starts false and (with a bad number) never flips true — defineModel only emits on change.
    const emits = (w.emitted('update:valid') as boolean[][] | undefined) ?? []
    expect(emits.flat().includes(true)).toBe(false)
  })
})
