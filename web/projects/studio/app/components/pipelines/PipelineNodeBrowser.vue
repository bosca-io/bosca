<script setup lang="ts">
import PipelineTypeField from '~/components/pipelines/PipelineTypeField.vue'
import {
  groupNodeTypes,
  shortTypeName,
  type NodeInputSlotMeta,
  type NodeType,
} from '~/components/pipelines/pipelineNodeTypes'

/**
 * The pipeline editor's Node Browser — the palette's big sibling, opened like the Type Browser. A
 * searchable grid of every registered node type, sectioned by the organizational taxonomy each node
 * declares (`group` → `subgroup`, e.g. WorkOps → Releases), so far more of the catalog is visible at
 * once than the palette column shows. Clicking a card flips to that node's reference view — what it
 * does, expects, produces (with typed-object field structures), and its settings — with a one-click
 * "Add to canvas". Entirely data-driven from the same `pipelines.nodeTypes` the palette renders.
 */
const props = defineProps<{
  types: NodeType[]
}>()
const emit = defineEmits<{ close: [], add: [NodeType] }>()

const search = ref('')
function matchesSearch(t: NodeType): boolean {
  const q = search.value.trim().toLowerCase()
  if (!q) return true
  return t.label.toLowerCase().includes(q)
    || t.key.toLowerCase().includes(q)
    || t.description.toLowerCase().includes(q)
    || (t.group ?? '').toLowerCase().includes(q)
    || (t.subgroup ?? '').toLowerCase().includes(q)
}
const groups = computed(() => groupNodeTypes(props.types.filter(matchesSearch)))

// null = the grid; a key = that node's reference view.
const selectedKey = ref<string | null>(null)
const selected = computed(() => props.types.find(t => t.key === selectedKey.value) ?? null)

/** The taxonomy path shown on a detail header, e.g. "WorkOps › Releases". */
function taxonomyPath(t: NodeType): string | null {
  if (!t.group) return null
  return t.subgroup ? `${t.group} › ${t.subgroup}` : t.group
}

/** Display label for what an input slot accepts: its typeLabel, else the specific type, else the kind. */
function expectsLabel(s: NodeInputSlotMeta): string {
  return s.typeLabel || (s.type ? shortTypeName(s.type) : s.kind.toLowerCase())
}
</script>

