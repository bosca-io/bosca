<script setup lang="ts">
/**
 * A proper card-entry form for PAN-processing gateways (BluePay-style). Formats the number into
 * brand-aware groups with the detected brand glyph inline, takes MM/YY expiry and a brand-length CVV,
 * and reports validity (Luhn + non-past expiry + CVV length + name) via `v-model:valid`. The card
 * value is emitted normalized (digits only) — it's a transient charge input, never stored.
 */
interface Card { name: string; number: string; cvv: string; expirationMonth: number; expirationYear: number }
const model = defineModel<Card>({ required: true })
const valid = defineModel<boolean>('valid', { default: false })

const name = ref('')
const numberField = ref('')
const expiryField = ref('')
const cvv = ref('')

const digits = computed(() => numberField.value.replace(/\D/g, ''))
const brand = computed(() => detectCardBrand(digits.value))
const isAmex = computed(() => brand.value === 'AMERICAN_EXPRESS')

function group(d: string): string {
  if (detectCardBrand(d) === 'AMERICAN_EXPRESS') {
    return [d.slice(0, 4), d.slice(4, 10), d.slice(10, 15)].filter(Boolean).join(' ')
  }
  return d.replace(/(.{4})/g, '$1 ').trim()
}

// Reformat as the fields are typed (number grouping, MM/YY slash, CVV digits + brand length).
watch(numberField, (v) => {
  const d = v.replace(/\D/g, '').slice(0, isAmex.value ? 15 : 16)
  const formatted = group(d)
  if (formatted !== v) numberField.value = formatted
})
watch(expiryField, (v) => {
  const d = v.replace(/\D/g, '').slice(0, 4)
  const formatted = d.length >= 3 ? `${d.slice(0, 2)}/${d.slice(2)}` : d
  if (formatted !== v) expiryField.value = formatted
})
watch(cvv, (v) => {
  const d = v.replace(/\D/g, '').slice(0, isAmex.value ? 4 : 3)
  if (d !== v) cvv.value = d
})

const expMonth = computed(() => Number(expiryField.value.slice(0, 2)) || 0)
const expYear = computed(() => {
  const yy = expiryField.value.replace(/\D/g, '').slice(2, 4)
  return yy.length === 2 ? 2000 + Number(yy) : 0
})
const expiryValid = computed(() => {
  const m = expMonth.value
  const y = expYear.value
  if (m < 1 || m > 12 || y < 2000) return false
  const now = new Date()
  return y > now.getFullYear() || (y === now.getFullYear() && m >= now.getMonth() + 1)
})

watchEffect(() => {
  valid.value = !!name.value.trim() && luhnValid(digits.value) && expiryValid.value && cvv.value.length >= (isAmex.value ? 4 : 3)
  model.value = { name: name.value.trim(), number: digits.value, cvv: cvv.value, expirationMonth: expMonth.value, expirationYear: expYear.value }
})
</script>

<template>
  <div class="cc-form">
    <TextInput v-model="name" label="Cardholder name" placeholder="Name on card" />
    <div class="cc-number">
      <TextInput
        v-model="numberField"
        label="Card number"
        placeholder="1234 5678 9012 3456"
        mono />
      <CardBrandIcon :brand="brand" class="cc-brand" />
    </div>
    <div class="cc-row">
      <TextInput
        v-model="expiryField"
        label="Expiry (MM/YY)"
        placeholder="MM/YY"
        mono />
      <TextInput
        v-model="cvv"
        label="CVV"
        :placeholder="isAmex ? '4 digits' : '3 digits'"
        mono />
    </div>
  </div>
</template>

<style scoped>
.cc-form { display: flex; flex-direction: column; gap: 14px; }
.cc-number { position: relative; }
.cc-brand { position: absolute; left: 10px; bottom: 5px; pointer-events: none; }
/* Make room on the left for the brand glyph so the number doesn't run under it. */
.cc-number :deep(.text-input-el) { padding-left: 40px; }
.cc-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
</style>
