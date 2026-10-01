<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
/**
 * Debug panel exposing the editor's Yjs document: lists every shared type,
 * renders its current value, and (for Map/Array/Text types) allows editing the
 * value in place — useful for diagnosing collaboration/attribute state.
 */
import * as Y from 'yjs'
import JsonEditorVue from 'json-editor-vue'
import { applyValueToType } from '~/utils/yjsJson'

const props = defineProps<{
  ydoc: Y.Doc | null | undefined
}>()

const toast = useToast()
const editing = ref(false)
const selectedType = ref<string | null>(null)
const editValue = ref<any>(null)

const sharedTypeNames = computed(() => {
  if (!props.ydoc) return []
  const doc = toRaw(props.ydoc)
  return Array.from(doc.share.keys()).sort()
})

function getSharedType(name: string) {
  if (!props.ydoc) return null
  const doc = toRaw(props.ydoc)
  return (doc.share as any).get(name)
}

function getTypeName(type: any) {
  if (type instanceof Y.Map) return 'Map'
  if (type instanceof Y.Array) return 'Array'
  if (type instanceof Y.Text) return 'Text'
  if (type instanceof Y.XmlFragment) return 'XmlFragment'
  if (type instanceof Y.XmlElement) return 'XmlElement'
  if (type instanceof Y.XmlText) return 'XmlText'
  return 'Unknown'
}

const isJsonType = (type: any) => type instanceof Y.Map || type instanceof Y.Array
const isEditable = (type: any) => type instanceof Y.Map || type instanceof Y.Array || type instanceof Y.Text
const isTextType = (type: any) => type instanceof Y.Text || type instanceof Y.XmlFragment || type instanceof Y.XmlElement || type instanceof Y.XmlText

const currentType = computed(() => selectedType.value ? getSharedType(selectedType.value) : null)
const currentTypeName = computed(() => currentType.value ? getTypeName(currentType.value) : '')

function refreshEditValue() {
  if (!selectedType.value) return
  const type = getSharedType(selectedType.value)
  if (!type) {
    editValue.value = null
    return
  }
  editValue.value = isTextType(type) ? type.toString() : type.toJSON()
}

watch(selectedType, () => {
  if (editing.value) editing.value = false
  refreshEditValue()
})

const docMeta = computed(() => {
  if (!props.ydoc) return { guid: '', clientID: 0, gc: false }
  const doc = toRaw(props.ydoc)
  return { guid: doc.guid, clientID: doc.clientID, gc: doc.gc }
})

function onYDocUpdate() {
  if (!editing.value) refreshEditValue()
}

function selectDefaultType() {
  if (!selectedType.value && sharedTypeNames.value.length > 0) {
    selectedType.value = sharedTypeNames.value.find(n => n !== 'changes' && n !== 'saved') || sharedTypeNames.value[0]!
  }
}

watch(() => props.ydoc, (newDoc, oldDoc) => {
  if (oldDoc) toRaw(oldDoc).off('update', onYDocUpdate)
  if (newDoc) {
    const doc = toRaw(newDoc)
    doc.on('update', onYDocUpdate)
    // Ensure the standard Bosca shared types are listed even when empty.
    doc.getXmlFragment('default')
    doc.getMap('textAttributes')
    doc.getMap('numberAttributes')
    doc.getMap('dateAttributes')
    doc.getMap('metadatas')
    doc.getMap('metadata')
    doc.getMap('collections')
    doc.getMap('collection')
    doc.getText('changes')
    doc.getText('saved')
    selectDefaultType()
  }
  refreshEditValue()
}, { immediate: true })

onMounted(selectDefaultType)

onUnmounted(() => {
  if (props.ydoc) toRaw(props.ydoc).off('update', onYDocUpdate)
})

function onCancel() {
  editing.value = false
  refreshEditValue()
}

function onSave() {
  try {
    if (!props.ydoc || !selectedType.value) return
    const doc = toRaw(props.ydoc)
    const type = getSharedType(selectedType.value)
    if (!type) return

    doc.transact(() => {
      if (type instanceof Y.XmlFragment || type instanceof Y.XmlElement || type instanceof Y.XmlText) {
        throw new Error('Direct editing of XML types is not supported in this view.')
      }
      applyValueToType(type, editValue.value)
      const changes = doc.getText('changes') as any
      if (typeof changes.setAttribute === 'function') {
        changes.setAttribute('changes', 'true')
      }
    }, 'introspection-edit')

    toast.success(`Applied changes to ${selectedType.value}`)
  } catch (e: any) {
    console.error('Failed to apply edits', e)
    toast.error(e?.message || 'Failed to apply changes')
  } finally {
    editing.value = false
    refreshEditValue()
  }
}
</script>

