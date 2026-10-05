import { describe, expect, it } from 'vitest'
import { SUBSYSTEMS } from './useSubsystems'

describe('campaign subsystem ownership', () => {
  it('places Campaigns under Communications instead of Audience', () => {
    const communications = SUBSYSTEMS.find(subsystem => subsystem.id === 'communications')
    const audience = SUBSYSTEMS.find(subsystem => subsystem.id === 'audience')

    expect(communications?.nav.flatMap(group => group.items).some(item => item.id === 'campaigns')).toBe(true)
    expect(audience?.nav.flatMap(group => group.items).some(item => item.id === 'campaigns')).toBe(false)
  })
})

describe('recommendations subsystem navigation', () => {
  it('exposes recommendation context management', () => {
    const recommendations = SUBSYSTEMS.find(subsystem => subsystem.id === 'recommendations')

    expect(recommendations?.nav.flatMap(group => group.items).some(item => item.id === 'contexts')).toBe(true)
  })
})

describe('localization subsystem navigation', () => {
  it('exposes language mappings separately from locales', () => {
    const localization = SUBSYSTEMS.find(subsystem => subsystem.id === 'localization')
    const items = localization?.nav.flatMap(group => group.items) ?? []

    expect(items.some(item => item.id === 'languages' && item.label === 'Locales')).toBe(true)
    expect(items.some(item => item.id === 'language-mappings' && item.label === 'Language Mappings')).toBe(true)
  })
})
