<script setup lang="ts">
import type { PipelineOption } from '~/components/pipelines/pipelineNodeTypes'

/**
 * Read-only summary of a picked pipeline's declared contract (accepts / fields / outputs), shown under a
 * Run Pipeline (or For Each) node's pipeline reference. Extracted from the old per-kind inspector so the
 * contract adornment survives the move to a data-driven settings form.
 */
defineProps<{ pipeline: PipelineOption }>()

// Makes a declared contract type open the Type Browser (element type for arrays), when it's catalogued.
const { canBrowse, browse } = usePipelineTypeBrowser()

/** The readable leaf of the accepted event's FQDN, "JSON", "object" for an inline shape, or a named shape. */
function contractInputLabel(p: PipelineOption): string {
  if (p.acceptedInputType === 'JSON') return 'JSON'
  if (p.acceptedInputType === 'SHAPE') return 'object'
  if (p.acceptedInputType.startsWith('shape:')) return p.acceptedInputType.slice('shape:'.length)
  const leaf = p.acceptedInputType.includes('.')
    ? p.acceptedInputType.slice(p.acceptedInputType.lastIndexOf('.') + 1)
    : p.acceptedInputType
  return leaf || p.acceptedInputType
}

/** The readable declared output: "nothing", "undeclared", "JSON", "object", a named shape, or a type's short name (with `[]` for a list). */
function contractOutputLabel(p: PipelineOption): string {
  if (!p.hasOutput) return 'nothing'
  if (!p.outputType) return 'undeclared'
  if (p.outputType === 'JSON') return 'JSON'
  if (p.outputType === 'SHAPE') return 'object'
  if (p.outputType.startsWith('shape:')) return p.outputType.slice('shape:'.length)
  const isArray = p.outputType.endsWith('[]')
  const base = isArray ? p.outputType.slice(0, -2) : p.outputType
  const short = base.includes('.') ? base.slice(base.lastIndexOf('.') + 1) : base
  return isArray ? `${short}[]` : short
}

/** The short label for a shape field's type (a serial name, optionally `[]`-suffixed, or a primitive). */
function shapeFieldType(type: string): string {
  const isArray = type.endsWith('[]')
  const base = isArray ? type.slice(0, -2) : type
  const short = base.includes('.') ? base.slice(base.lastIndexOf('.') + 1) : base
  return isArray ? `${short}[]` : short
}

/** Top-level fields of a contract schema, for the summary list. */
function contractFields(schema: Record<string, unknown> | null): { name: string, type: string, required: boolean }[] {
  if (!schema) return []
  const properties = schema.properties
  if (properties == null || typeof properties !== 'object' || Array.isArray(properties)) return []
  const required = Array.isArray(schema.required) ? schema.required.filter((r): r is string => typeof r === 'string') : []
  return Object.entries(properties as Record<string, unknown>).map(([name, prop]) => {
    const type = prop != null && typeof prop === 'object' && !Array.isArray(prop) && typeof (prop as Record<string, unknown>).type === 'string'
      ? String((prop as Record<string, unknown>).type)
      : 'any'
    return { name, type, required: required.includes(name) }
  })
}
</script>

<template>
  <div class="contract">
    <p
      v-if="pipeline.description"
      class="hint"
    >
      {{ pipeline.description }}
    </p>
    <div class="contract-row">
      <span class="contract-label">Accepts</span>
      <button
        v-if="canBrowse(pipeline.acceptedInputType)"
        type="button"
        class="contract-value contract-link"
        @click="browse(pipeline.acceptedInputType)"
      >{{ contractInputLabel(pipeline) }}</button>
      <code
        v-else
        class="contract-value"
      >{{ contractInputLabel(pipeline) }}</code>
    </div>
    <ul
      v-if="contractFields(pipeline.inputSchema).length"
      class="contract-fields"
    >
      <li
        v-for="f in contractFields(pipeline.inputSchema)"
        :key="f.name"
      >
        <code>{{ f.name }}</code> <span class="contract-type">{{ f.type }}</span>
        <span
          v-if="f.required"
          class="contract-required"
        >required</span>
      </li>
    </ul>
    <ul
      v-if="pipeline.inputFields?.length"
      class="contract-fields"
    >
      <li
        v-for="f in pipeline.inputFields"
        :key="f.name"
      >
        <code>{{ f.name }}</code> <span class="contract-type">{{ shapeFieldType(f.type) }}</span>
      </li>
    </ul>
    <div class="contract-row">
      <span class="contract-label">Outputs</span>
      <button
        v-if="pipeline.hasOutput && canBrowse(pipeline.outputType)"
        type="button"
        class="contract-value contract-link"
        @click="browse(pipeline.outputType)"
      >{{ contractOutputLabel(pipeline) }}</button>
      <code
        v-else
        class="contract-value"
      >{{ contractOutputLabel(pipeline) }}</code>
    </div>
    <ul
      v-if="pipeline.hasOutput && pipeline.outputFields?.length"
      class="contract-fields"
    >
      <li
        v-for="f in pipeline.outputFields"
        :key="f.name"
      >
        <code>{{ f.name }}</code> <span class="contract-type">{{ shapeFieldType(f.type) }}</span>
      </li>
    </ul>
    <p
      v-if="!pipeline.hasOutput"
      class="hint warn"
    >
      This pipeline has no Output node — this node produces no output, so its downstream nodes will be skipped.
    </p>
  </div>
</template>

<style scoped>
.contract {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px;
  border: 1px solid var(--border, rgba(148, 163, 184, 0.15));
  border-radius: 8px;
}
.contract-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.contract-label {
  color: var(--text-muted, #94a3b8);
  min-width: 56px;
}
.contract-value {
  font-size: 11.5px;
}
.contract-link {
  font-family: var(--font-mono, monospace);
  background: none;
  border: none;
  padding: 0;
  cursor: pointer;
  color: var(--accent, #f59e0b);
  border-bottom: 1px dotted currentColor;
}
.contract-link:hover {
  color: var(--text, #e2e8f0);
}
.contract-fields {
  margin: 0;
  padding-left: 18px;
  font-size: 11.5px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.contract-type {
  color: var(--text-muted, #94a3b8);
}
.contract-required {
  margin-left: 6px;
  font-size: 10px;
  color: var(--warning, #fbbf24);
}
.hint {
  margin: 0;
  font-size: 11px;
  color: var(--text-muted, #94a3b8);
}
.hint.warn {
  color: var(--warning, #fbbf24);
}
</style>
