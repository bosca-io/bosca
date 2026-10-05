<script setup lang="ts">
import PipelineTypeField from '~/components/pipelines/PipelineTypeField.vue'
import PipelineShapeBuilder from '~/components/pipelines/PipelineShapeBuilder.vue'
import type { BrowsableType, ShapeField } from '~/components/pipelines/pipelineNodeTypes'

/**
 * The pipeline editor's Type Browser — a searchable catalog of the object types that flow between nodes.
 * Pick a type on the left; its fields render on the right as a tree, and any field whose type is itself an
 * object (or a list of objects) expands in place. Types come from `pipelines.nodeTypes … structure` (walked
 * server-side from serial descriptors), so this stays domain-agnostic.
 */
const props = defineProps<{
  types: BrowsableType[]
  /** Serial name to open focused on; falls back to the first type. */
  initial: string | null
}>()
const emit = defineEmits<{ close: [] }>()

const search = ref('')
const selectedName = ref<string | null>(props.initial ?? props.types[0]?.name ?? null)

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase()
  if (!q) return props.types
  return props.types.filter(t => t.shortName.toLowerCase().includes(q) || t.name.toLowerCase().includes(q))
})

const selected = computed(() => props.types.find(t => t.name === selectedName.value) ?? null)

function select(name: string) {
  selectedName.value = name
  search.value = ''
}

// Reusable named shapes (name = "shape:<n>") are user-managed (create/delete); catalogued (code) types can't.
const saveNamedShape = inject<(name: string, fields: ShapeField[]) => Promise<void>>('saveNamedShape')
const deleteNamedShape = inject<(name: string) => Promise<void>>('deleteNamedShape')
function isNamedShape(name: string): boolean {
  return name.startsWith('shape:')
}

// Per-field type choices for the New type builder: primitives, UUID, JSON, and every catalogued type
// (single + `[]` list form).
const shapeTypeOptions = computed(() => [
  { value: 'String', label: 'String' },
  { value: 'Number', label: 'Number' },
  { value: 'Boolean', label: 'Boolean' },
  { value: 'UUID', label: 'UUID' },
  { value: 'JSON', label: 'JSON' },
  ...props.types.map(t => ({ value: t.name, label: t.shortName })),
])

// New-type creator / editor.
const creating = ref(false)
const editMode = ref(false)
const newTypeName = ref('')
const newTypeFields = ref<ShapeField[]>([])
const savingNew = ref(false)
function startNewType() {
  creating.value = true
  editMode.value = false
  newTypeName.value = ''
  newTypeFields.value = [{ name: '', type: 'String' }]
}
function startEditType() {
  const t = selected.value
  if (!t || !isNamedShape(t.name)) return
  creating.value = true
  editMode.value = true
  newTypeName.value = t.shortName
  newTypeFields.value = t.fields.map(f => ({ name: f.name, type: f.type }))
}
async function saveNewType() {
  const name = newTypeName.value.trim()
  if (!name || !saveNamedShape || !newTypeFields.value.length) return
  savingNew.value = true
  try {
    await saveNamedShape(name, newTypeFields.value)
    creating.value = false
    selectedName.value = `shape:${name}`
  }
  finally {
    savingNew.value = false
  }
}

// Inline delete confirmation (no browser confirm()).
const confirmingDelete = ref(false)
const deleting = ref(false)
async function confirmDelete() {
  const t = selected.value
  if (!t || !deleteNamedShape || !isNamedShape(t.name)) return
  deleting.value = true
  try {
    await deleteNamedShape(t.name)
    confirmingDelete.value = false
    selectedName.value = props.types.find(x => x.name !== t.name)?.name ?? null
  }
  finally {
    deleting.value = false
  }
}
// Reset transient state when the selection changes.
watch(selectedName, () => { confirmingDelete.value = false })
</script>

