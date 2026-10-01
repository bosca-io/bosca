import type { Ref } from 'vue'
import type { BrowsableType } from '~/components/pipelines/pipelineNodeTypes'

/**
 * Shared "is this type browsable, and open it" helper for the pipeline editor — used by both the inspector
 * type labels and a sub-pipeline's contract summary. Reads the `openTypeBrowser` opener and the type
 * catalog the editor page provided; a type is browsable when its base (with any `[]` array suffixes
 * stripped, so `ProjectRepository[][]` resolves to `ProjectRepository`) is a catalogued object type.
 */
export function usePipelineTypeBrowser() {
  const openTypeBrowser = inject<(name: string | null) => void>('openTypeBrowser')
  const catalog = inject<Ref<BrowsableType[]>>('pipelineTypeCatalog', ref([]))

  /** The base serial name, stripped of any trailing `[]` array suffixes. */
  function baseType(type: string | null): string {
    return (type ?? '').replace(/(\[\])+$/, '')
  }

  function canBrowse(type: string | null): boolean {
    const base = baseType(type)
    return !!openTypeBrowser && !!base && catalog.value.some(t => t.name === base)
  }

  function browse(type: string | null): void {
    if (openTypeBrowser) openTypeBrowser(baseType(type))
  }

  return { canBrowse, browse }
}
