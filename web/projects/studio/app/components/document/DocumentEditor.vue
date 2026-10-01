<script lang="ts" setup>
/* eslint-disable @typescript-eslint/no-explicit-any */
import { EditorContent, type Range, useEditor } from '@tiptap/vue-3'
import type { DocumentInput, Metadata, Profile } from '~/types/graphql'
import type * as Y from 'yjs'
import { newExtensions } from '~/utils/editor/extensions'
import { markYDocReady, markYDocSaved } from '~/utils/editor/ydoc'
import { NewContainerEvent, NewLinkEvent } from '~/utils/editor/commanditems'
import type { AttributeState } from '~/utils/editor/attribute'
import { sanitizeDocument } from '~/utils/editor/document'

const router = useRouter()
const { searchEntities } = useEntitySearch()

const props = defineProps<{
  metadata: Metadata
  profile: Profile
  attributes: Map<string, AttributeState>
  ydoc: Y.Doc
  editable: boolean
   
  onDocument: (document: DocumentInput) => void
   
  onTitleUpdate?: (title: string) => void
}>()

let pendingRange: Range | null | undefined = null
const title = ref()
const newContainerOpen = ref(false)
const addContainerSelection = ref()
const newLinkOpen = ref(false)
const linkUrl = ref('')

// Loading a document can rewrite parts of the YDoc even though the user
// changed nothing: the ProseMirror schema normalizes stored content (defaulted
// attrs, demoted duplicate H1s, dropped unknown marks) and y-prosemirror
// writes that normalization back into the shared fragment on the first
// transaction. If that write lands after the doc is marked ready it is
// indistinguishable from a user edit and the document opens dirty. So the doc
// is marked ready only once the provider has synced AND the editor exists,
// with a no-op transaction dispatched first to flush the normalization while
// it still doesn't count as a change.
let providerSynced = false

function completeInitialization() {
  if (!providerSynced) return
  const e = editor.value
  if (!e || e.isDestroyed) return
  e.view.dispatch(e.state.tr)
  markYDocReady(toRaw(props.ydoc))
}

const editor = useEditor({
  content: sanitizeDocument(props.metadata.document?.content?.document),
  editable: props.editable,
  extensions: newExtensions(
    props.metadata,
    props.profile,
    props.ydoc,
    (t) => {
      if (props.onTitleUpdate) {
        props.onTitleUpdate(t)
      }
    },
    searchEntities,
    () => {
      providerSynced = true
      completeInitialization()
    },
  ),
  onContentError: (ev) => {
    console.error('Error loading content', (ev as unknown as any).message)
  },
  onUpdate: ({ editor: ed }) => {
    const node = ed.view.dom.childNodes[0]
    title.value = node ? (node as HTMLElement)?.innerText : ''
    if (props.onTitleUpdate) {
      props.onTitleUpdate(title.value)
    }
  },
  editorProps: {
    attributes: {
      class: 'editor-textarea'
    }
  }
})

watch(title, () => {
  if (props.onTitleUpdate && title.value) {
    props.onTitleUpdate(title.value)
  }
})

watch(() => props.editable, (value) => {
  editor.value?.setEditable(value)
})

function getDocument() {
  const newDocument: DocumentInput = {
    templateMetadataId: props.metadata.document?.template?.id,
    templateMetadataVersion: props.metadata.document?.template?.version,
    title: title.value,
    content: {
      document: sanitizeDocument(editor.value!.state.doc.toJSON())
    }
  }
  return newDocument
}

defineExpose({ getDocument })

function onReset() {
  editor.value!.commands.setContent(props.metadata.document?.content?.document)
  for (const key of props.attributes.keys()) {
    props.attributes.get(key)?.reset()
  }
  setTimeout(() => {
    markYDocSaved(props.ydoc)
  }, 100)
}

async function onAddContainer(name: string) {
  const e = editor.value
  if (!e) return
  try {
    let chain = e.chain().focus()
    if (pendingRange) {
      chain = chain.deleteRange(pendingRange)
      pendingRange = null
    }
    chain.setContainer({ name }).run()
  } catch (err: any) {
    console.error('Error adding container:', err.message)
  } finally {
    newContainerOpen.value = false
  }
}

function onNewContainerEvent(event: NewContainerEvent) {
  pendingRange = event.range
  newContainerOpen.value = true
}

function onNewLinkEvent(event: NewLinkEvent) {
  pendingRange = event.range
  const e = editor.value
  if (!e) return
  const existingHref = e.getAttributes('link').href
  linkUrl.value = existingHref || ''
  newLinkOpen.value = true
}

function onApplyLink() {
  const e = editor.value
  if (!e) return
  const url = linkUrl.value.trim()
  if (url) {
    let chain = e.chain().focus()
    if (pendingRange) {
      chain = chain.deleteRange(pendingRange)
      pendingRange = null
    }
    chain.extendMarkRange('link').setLink({ href: url }).run()
  } else {
    e.chain().focus().extendMarkRange('link').unsetLink().run()
  }
  pendingRange = null
  newLinkOpen.value = false
  linkUrl.value = ''
}

function onRemoveLink() {
  const e = editor.value
  if (!e) return
  e.chain().focus().extendMarkRange('link').unsetLink().run()
  pendingRange = null
  newLinkOpen.value = false
  linkUrl.value = ''
}

watch(addContainerSelection, () => {
  if (addContainerSelection.value) {
    onAddContainer(addContainerSelection.value)
    addContainerSelection.value = null
    newContainerOpen.value = false
  }
})

const containerOptions = computed(() => {
  const containers = props.metadata.document?.template?.documentTemplate?.containers || []
  return (containers as any[]).map((c: any) => ({
    label: c.name,
    value: c.id,
  }))
})

