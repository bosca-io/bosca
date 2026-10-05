<script lang="ts" setup>
const props = defineProps<{
  modelValue: Record<string, unknown>
  channel: 'PUSH' | 'EMAIL' | 'BANNER'
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>]
}>()

function update(key: string, value: unknown) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}

function get(key: string, fallback?: string): string
function get(key: string, fallback: Record<string, string>): Record<string, string>
function get(key: string, fallback: boolean): boolean
function get(key: string, fallback: number): number
function get(key: string, fallback?: unknown): unknown {
  return props.modelValue[key] ?? fallback ?? ''
}

function getDefaultAction(field: 'label' | 'url'): string {
  const action = props.modelValue.defaultAction
  return action && typeof action === 'object'
    ? String((action as Record<string, unknown>)[field] ?? '')
    : ''
}

function updateDefaultAction(field: 'label' | 'url', value: string) {
  const existing = props.modelValue.defaultAction
  const action: Record<string, unknown> = existing && typeof existing === 'object'
    ? { ...(existing as Record<string, unknown>) }
    : { id: 'open' }
  action[field] = value
  update('defaultAction', action)
}

const showAndroid = ref(false)
const showIos = ref(false)
const showDelivery = ref(false)

const customDataEntries = computed(() => {
  const data = get('data', {})
  if (!data || typeof data !== 'object') return []
  return Object.entries(data as Record<string, string>)
})

function addCustomData() {
  const data = { ...(get('data', {}) as Record<string, string>) }
  const key = `key_${Object.keys(data).length}`
  data[key] = ''
  update('data', data)
}

function removeCustomData(key: string) {
  const data = { ...(get('data', {}) as Record<string, string>) }
  Reflect.deleteProperty(data, key)
  update('data', data)
}

function updateCustomDataKey(oldKey: string, newKey: string) {
  const data = { ...(get('data', {}) as Record<string, string>) }
  const value = data[oldKey] ?? ''
  Reflect.deleteProperty(data, oldKey)
  data[newKey] = value
  update('data', data)
}

function updateCustomDataValue(key: string, value: string) {
  const data = { ...(get('data', {}) as Record<string, string>) }
  data[key] = value
  update('data', data)
}
</script>