<template>
  <div class="ydoc-panel">
    <div class="ydoc-meta">
      <span class="ydoc-meta-item">GUID <code class="mono">{{ docMeta.guid }}</code></span>
      <span class="ydoc-meta-item">ClientID <code class="mono">{{ docMeta.clientID }}</code></span>
      <span class="ydoc-meta-item">GC <code class="mono">{{ docMeta.gc }}</code></span>
      <span class="spacer" />
      <Button
        v-if="!editing && selectedType && isEditable(currentType)"
        size="sm"
        icon="pencil"
        @click="editing = true"
      >
        Edit
      </Button>
      <template v-else-if="editing">
        <Button
          size="sm"
          icon="x"
          @click="onCancel"
        >Cancel</Button>
        <Button
          size="sm"
          icon="save"
          primary
          @click="onSave"
        >Save</Button>
      </template>
    </div>

    <div class="ydoc-body">
      <div class="ydoc-types">
        <div class="ydoc-types-title">Shared Types</div>
        <button
          v-for="name in sharedTypeNames"
          :key="name"
          class="ydoc-type"
          :class="{ active: selectedType === name }"
          @click="selectedType = name">
          <span class="ydoc-type-name">{{ name }}</span>
          <span class="ydoc-type-kind">{{ getTypeName(getSharedType(name)) }}</span>
        </button>
      </div>

      <div class="ydoc-value">
        <div v-if="selectedType" class="ydoc-value-inner">
          <div class="ydoc-value-header">
            <span class="ydoc-value-title">{{ selectedType }} ({{ currentTypeName }})</span>
            <span v-if="!isEditable(currentType)" class="ydoc-readonly">Read-only</span>
          </div>
          <ClientOnly v-if="isJsonType(currentType)">
            <JsonEditorVue
              v-model="editValue"
              :read-only="!editing"
              :main-menu-bar="false"
              :navigation-bar="false"
              class="ydoc-json json-editor" />
          </ClientOnly>
          <textarea
            v-else
            v-model="editValue"
            class="ydoc-text mono"
            :readonly="!editing"
            spellcheck="false" />
        </div>
        <div v-else class="ydoc-empty">Select a shared type to view its content</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ydoc-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 540px;
}

.ydoc-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 11.5px;
  color: var(--fg-3);
}

.ydoc-meta-item code { color: var(--fg-1); }
.spacer { flex: 1; }

.ydoc-body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 12px;
}

.ydoc-types {
  width: 180px;
  flex: 0 0 180px;
  overflow-y: auto;
  border-right: 1px solid var(--line);
  padding-right: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ydoc-types-title {
  font-size: 10.5px;
  font-weight: 650;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-4);
  margin-bottom: 6px;
}

.ydoc-type {
  text-align: left;
  padding: 5px 8px;
  border-radius: var(--r-xs);
  background: none;
  border: none;
  cursor: pointer;
  transition: background 0.1s;
}

.ydoc-type:hover { background: var(--bg-3); }
.ydoc-type.active { background: color-mix(in oklch, var(--brand-2) 14%, transparent); }
.ydoc-type-name { display: block; font-size: 12.5px; color: var(--fg-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ydoc-type.active .ydoc-type-name { color: var(--brand-2); font-weight: 600; }
.ydoc-type-kind { display: block; font-size: 10px; color: var(--fg-4); }

.ydoc-value { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.ydoc-value-inner { flex: 1; min-height: 0; display: flex; flex-direction: column; gap: 6px; }
.ydoc-value-header { display: flex; align-items: center; justify-content: space-between; }
.ydoc-value-title { font-size: 12.5px; font-weight: 600; color: var(--fg-1); }
.ydoc-readonly { font-size: 10.5px; font-style: italic; color: var(--fg-4); }

.ydoc-json { flex: 1; min-height: 0; overflow: auto; border: 1px solid var(--line); border-radius: var(--r-sm); }

.ydoc-text {
  flex: 1;
  min-height: 0;
  resize: none;
  padding: 10px;
  font-size: 12px;
  background: var(--bg-2);
  color: var(--fg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  outline: none;
}

.ydoc-text:focus { border-color: var(--brand-2); }

.ydoc-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12.5px;
  font-style: italic;
  color: var(--fg-4);
}
</style>