<template>
  <Modal
    title="Type Browser"
    subtitle="The object types that flow between nodes, and their fields — click a field's type to follow it"
    icon="braces"
    width="min(92vw, 860px)"
    @close="emit('close')"
  >
    <div class="type-browser">
      <aside class="tb-list">
        <TextInput
          v-model="search"
          type="search"
          size="sm"
          icon="search"
          placeholder="Search types…"
        />
        <Button
          v-if="saveNamedShape"
          size="xs"
          variant="ghost"
          icon="plus"
          @click="startNewType"
        >
          New type
        </Button>
        <p
          v-if="filtered.length === 0"
          class="tb-empty"
        >
          No types match.
        </p>
        <button
          v-for="t in filtered"
          :key="t.name"
          type="button"
          :class="['tb-item', { 'is-active': !creating && t.name === selectedName }]"
          @click="creating = false; select(t.name)"
        >
          <span class="tb-item-name">{{ t.shortName }}</span>
          <span class="tb-item-count">{{ t.fields.length }}</span>
        </button>
      </aside>

      <!-- New / edit reusable type -->
      <section
        v-if="creating"
        class="tb-detail"
      >
        <header class="tb-detail-head">
          <span class="tb-detail-name">{{ editMode ? 'Edit reusable type' : 'New reusable type' }}</span>
        </header>
        <div class="tb-new">
          <TextInput
            v-model="newTypeName"
            label="Name"
            placeholder="e.g. ReleaseBundle"
            size="sm"
          />
          <PipelineShapeBuilder
            v-model="newTypeFields"
            :type-options="shapeTypeOptions"
            hide-reusable-save
          />
          <div class="tb-new-actions">
            <Button
              size="sm"
              variant="ghost"
              @click="creating = false"
            >
              Cancel
            </Button>
            <Button
              size="sm"
              primary
              :disabled="!newTypeName.trim() || !newTypeFields.length || savingNew"
              @click="saveNewType"
            >
              Save type
            </Button>
          </div>
        </div>
      </section>

      <section
        v-else-if="selected"
        class="tb-detail"
      >
        <header class="tb-detail-head">
          <div class="tb-detail-title">
            <span class="tb-detail-name">{{ selected.shortName }}</span>
            <span
              v-if="isNamedShape(selected.name)"
              class="tb-reusable"
            >reusable type</span>
          </div>
          <div
            v-if="isNamedShape(selected.name) && !confirmingDelete"
            class="tb-detail-actions"
          >
            <button
              v-if="saveNamedShape"
              type="button"
              class="tb-edit"
              @click="startEditType"
            >
              <Icon
                name="pencil"
                :size="12"
              />
              Edit
            </button>
            <button
              v-if="deleteNamedShape"
              type="button"
              class="tb-delete"
              @click="confirmingDelete = true"
            >
              <Icon
                name="trash"
                :size="12"
              />
              Delete
            </button>
          </div>
          <code
            v-else-if="!isNamedShape(selected.name)"
            class="tb-detail-fqn"
          >{{ selected.name }}</code>
        </header>
        <div
          v-if="confirmingDelete"
          class="tb-confirm"
        >
          <span>Delete <strong>{{ selected.shortName }}</strong>? Pipelines referencing it will show it as unresolved.</span>
          <div class="tb-confirm-actions">
            <Button
              size="xs"
              variant="ghost"
              :disabled="deleting"
              @click="confirmingDelete = false"
            >
              Cancel
            </Button>
            <Button
              size="xs"
              :accent="'var(--err, #f87171)'"
              :disabled="deleting"
              @click="confirmDelete"
            >
              Delete
            </Button>
          </div>
        </div>
        <div class="tb-fields">
          <PipelineTypeField
            v-for="f in selected.fields"
            :key="f.name"
            :field="f"
            :depth="0"
          />
        </div>
      </section>
      <section
        v-else
        class="tb-detail tb-detail-empty"
      >
        No introspectable types in this pipeline yet.
      </section>
    </div>
  </Modal>
</template>