<template>
  <Modal
    title="Node Browser"
    subtitle="Every node the editor can place — click one for what it expects, produces, and how it's configured"
    icon="boxes"
    width="min(94vw, 1100px)"
    @close="emit('close')"
  >
    <div class="node-browser">
      <!-- ─── Grid ──────────────────────────────────────────────────────────── -->
      <template v-if="!selected">
        <TextInput
          v-model="search"
          type="search"
          size="sm"
          icon="search"
          placeholder="Search nodes…"
          class="nb-search"
        />
        <div class="nb-scroll">
          <p
            v-if="groups.length === 0"
            class="nb-empty"
          >
            No nodes match.
          </p>
          <section
            v-for="group in groups"
            :key="group.name"
            class="nb-group"
          >
            <h3 class="nb-group-title">
              {{ group.name }}
            </h3>
            <template
              v-for="sub in group.subgroups"
              :key="sub.name ?? ''"
            >
              <h4
                v-if="sub.name"
                class="nb-subgroup-title"
              >
                {{ sub.name }}
              </h4>
              <div class="nb-grid">
                <button
                  v-for="t in sub.types"
                  :key="t.key"
                  type="button"
                  class="nb-card"
                  :style="{ '--node-accent': pipelineNodeAccent(t.category) }"
                  @click="selectedKey = t.key"
                >
                  <div class="nb-card-head">
                    <Icon
                      :name="pipelineNodeIcon(t.key, t.category)"
                      :size="15"
                      :color="pipelineNodeAccent(t.category)"
                      class="nb-card-icon"
                    />
                    <span class="nb-card-label">{{ t.label }}</span>
                  </div>
                  <p class="nb-card-desc">
                    {{ t.description }}
                  </p>
                </button>
              </div>
            </template>
          </section>
        </div>
      </template>

      <!-- ─── Detail ────────────────────────────────────────────────────────── -->
      <template v-else>
        <div class="nb-detail-bar">
          <button
            type="button"
            class="nb-back"
            @click="selectedKey = null"
          >
            <Icon
              name="arrow-left"
              :size="13"
            />
            All nodes
          </button>
          <Button
            size="sm"
            primary
            icon="plus"
            @click="emit('add', selected)"
          >
            Add to canvas
          </Button>
        </div>
        <div class="nb-scroll">
          <header class="nb-detail-head">
            <div class="nb-detail-title">
              <Icon
                :name="pipelineNodeIcon(selected.key, selected.category)"
                :size="18"
                :color="pipelineNodeAccent(selected.category)"
                class="nb-detail-icon"
              />
              <span class="nb-detail-name">{{ selected.label }}</span>
              <span
                class="nb-chip nb-chip-category"
                :style="{ '--node-accent': pipelineNodeAccent(selected.category) }"
              >{{ selected.category }}</span>
              <span
                v-if="taxonomyPath(selected)"
                class="nb-chip"
              >{{ taxonomyPath(selected) }}</span>
            </div>
            <code class="nb-detail-key">{{ selected.key }}</code>
          </header>
          <p
            v-if="selected.description"
            class="nb-desc"
          >
            {{ selected.description }}
          </p>

          <div class="nb-sections">
            <section v-if="selected.inputs.length">
              <h3 class="nb-section-title">
                Expects
              </h3>
              <div
                v-for="s in selected.inputs"
                :key="s.name"
                class="nb-slot"
              >
                <div class="nb-slot-head">
                  <span class="nb-slot-name">{{ s.name }}</span>
                  <span class="nb-slot-type">{{ expectsLabel(s) }}</span>
                  <span
                    v-if="!s.required"
                    class="nb-slot-opt"
                  >optional</span>
                </div>
                <p
                  v-if="s.description"
                  class="nb-slot-desc"
                >
                  {{ s.description }}
                </p>
                <div
                  v-if="s.structure?.length"
                  class="nb-slot-structure"
                >
                  <PipelineTypeField
                    v-for="f in s.structure"
                    :key="f.name"
                    :field="f"
                    :depth="0"
                  />
                </div>
              </div>
            </section>

            <section v-if="selected.outputs.length">
              <h3 class="nb-section-title">
                Produces
              </h3>
              <div
                v-for="o in selected.outputs"
                :key="o.name"
                class="nb-slot"
              >
                <div class="nb-slot-head">
                  <span
                    class="nb-slot-name"
                    :class="{ 'is-error': o.error }"
                  >{{ o.name }}</span>
                  <span class="nb-slot-type">{{ o.typeLabel || (o.type ? shortTypeName(o.type) : o.kind.toLowerCase()) }}</span>
                  <span
                    v-if="o.error"
                    class="nb-slot-err"
                  >error port</span>
                </div>
                <p
                  v-if="o.description"
                  class="nb-slot-desc"
                >
                  {{ o.description }}
                </p>
                <div
                  v-if="o.structure?.length"
                  class="nb-slot-structure"
                >
                  <PipelineTypeField
                    v-for="f in o.structure"
                    :key="f.name"
                    :field="f"
                    :depth="0"
                  />
                </div>
              </div>
            </section>

            <section v-if="selected.settings.length">
              <h3 class="nb-section-title">
                Settings
              </h3>
              <div
                v-for="s in selected.settings"
                :key="s.name"
                class="nb-slot"
              >
                <div class="nb-slot-head">
                  <span class="nb-slot-name">{{ s.label || s.name }}</span>
                  <span class="nb-slot-type">{{ s.control.toLowerCase().replace('_', ' ') }}</span>
                  <span
                    v-if="s.required"
                    class="nb-slot-req"
                  >required</span>
                </div>
                <p
                  v-if="s.description"
                  class="nb-slot-desc"
                >
                  {{ s.description }}
                </p>
              </div>
            </section>
          </div>
        </div>
      </template>
    </div>
  </Modal>
</template>

<style scoped>
/* Fixed content height so the window doesn't resize between grid and detail — the body scrolls within. */
.node-browser { display: flex; flex-direction: column; gap: 10px; height: min(70vh, 680px); min-height: 380px; }
.nb-search { flex: 0 0 auto; }
.nb-scroll { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding-right: 4px; }
.nb-empty { font-size: 12px; color: var(--fg-3); padding: 8px 4px; }

