export function formatMs(ms: number): string {
  const abs = Math.abs(ms)
  const totalSeconds = Math.floor(abs / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  const tenths = Math.floor((abs % 1000) / 100)
  const sign = ms < 0 ? '-' : ''
  return `${sign}${minutes}:${seconds.toString().padStart(2, '0')}.${tenths}`
}

export function parseMs(str: string): number | null {
  const trimmed = str.trim()
  if (!trimmed) return null
  const match = trimmed.match(/^(-?)(\d+):(\d{1,2})(?:\.(\d{1,3}))?$/)
  if (!match) return null
  const sign = match[1] === '-' ? -1 : 1
  const minutes = parseInt(match[2]!, 10)
  const seconds = parseInt(match[3]!, 10)
  if (seconds >= 60) return null
  const millis = match[4] ? parseInt(match[4].padEnd(3, '0'), 10) : 0
  return sign * (minutes * 60000 + seconds * 1000 + millis)
}

export function typeColor(typeId: string): string {
  let hash = 0
  for (let i = 0; i < typeId.length; i++) {
    hash = typeId.charCodeAt(i) + ((hash << 5) - hash)
  }
  const h = Math.abs(hash) % 360
  return `hsl(${h}, 60%, 50%)`
}