<style scoped>
/* Fixed content height so the window doesn't resize as you move between types — each pane scrolls within it. */
.type-browser { display: grid; grid-template-columns: 220px 1fr; gap: 16px; height: min(60vh, 560px); min-height: 340px; }

/* ─── List ──────────────────────────────────────────────────────────────────── */
.tb-list { display: flex; flex-direction: column; gap: 4px; border-right: 1px solid var(--line); padding-right: 16px; min-height: 0; overflow-y: auto; }
.tb-empty { font-size: 12px; color: var(--fg-3); padding: 8px 4px; }
.tb-item {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  width: 100%; text-align: left; padding: 7px 10px; border: none; border-radius: 6px;
  background: none; color: var(--fg-1); font-size: 13px; cursor: pointer;
}
.tb-item:hover { background: var(--bg-2); color: var(--fg-0); }
.tb-item.is-active { background: color-mix(in oklch, var(--accent, var(--fg-1)) 16%, transparent); color: var(--fg-0); }
.tb-item-name { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tb-item-count { font-size: 11px; color: var(--fg-3); font-variant-numeric: tabular-nums; flex: 0 0 auto; }

/* ─── Detail ────────────────────────────────────────────────────────────────── */
.tb-detail { min-width: 0; display: flex; flex-direction: column; overflow: hidden; }
.tb-detail-empty { color: var(--fg-3); font-size: 13px; padding-top: 8px; }
.tb-detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding-bottom: 12px; margin-bottom: 8px; border-bottom: 1px solid var(--line); flex: 0 0 auto; }
.tb-detail-title { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.tb-detail-name { font-size: 15px; font-weight: 600; color: var(--fg-0); }
.tb-detail-fqn { font-size: 11px; color: var(--fg-3); overflow-wrap: anywhere; }
.tb-reusable {
  align-self: flex-start; font-size: 10px; text-transform: uppercase; letter-spacing: 0.05em; font-weight: 600;
  color: var(--accent, #5ec5ff); background: color-mix(in oklch, var(--accent, #5ec5ff) 14%, transparent);
  padding: 1px 6px; border-radius: 4px;
}
.tb-delete {
  display: inline-flex; align-items: center; gap: 5px; flex: 0 0 auto; cursor: pointer;
  font-size: 11.5px; padding: 4px 9px; border-radius: 6px;
  color: var(--err, #f87171); background: none; border: 1px solid color-mix(in oklch, var(--err, #f87171) 40%, transparent);
}
.tb-delete:hover:not(:disabled) { background: color-mix(in oklch, var(--err, #f87171) 12%, transparent); }
.tb-delete:disabled { opacity: 0.5; cursor: default; }
.tb-detail-actions { display: flex; align-items: center; gap: 6px; flex: 0 0 auto; }
.tb-edit {
  display: inline-flex; align-items: center; gap: 5px; cursor: pointer;
  font-size: 11.5px; padding: 4px 9px; border-radius: 6px;
  color: var(--fg-2); background: none; border: 1px solid var(--line);
}
.tb-edit:hover { color: var(--fg-0); border-color: var(--fg-3); }
.tb-new { display: flex; flex-direction: column; gap: 12px; overflow-y: auto; flex: 1 1 auto; min-height: 0; }
.tb-new-actions { display: flex; justify-content: flex-end; gap: 8px; }
.tb-confirm {
  display: flex; flex-direction: column; gap: 8px; margin-bottom: 10px; padding: 10px 12px; border-radius: 8px;
  font-size: 12.5px; color: var(--fg-1);
  background: color-mix(in oklch, var(--err, #f87171) 10%, transparent);
  border: 1px solid color-mix(in oklch, var(--err, #f87171) 35%, transparent);
}
.tb-confirm-actions { display: flex; justify-content: flex-end; gap: 8px; }
.tb-fields { display: flex; flex-direction: column; flex: 1 1 auto; min-height: 0; overflow-y: auto; }
</style>
