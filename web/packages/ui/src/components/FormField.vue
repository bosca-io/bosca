<script setup lang="ts">
withDefaults(defineProps<{
  label?: string
  error?: string
  help?: string
  required?: boolean
  col?: number
}>(), {
  col: 12,
})
</script>

<template>
  <div class="form-field" :style="{ gridColumn: `span ${col}` }">
    <label v-if="label" class="form-field-label">
      {{ label }}
      <span v-if="required" class="form-field-required">*</span>
    </label>
    <slot />
    <p v-if="error" class="form-field-error">{{ error }}</p>
    <p v-if="help && !error" class="form-field-help">{{ help }}</p>
  </div>
</template>

<style scoped>
.form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.form-field-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.form-field-required {
  color: var(--err);
  margin-left: 2px;
}

.form-field-error {
  margin: 0;
  font-size: 12px;
  color: var(--err);
}

.form-field-help {
  margin: 0;
  font-size: 11.5px;
  color: var(--fg-3);
}
</style>
