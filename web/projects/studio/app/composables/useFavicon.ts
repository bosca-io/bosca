import { accentHueShift } from '~/composables/useSubsystems'

const BOSCA_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" fill="none">
  <path d="M256 262L491 138L256 21L21 138L256 262Z" fill="#00c16a"/>
  <path d="M491 138L256 262V498L491 372V138Z" fill="#00dc82"/>
  <path d="M21 138L256 262V498L21 372V138Z" fill="#00a155"/>
</svg>`

export function useFavicon(subsystemId: Ref<string> | ComputedRef<string>) {
  if (!import.meta.client) return

  watch(subsystemId, (id) => {
    const hue = accentHueShift(id)
    const size = 64
    const canvas = document.createElement('canvas')
    canvas.width = size
    canvas.height = size
    const ctx = canvas.getContext('2d')!

    const img = new Image()
    const blob = new Blob([BOSCA_SVG], { type: 'image/svg+xml' })
    const url = URL.createObjectURL(blob)

    img.onload = () => {
      ctx.filter = `hue-rotate(${hue}deg)`
      ctx.drawImage(img, 0, 0, size, size)
      URL.revokeObjectURL(url)

      let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
      if (!link) {
        link = document.createElement('link')
        link.rel = 'icon'
        document.head.appendChild(link)
      }
      link.type = 'image/png'
      link.href = canvas.toDataURL('image/png')
    }
    img.src = url
  }, { immediate: true })
}
