const PALETTE = [
  '#3b9eff',
  '#2563eb',
  '#10b981',
  '#f59e0b',
  '#ec4899',
  '#8b5cf6',
  '#ef4444',
  '#06b6d4',
  '#f97316',
  '#6366f1',
]

export function makePalette(count: number, accent?: string): string[] {
  const base = accent ?? PALETTE[0]!
  if (count <= 1) return [base]
  const colors = [base]
  for (let i = 1; i < count; i++) {
    colors.push(PALETTE[i % PALETTE.length]!)
  }
  return colors
}
