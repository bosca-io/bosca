<script lang="ts" setup>
import { Cropper } from 'vue-advanced-cropper'
import 'vue-advanced-cropper/dist/style.css'
import type { AttributeState } from '~/utils/editor/attribute'

interface CropCoordinates {
  width: number
  height: number
  left: number
  top: number
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function debounce<T extends (...args: any[]) => void>(fn: T, ms: number): T {
  let timer: ReturnType<typeof setTimeout> | null = null
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return ((...args: any[]) => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => fn(...args), ms)
  }) as unknown as T
}

function coordsMatch(a: CropCoordinates | undefined, b: CropCoordinates | undefined): boolean {
  if (!a || !b) return false
  return (
    Math.abs(a.width - b.width) < 0.5 &&
    Math.abs(a.height - b.height) < 0.5 &&
    Math.abs(a.left - b.left) < 0.5 &&
    Math.abs(a.top - b.top) < 0.5
  )
}

const props = defineProps<{
  attribute: AttributeState
  aspectRatio: number | null
  editable: boolean
}>()

const emit = defineEmits<{
   
  (_e: 'cropChange', _coords: CropCoordinates): void
}>()

const cropperRef = ref<InstanceType<typeof Cropper> | null>(null)
const ready = ref(false)
const lastMetadataId = ref<string | null>(null)
const metadata = ref(props.attribute.metadata)
const stableImageId = ref(props.attribute.metadata?.id ?? null)

const imageUrl = computed(() => {
  const id = stableImageId.value
  if (!id) return ''
  return `/content/image/${id}`
})

function enableUserChanges() {
  setTimeout(() => {
    ready.value = true
  }, 300)
}

interface CropperInstance {
  getResult: () => { coordinates: CropCoordinates; visibleArea: CropCoordinates } | undefined
   
  setCoordinates: (_coords: CropCoordinates) => void
   
  zoom: (_factor: number) => void
}

function getCropper(): CropperInstance | null {
  return cropperRef.value as unknown as CropperInstance | null
}

const onCropChange = debounce(({ coordinates }: { coordinates: CropCoordinates }) => {
  if (!ready.value) return
  const meta = props.attribute.metadata
  if (!meta) return
  if (coordsMatch(meta.attributes?.crop as CropCoordinates | undefined, coordinates)) return
  meta.attributes = { ...meta.attributes, crop: coordinates }
  // eslint-disable-next-line vue/no-mutating-props
  props.attribute.metadata = meta
  emit('cropChange', coordinates)
}, 250)

function onReady() {
  const meta = props.attribute.metadata
  if (!meta || lastMetadataId.value === meta.id) {
    enableUserChanges()
    return
  }
  lastMetadataId.value = meta.id

  if (meta.attributes?.crop) {
    enableUserChanges()
    return
  }

  setTimeout(() => {
    const results = getCropper()?.getResult()
    if (!results) {
      enableUserChanges()
      return
    }
    const { visibleArea } = results
    const width = visibleArea.width
    const height = props.aspectRatio ? width / props.aspectRatio : visibleArea.height
    const left = visibleArea.left
    const top = visibleArea.top + (visibleArea.height - height) / 2
    const coordinates: CropCoordinates = { width, height, left, top }

    getCropper()?.setCoordinates(coordinates)
    meta.attributes = { ...meta.attributes, crop: coordinates }
    enableUserChanges()
  }, 500)
}

function onMetadataChanged() {
  metadata.value = props.attribute.metadata
  const newId = metadata.value?.id ?? null
  if (newId !== stableImageId.value) {
    stableImageId.value = newId
  }
}

onMounted(() => {
  props.attribute.addListener(onMetadataChanged)
  if (props.attribute.metadata?.attributes?.crop && cropperRef.value) {
    getCropper()?.setCoordinates(props.attribute.metadata.attributes.crop as CropCoordinates)
  }
  setTimeout(() => {
    if (!ready.value) ready.value = true
  }, 10000)
})

onUnmounted(() => {
  props.attribute.removeListener(onMetadataChanged)
})

defineExpose({
  zoom(factor: number) {
    getCropper()?.zoom(factor)
  },
  maximize() {
    const result = getCropper()?.getResult()
    if (!result) return
    const { visibleArea } = result
    getCropper()?.setCoordinates(visibleArea)
    const meta = props.attribute.metadata
    if (!meta) return
    meta.attributes = {
      ...meta.attributes,
      crop: getCropper()?.getResult()?.coordinates,
    }
    // eslint-disable-next-line vue/no-mutating-props
    props.attribute.metadata = meta
  },
  getResult() {
    return getCropper()?.getResult()
  },
  setCoordinates(coords: CropCoordinates) {
    getCropper()?.setCoordinates(coords)
  },
})
</script>

<template>
  <Cropper
    v-if="imageUrl"
    :key="stableImageId"
    ref="cropperRef"
    class="image-cropper"
    :default-position="metadata?.attributes?.crop"
    :default-transforms="metadata?.attributes?.crop"
    :resize-image="{ wheel: false }"
    :stencil-props="{ aspectRatio: aspectRatio }"
    :src="imageUrl"
    @change="onCropChange"
    @ready="onReady"
  />
</template>

<style scoped>
.image-cropper {
  width: 100%;
  min-height: 200px;
  border-radius: var(--r-md);
  overflow: hidden;
}
</style>