<template>
  <div class="content-editor">
    <!-- PUSH -->
    <template v-if="channel === 'PUSH'">
      <div class="field">
        <label class="field-label">Title</label>
        <input
          class="field-input"
          :value="get('title')"
          :disabled="disabled"
          placeholder="Notification title"
          @input="update('title', ($event.target as HTMLInputElement).value)">
      </div>
      <div class="field">
        <label class="field-label">Body</label>
        <textarea
          class="field-textarea"
          :value="get('body')"
          :disabled="disabled"
          placeholder="Notification body"
          rows="3"
          @input="update('body', ($event.target as HTMLTextAreaElement).value)" />
      </div>
      <div class="field">
        <label class="field-label">Image URL</label>
        <input
          class="field-input"
          :value="get('imageUrl')"
          :disabled="disabled"
          placeholder="https://..."
          @input="update('imageUrl', ($event.target as HTMLInputElement).value)">
      </div>
      <div class="field-row">
        <div class="field">
          <label class="field-label">Default Action Label</label>
          <input
            class="field-input"
            :value="getDefaultAction('label')"
            :disabled="disabled"
            placeholder="Open"
            @input="updateDefaultAction('label', ($event.target as HTMLInputElement).value)">
        </div>
        <div class="field">
          <label class="field-label">Default Action URL</label>
          <input
            class="field-input"
            :value="getDefaultAction('url')"
            :disabled="disabled"
            placeholder="https://..."
            @input="updateDefaultAction('url', ($event.target as HTMLInputElement).value)">
        </div>
      </div>

      <!-- Delivery options -->
      <button class="section-toggle" @click="showDelivery = !showDelivery">
        <Icon :name="showDelivery ? 'chevronDown' : 'chevron'" :size="12" />
        Delivery Options
      </button>
      <div v-if="showDelivery" class="section-body">
        <div class="field-row">
          <div class="field">
            <label class="field-label">Priority</label>
            <select
              class="field-select"
              :value="get('priority', 'normal')"
              :disabled="disabled"
              @change="update('priority', ($event.target as HTMLSelectElement).value)">
              <option value="normal">Normal</option>
              <option value="high">High</option>
            </select>
          </div>
          <div class="field">
            <label class="field-label">Badge</label>
            <input
              class="field-input"
              type="number"
              :value="get('badge', 0)"
              :disabled="disabled"
              min="0"
              @input="update('badge', Number(($event.target as HTMLInputElement).value))">
          </div>
          <div class="field">
            <label class="field-label">TTL (seconds)</label>
            <input
              class="field-input"
              type="number"
              :value="get('ttl', 0)"
              :disabled="disabled"
              min="0"
              @input="update('ttl', Number(($event.target as HTMLInputElement).value))">
          </div>
        </div>
        <div class="field-row">
          <div class="field field--check">
            <input
              type="checkbox"
              :checked="!!get('sound', false)"
              :disabled="disabled"
              @change="update('sound', ($event.target as HTMLInputElement).checked)">
            <label class="field-label">Sound</label>
          </div>
        </div>
      </div>

      <!-- Android -->
      <button class="section-toggle" @click="showAndroid = !showAndroid">
        <Icon :name="showAndroid ? 'chevronDown' : 'chevron'" :size="12" />
        Android Settings
      </button>
      <div v-if="showAndroid" class="section-body">
        <div class="field-row">
          <div class="field">
            <label class="field-label">Channel ID</label>
            <input
              class="field-input"
              :value="get('androidChannelId')"
              :disabled="disabled"
              @input="update('androidChannelId', ($event.target as HTMLInputElement).value)">
          </div>
          <div class="field">
            <label class="field-label">Tag</label>
            <input
              class="field-input"
              :value="get('androidTag')"
              :disabled="disabled"
              @input="update('androidTag', ($event.target as HTMLInputElement).value)">
          </div>
          <div class="field">
            <label class="field-label">Collapse Key</label>
            <input
              class="field-input"
              :value="get('collapseKey')"
              :disabled="disabled"
              @input="update('collapseKey', ($event.target as HTMLInputElement).value)">
          </div>
        </div>
      </div>

      <!-- iOS -->
      <button class="section-toggle" @click="showIos = !showIos">
        <Icon :name="showIos ? 'chevronDown' : 'chevron'" :size="12" />
        iOS Settings
      </button>
      <div v-if="showIos" class="section-body">
        <div class="field-row">
          <div class="field">
            <label class="field-label">Thread ID</label>
            <input
              class="field-input"
              :value="get('threadId')"
              :disabled="disabled"
              @input="update('threadId', ($event.target as HTMLInputElement).value)">
          </div>
          <div class="field">
            <label class="field-label">Category</label>
            <input
              class="field-input"
              :value="get('category')"
              :disabled="disabled"
              @input="update('category', ($event.target as HTMLInputElement).value)">
          </div>
        </div>
        <div class="field-row">
          <div class="field">
            <label class="field-label">Interruption Level</label>
            <select
              class="field-select"
              :value="get('interruptionLevel', 'active')"
              :disabled="disabled"
              @change="update('interruptionLevel', ($event.target as HTMLSelectElement).value)">
              <option value="passive">Passive</option>
              <option value="active">Active</option>
              <option value="time-sensitive">Time Sensitive</option>
              <option value="critical">Critical</option>
            </select>
          </div>
          <div class="field">
            <label class="field-label">Relevance Score</label>
            <input
              class="field-input"
              type="number"
              :value="get('relevanceScore', 0)"
              :disabled="disabled"
              min="0"
              max="1"
              step="0.1"
              @input="update('relevanceScore', Number(($event.target as HTMLInputElement).value))">
          </div>
        </div>
        <div class="field-row">
          <div class="field field--check">
            <input
              type="checkbox"
              :checked="!!get('mutableContent', false)"
              :disabled="disabled"
              @change="update('mutableContent', ($event.target as HTMLInputElement).checked)">
            <label class="field-label">Mutable Content</label>
          </div>
          <div class="field field--check">
            <input
              type="checkbox"
              :checked="!!get('contentAvailable', false)"
              :disabled="disabled"
              @change="update('contentAvailable', ($event.target as HTMLInputElement).checked)">
            <label class="field-label">Content Available</label>
          </div>
        </div>
      </div>

      <!-- Custom data -->
      <div class="section-header">
        <span class="section-title">Custom Data</span>
        <button v-if="!disabled" class="section-add" @click="addCustomData">
          <Icon name="plus" :size="12" /> Add
        </button>
      </div>
      <div v-if="customDataEntries.length" class="custom-data">
        <div v-for="[key, value] in customDataEntries" :key="key" class="custom-data-row">
          <input
            class="field-input"
            :value="key"
            :disabled="disabled"
            placeholder="Key"
            @blur="updateCustomDataKey(key, ($event.target as HTMLInputElement).value)">
          <input
            class="field-input"
            :value="value"
            :disabled="disabled"
            placeholder="Value"
            @input="updateCustomDataValue(key, ($event.target as HTMLInputElement).value)">
          <button v-if="!disabled" class="custom-data-remove" @click="removeCustomData(key)">
            <Icon name="x" :size="12" />
          </button>
        </div>
      </div>
    </template>

    <!-- EMAIL -->
    <template v-else-if="channel === 'EMAIL'">
      <CampaignEmailContentEditor
        :model-value="modelValue"
        :disabled="disabled"
        @update:model-value="emit('update:modelValue', $event)" />
    </template>

    <!-- BANNER -->
    <template v-else-if="channel === 'BANNER'">
      <div class="field">
        <label class="field-label">Title</label>
        <input
          class="field-input"
          :value="get('title')"
          :disabled="disabled"
          placeholder="Banner title"
          @input="update('title', ($event.target as HTMLInputElement).value)">
      </div>
      <div class="field">
        <label class="field-label">Body</label>
        <textarea
          class="field-textarea"
          :value="get('body')"
          :disabled="disabled"
          placeholder="Banner message"
          rows="3"
          @input="update('body', ($event.target as HTMLTextAreaElement).value)" />
      </div>
      <div class="field">
        <label class="field-label">Image URL</label>
        <input
          class="field-input"
          :value="get('imageUrl')"
          :disabled="disabled"
          placeholder="https://..."
          @input="update('imageUrl', ($event.target as HTMLInputElement).value)">
      </div>
      <div class="field-row">
        <div class="field">
          <label class="field-label">CTA Label</label>
          <input
            class="field-input"
            :value="get('ctaLabel')"
            :disabled="disabled"
            placeholder="Learn More"
            @input="update('ctaLabel', ($event.target as HTMLInputElement).value)">
        </div>
        <div class="field">
          <label class="field-label">CTA URL</label>
          <input
            class="field-input"
            :value="get('ctaUrl')"
            :disabled="disabled"
            placeholder="https://..."
            @input="update('ctaUrl', ($event.target as HTMLInputElement).value)">
        </div>
      </div>
      <div class="field-row">
        <div class="field">
          <label class="field-label">Image Position</label>
          <select
            class="field-select"
            :value="get('imagePosition', 'top')"
            :disabled="disabled"
            @change="update('imagePosition', ($event.target as HTMLSelectElement).value)">
            <option value="top">Top</option>
            <option value="left">Left</option>
            <option value="right">Right</option>
          </select>
        </div>
        <div class="field field--check">
          <input
            type="checkbox"
            :checked="!!get('compact', false)"
            :disabled="disabled"
            @change="update('compact', ($event.target as HTMLInputElement).checked)">
          <label class="field-label">Compact Layout</label>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.content-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
  min-width: 0;
}