function onEditorClick(event: MouseEvent) {
  const target = event.target as HTMLElement
  if (target.closest('.mention[data-mention-id]')) {
    event.preventDefault()
    const el = target.closest('.mention[data-mention-id]') as HTMLElement
    const href = el.getAttribute('href')
    if (href) router.push(href)
  }
}

onMounted(() => {
  if (editor.value) {
    const node = editor.value.view.dom.childNodes[0]
    title.value = node ? (node as HTMLElement)?.innerText : ''
  }
  // The provider can finish syncing before the editor instance exists (it is
  // created during setup, the editor in useEditor's mount hook) — retry here.
  completeInitialization()
  window.addEventListener('reset-document', onReset)
  // @ts-expect-error custom event
  window.addEventListener(NewContainerEvent.NAME, onNewContainerEvent)
  // @ts-expect-error custom event
  window.addEventListener(NewLinkEvent.NAME, onNewLinkEvent)
})

onUnmounted(() => {
  window.removeEventListener('reset-document', onReset)
  // @ts-expect-error custom event
  window.removeEventListener(NewContainerEvent.NAME, onNewContainerEvent)
  // @ts-expect-error custom event
  window.removeEventListener(NewLinkEvent.NAME, onNewLinkEvent)
})
</script>

<template>
  <div v-if="editor" class="document-editor">
    <DocumentBubbleMenu :editor="editor" />
    <DocumentDragHandler :editor="editor" :editable="editable" />
    <EditorContent :editor="editor" @click="onEditorClick" />

    <Modal
      v-if="newContainerOpen"
      title="Select Container Type"
      @close="newContainerOpen = false"
    >
      <div class="container-select-body">
        <Select
          v-model="addContainerSelection"
          :options="containerOptions"
          placeholder="Select the container type…"
        />
      </div>
    </Modal>

    <Modal
      v-if="newLinkOpen"
      title="Add Hyperlink"
      @close="newLinkOpen = false"
    >
      <div class="link-modal-body">
        <div class="field">
          <label class="field-label">URL</label>
          <input
            v-model="linkUrl"
            class="field-input"
            type="url"
            placeholder="https://example.com"
            @keydown.enter="onApplyLink"
          >
        </div>
        <div class="link-modal-actions">
          <Button
            v-if="editor?.getAttributes('link').href"
            size="sm"
            @click="onRemoveLink"
          >Remove Link</Button>
          <span class="spacer" />
          <Button size="sm" @click="newLinkOpen = false">Cancel</Button>
          <Button primary size="sm" @click="onApplyLink">Apply</Button>
        </div>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.document-editor {
  width: 100%;
  height: 100%;
}

.document-editor :deep(.editor-textarea) {
  outline: none;
  min-height: 100%;
  /* Left gutter so the block drag handle floats over the editor's own box.
     The drag-handle plugin hides the handle whenever the pointer leaves the
     editor element for anything other than the handle itself — without this
     gutter the handle sits in dead space outside the editor and vanishes
     before it can be grabbed. The host panel's left padding is zeroed to
     compensate, so the total left inset stays the same. */
  padding: 0 0 0 30px;
}

.document-editor :deep(.editor-textarea > h1:first-child) {
  margin: 0 0 18px;
  font-size: 22px;
  font-weight: 700;
  color: var(--fg-0);
  letter-spacing: -0.01em;
}

.document-editor :deep(ul[data-type="taskList"]) {
  list-style: none;
  padding-left: 0;
  margin: 8px 0;
}

.document-editor :deep(ul[data-type="taskList"] li) {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.document-editor :deep(ul[data-type="taskList"] li > label) {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  margin-top: 3px;
  cursor: pointer;
}

.document-editor :deep(ul[data-type="taskList"] li > label input[type="checkbox"]) {
  appearance: none;
  width: 16px;
  height: 16px;
  border: 1.5px solid var(--fg-3, #555);
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
  position: relative;
}

.document-editor :deep(ul[data-type="taskList"] li > label input[type="checkbox"]:checked) {
  background: var(--brand-2, #5ec5ff);
  border-color: var(--brand-2, #5ec5ff);
}

.document-editor :deep(ul[data-type="taskList"] li > label input[type="checkbox"]:checked::after) {
  content: '';
  position: absolute;
  top: 2px;
  left: 5px;
  width: 4px;
  height: 8px;
  border: solid var(--bg-0, #000);
  border-width: 0 2px 2px 0;
  transform: rotate(45deg);
}

.document-editor :deep(ul[data-type="taskList"] li[data-checked="true"] > div > p) {
  text-decoration: line-through;
  color: var(--fg-3, #666);
}

.document-editor :deep(ul[data-type="taskList"] li > div) {
  flex: 1;
  min-width: 0;
}

.document-editor :deep(ul[data-type="taskList"] ul[data-type="taskList"]) {
  margin-left: 24px;
}

.document-editor :deep(.mention) {
  color: var(--brand-2, #5ec5ff);
  background: color-mix(in oklch, var(--brand-2, #5ec5ff) 12%, transparent);
  border-radius: 4px;
  padding: 1px 4px;
  font-weight: 500;
  cursor: pointer;
  text-decoration: none;
  white-space: nowrap;
}

.document-editor :deep(.mention:hover) {
  text-decoration: underline;
}

.container-select-body {
  padding: 4px 0;
}

.link-modal-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.link-modal-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.spacer {
  flex: 1;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-1);
}

.field-input {
  padding: 7px 10px;
  border-radius: 6px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  font-size: 12.5px;
  color: var(--fg-0);
  outline: none;
  font-family: inherit;
}

.field-input:focus {
  border-color: var(--brand-2);
}
</style>
