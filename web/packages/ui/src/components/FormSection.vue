<script setup lang="ts">
defineProps<{
  label?: string
  description?: string
}>()
</script>

<template>
  <fieldset class="form-section">
    <legend v-if="label" class="form-section-label">{{ label }}</legend>
    <p v-if="description" class="form-section-desc">{{ description }}</p>
    <div class="form-section-body">
      <slot />
    </div>
  </fieldset>
</template>

<style scoped>
.form-section {
  margin: 0;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-section-label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--fg-1);
  letter-spacing: 0.06em;
  text-transform: uppercase;
  padding: 0 4px;
}

.form-section-desc {
  margin: 0;
  font-size: 12px;
  color: var(--fg-3);
  line-height: 1.4;
}

.form-section-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
</style>