/* ─── Grid ──────────────────────────────────────────────────────────────────── */
.nb-group { margin-bottom: 18px; }
.nb-group-title { font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.08em; color: var(--fg-2); margin: 0 0 8px; }
.nb-subgroup-title { font-size: 10.5px; letter-spacing: 0.05em; color: var(--fg-3); margin: 10px 0 6px; font-weight: 500; }
.nb-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); gap: 8px; }
/* Same color language as the canvas cards: the category accent as a left border + tinted icon. */
.nb-card {
  display: flex; flex-direction: column; gap: 5px; text-align: left; cursor: pointer;
  padding: 9px 11px; border: 1px solid color-mix(in srgb, var(--node-accent, var(--fg-3)) 35%, transparent);
  border-left: 3px solid var(--node-accent, var(--fg-3));
  border-radius: 8px; background: none; color: var(--fg-1);
}
.nb-card:hover {
  background: color-mix(in oklch, var(--node-accent, var(--fg-3)) 8%, transparent);
  border-color: var(--node-accent, var(--fg-3));
  color: var(--fg-0);
}
.nb-card-head { display: flex; align-items: center; gap: 7px; min-width: 0; }
.nb-card-icon { flex: 0 0 auto; opacity: 0.85; }
.nb-card-label { font-size: 12.5px; font-weight: 600; color: var(--fg-0); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nb-card-desc {
  font-size: 11px; color: var(--fg-3); margin: 0; line-height: 1.35;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}

/* ─── Detail ────────────────────────────────────────────────────────────────── */
.nb-detail-bar { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex: 0 0 auto; }
.nb-back {
  display: inline-flex; align-items: center; gap: 6px; cursor: pointer;
  font-size: 12px; padding: 5px 10px; border-radius: 6px;
  color: var(--fg-2); background: none; border: 1px solid var(--line);
}
.nb-back:hover { color: var(--fg-0); border-color: var(--fg-3); }
.nb-detail-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.nb-detail-title { display: flex; align-items: center; gap: 9px; min-width: 0; flex-wrap: wrap; }
.nb-detail-icon { flex: 0 0 auto; opacity: 0.9; }
.nb-detail-name { font-size: 16px; font-weight: 600; color: var(--fg-0); }
.nb-detail-key { font-size: 11px; color: var(--fg-3); flex: 0 0 auto; }
.nb-chip {
  font-size: 10px; font-weight: 600; letter-spacing: 0.04em;
  color: var(--fg-2); background: var(--bg-2); padding: 2px 7px; border-radius: 4px;
}
.nb-chip-category { text-transform: uppercase; letter-spacing: 0.06em; color: var(--node-accent, var(--fg-2)); background: color-mix(in oklch, var(--node-accent, var(--fg-2)) 14%, transparent); }
.nb-desc { font-size: 13px; color: var(--fg-1); margin: 12px 0 0; max-width: 76ch; }

.nb-sections { display: flex; flex-direction: column; gap: 20px; margin-top: 16px; }
.nb-section-title { font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.08em; color: var(--fg-2); margin: 0 0 8px; }
.nb-slot { padding: 8px 10px; border: 1px solid var(--line); border-radius: 8px; margin-bottom: 6px; }
.nb-slot-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.nb-slot-name { font-size: 12.5px; font-weight: 600; color: var(--fg-0); font-family: var(--mono, monospace); }
.nb-slot-name.is-error { color: var(--err, #f87171); }
.nb-slot-type { font-size: 11.5px; color: var(--fg-2); }
.nb-slot-opt { font-size: 10px; color: var(--fg-3); border: 1px solid var(--line); padding: 1px 6px; border-radius: 4px; }
.nb-slot-req { font-size: 10px; color: var(--accent, var(--fg-2)); border: 1px solid color-mix(in oklch, var(--accent, var(--fg-2)) 40%, transparent); padding: 1px 6px; border-radius: 4px; }
.nb-slot-err { font-size: 10px; color: var(--err, #f87171); border: 1px solid color-mix(in oklch, var(--err, #f87171) 40%, transparent); padding: 1px 6px; border-radius: 4px; }
.nb-slot-desc { font-size: 12px; color: var(--fg-2); margin: 5px 0 0; max-width: 74ch; }
.nb-slot-structure { margin-top: 8px; border-top: 1px dashed var(--line); padding-top: 6px; }
</style>
