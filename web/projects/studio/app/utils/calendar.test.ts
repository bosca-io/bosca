import { describe, it, expect } from 'vitest'
import {
  startOfWeek, startOfMonth, addDays, addMonths,
  formatTime, formatDateShort, isSameDay,
  getWeekDays, getMonthGrid, snapToQuarter,
  hourFromY, minuteFromY,
} from './calendar'

describe('startOfWeek', () => {
  it('returns Monday for a Wednesday', () => {
    const wed = new Date(2026, 4, 13) // May 13 2026 is Wednesday
    const mon = startOfWeek(wed)
    expect(mon.getDay()).toBe(1) // Monday
    expect(mon.getDate()).toBe(11)
  })

  it('returns same day for Monday', () => {
    const mon = new Date(2026, 4, 11)
    expect(startOfWeek(mon).getDate()).toBe(11)
  })

  it('returns previous Monday for Sunday', () => {
    const sun = new Date(2026, 4, 17)
    expect(startOfWeek(sun).getDate()).toBe(11)
  })
})

describe('startOfMonth', () => {
  it('returns first day of month', () => {
    const d = new Date(2026, 4, 15)
    const first = startOfMonth(d)
    expect(first.getDate()).toBe(1)
    expect(first.getMonth()).toBe(4)
  })
})

describe('addDays', () => {
  it('adds positive days', () => {
    const d = new Date(2026, 0, 1)
    expect(addDays(d, 5).getDate()).toBe(6)
  })

  it('adds negative days', () => {
    const d = new Date(2026, 0, 10)
    expect(addDays(d, -3).getDate()).toBe(7)
  })

  it('crosses month boundary', () => {
    const d = new Date(2026, 0, 30)
    const result = addDays(d, 3)
    expect(result.getMonth()).toBe(1) // February
    expect(result.getDate()).toBe(2)
  })
})

describe('addMonths', () => {
  it('adds months', () => {
    const d = new Date(2026, 0, 15)
    expect(addMonths(d, 2).getMonth()).toBe(2)
  })

  it('wraps year', () => {
    const d = new Date(2026, 11, 1)
    const result = addMonths(d, 2)
    expect(result.getFullYear()).toBe(2027)
    expect(result.getMonth()).toBe(1)
  })
})

describe('formatTime', () => {
  it('formats hours and minutes with padding', () => {
    const d = new Date(2026, 0, 1, 9, 5)
    expect(formatTime(d)).toBe('09:05')
  })

  it('formats midnight', () => {
    const d = new Date(2026, 0, 1, 0, 0)
    expect(formatTime(d)).toBe('00:00')
  })

  it('formats afternoon', () => {
    const d = new Date(2026, 0, 1, 14, 30)
    expect(formatTime(d)).toBe('14:30')
  })
})

describe('formatDateShort', () => {
  it('formats with month abbreviation', () => {
    const d = new Date(2026, 4, 15)
    expect(formatDateShort(d)).toBe('May 15')
  })

  it('formats January', () => {
    const d = new Date(2026, 0, 1)
    expect(formatDateShort(d)).toBe('Jan 1')
  })
})

describe('isSameDay', () => {
  it('returns true for same day', () => {
    const a = new Date(2026, 4, 15, 10, 0)
    const b = new Date(2026, 4, 15, 20, 30)
    expect(isSameDay(a, b)).toBe(true)
  })

  it('returns false for different days', () => {
    const a = new Date(2026, 4, 15)
    const b = new Date(2026, 4, 16)
    expect(isSameDay(a, b)).toBe(false)
  })
})

describe('getWeekDays', () => {
  it('returns 7 consecutive days', () => {
    const start = new Date(2026, 4, 11)
    const days = getWeekDays(start)
    expect(days.length).toBe(7)
    expect(days[0]!.getDate()).toBe(11)
    expect(days[6]!.getDate()).toBe(17)
  })
})

describe('getMonthGrid', () => {
  it('returns 6 rows of 7 days', () => {
    const grid = getMonthGrid(new Date(2026, 4, 1))
    expect(grid.length).toBe(6)
    for (const row of grid) {
      expect(row.length).toBe(7)
    }
  })

  it('first cell is a Monday', () => {
    const grid = getMonthGrid(new Date(2026, 4, 1))
    expect(grid[0]![0]!.getDay()).toBe(1)
  })

  it('contains the first day of the month', () => {
    const grid = getMonthGrid(new Date(2026, 4, 1))
    const allDays = grid.flat()
    const may1 = allDays.find((d) => d.getMonth() === 4 && d.getDate() === 1)
    expect(may1).toBeDefined()
  })
})

describe('snapToQuarter', () => {
  it('snaps to nearest 15 minutes', () => {
    expect(snapToQuarter(0)).toBe(0)
    expect(snapToQuarter(7)).toBe(0)
    expect(snapToQuarter(8)).toBe(15)
    expect(snapToQuarter(22)).toBe(15)
    expect(snapToQuarter(23)).toBe(30)
    expect(snapToQuarter(37)).toBe(30)
    expect(snapToQuarter(38)).toBe(45)
    expect(snapToQuarter(52)).toBe(45)
    expect(snapToQuarter(53)).toBe(60)
  })
})

describe('hourFromY', () => {
  it('converts Y position to hour', () => {
    expect(hourFromY(0, 48)).toBe(0)
    expect(hourFromY(48, 48)).toBe(1)
    expect(hourFromY(576, 48)).toBe(12)
  })

  it('clamps to 0-23', () => {
    expect(hourFromY(-10, 48)).toBe(0)
    expect(hourFromY(10000, 48)).toBe(23)
  })
})

describe('minuteFromY', () => {
  it('converts Y position to snapped minutes', () => {
    expect(minuteFromY(0, 48)).toBe(0)
    expect(minuteFromY(24, 48)).toBe(30)
    expect(minuteFromY(48, 48)).toBe(60)
  })

  it('snaps to 15 minute intervals', () => {
    expect(minuteFromY(12, 48)).toBe(15)
  })
})
