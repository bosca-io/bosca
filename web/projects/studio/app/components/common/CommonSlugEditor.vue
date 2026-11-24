<script lang="ts" setup>
defineProps<{
  slug: string
}>()

const emit = defineEmits<{
  edit: []
}>()
</script>

<template>
  <div class="slug-field">
    <div class="slug-label-row">
      <span class="slug-label">Slug</span>
    </div>
    <div class="slug-input-row">
      <input
        :value="slug"
        class="slug-input mono"
        type="text"
        readonly
        title="Click to edit the slug"
        @click="emit('edit')"
      >
    </div>
  </div>
</template>

<style scoped>
.slug-field {
  margin-bottom: 14px;
}

.slug-label-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.slug-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-1);
}

.slug-input-row {
  display: flex;
  gap: 6px;
}

.slug-input {
  flex: 1;
  padding: 7px 10px;
  border-radius: 6px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  font-size: 12.5px;
  color: var(--fg-2);
  cursor: pointer;
  outline: none;
  overflow: hidden;
  text-overflow: ellipsis;
}

.slug-input:hover {
  border-color: var(--brand-2);
}

.slug-input:focus {
  border-color: var(--line);
}
</style>
