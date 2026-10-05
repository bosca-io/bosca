import { describe, it, expect, beforeEach } from 'vitest'
import { registerControl, getControl, getRegisteredControls } from './controls'
import { defineComponent } from 'vue'

const DummyA = defineComponent({ template: '<div>A</div>' })
const DummyB = defineComponent({ template: '<div>B</div>' })

describe('control registry', () => {
  beforeEach(() => {
    // Clear registry by re-registering known test controls to overwrite
    // (registry is a module-level Map, so we work with it as-is)
  })

  it('registers and retrieves a control', () => {
    registerControl('test-a', DummyA)
    expect(getControl('test-a')).toBe(DummyA)
  })

  it('returns undefined for an unregistered control', () => {
    expect(getControl('nonexistent-widget')).toBeUndefined()
  })

  it('replaces an existing control when re-registered', () => {
    registerControl('replaceable', DummyA)
    registerControl('replaceable', DummyB)
    expect(getControl('replaceable')).toBe(DummyB)
  })

  it('lists all registered control names', () => {
    registerControl('list-test-1', DummyA)
    registerControl('list-test-2', DummyB)
    const names = getRegisteredControls()
    expect(names).toContain('list-test-1')
    expect(names).toContain('list-test-2')
  })

  it('returns an array from getRegisteredControls', () => {
    expect(Array.isArray(getRegisteredControls())).toBe(true)
  })
})