.field--check {
  flex-direction: row;
  align-items: center;
  gap: 8px;
}

.field-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-3);
}

.field-input {
  padding: 7px 10px;
  font-size: 13px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-1);
  outline: none;
  width: 100%;
}

.field-input:focus { border-color: var(--brand-2); }
.field-input:disabled { opacity: 0.5; cursor: not-allowed; }

.field-textarea {
  padding: 7px 10px;
  font-size: 13px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-1);
  outline: none;
  resize: vertical;
  width: 100%;
  line-height: 1.5;
}

.field-textarea:focus { border-color: var(--brand-2); }
.field-textarea:disabled { opacity: 0.5; cursor: not-allowed; }
.field-textarea--mono { font-family: var(--font-mono, monospace); font-size: 12px; }

.field-select {
  padding: 7px 10px;
  font-size: 13px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-1);
  outline: none;
  width: 100%;
}

.field-select:focus { border-color: var(--brand-2); }
.field-select:disabled { opacity: 0.5; cursor: not-allowed; }

.field-row {
  display: flex;
  gap: 12px;
}

.section-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px 0;
}

.section-toggle:hover { color: var(--fg-0); }

.section-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-left: 18px;
  border-left: 2px solid var(--line);
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
}

.section-add {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--fg-3);
  background: none;
  border: none;
  cursor: pointer;
}

.section-add:hover { color: var(--fg-1); }

.custom-data {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.custom-data-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.custom-data-remove {
  width: 24px;
  height: 24px;
  background: none;
  border: none;
  color: var(--fg-3);
  cursor: pointer;
  border-radius: var(--r-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.custom-data-remove:hover { color: var(--err); background: var(--bg-2); }
</style>
