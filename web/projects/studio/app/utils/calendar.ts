export function startOfWeek(date: Date): Date {
  const d = new Date(date)
  const day = d.getDay()
  const diff = day === 0 ? -6 : 1 - day
  d.setDate(d.getDate() + diff)
  d.setHours(0, 0, 0, 0)
  return d
}

export function startOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1)
}

export function addDays(date: Date, n: number): Date {
  const d = new Date(date)
  d.setDate(d.getDate() + n)
  return d
}

export function addMonths(date: Date, n: number): Date {
  const d = new Date(date)
  d.setMonth(d.getMonth() + n)
  return d
}

export function formatTime(date: Date): string {
  const h = date.getHours().toString().padStart(2, '0')
  const m = date.getMinutes().toString().padStart(2, '0')
  return `${h}:${m}`
}

export function formatDateShort(date: Date): string {
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
  return `${months[date.getMonth()]} ${date.getDate()}`
}

export function isSameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  )
}

export function getWeekDays(start: Date): Date[] {
  return Array.from({ length: 7 }, (_, i) => addDays(start, i))
}

export function getMonthGrid(date: Date): Date[][] {
  const first = startOfMonth(date)
  const gridStart = startOfWeek(first)
  const grid: Date[][] = []
  for (let week = 0; week < 6; week++) {
    const row: Date[] = []
    for (let day = 0; day < 7; day++) {
      row.push(addDays(gridStart, week * 7 + day))
    }
    grid.push(row)
  }
  return grid
}

export function snapToQuarter(minutes: number): number {
  return Math.round(minutes / 15) * 15
}

export function dateToIso(date: Date): string {
  return date.toISOString()
}

export function hourFromY(y: number, slotHeight: number): number {
  return Math.max(0, Math.min(23, Math.floor(y / slotHeight)))
}

export function minuteFromY(y: number, slotHeight: number): number {
  const totalMinutes = (y / slotHeight) * 60
  return snapToQuarter(Math.max(0, Math.min(1439, Math.round(totalMinutes))))
}
