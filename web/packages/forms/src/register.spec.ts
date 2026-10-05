import { describe, it, expect } from 'vitest'
import { getControl, getRegisteredControls } from './controls'

// Importing register.ts triggers the side-effect of registering all built-in controls
import './register'

describe('register (built-in controls)', () => {
  const expectedControls = [
    'text-input',
    'textarea',
    'number-input',
    'select',
    'checkbox',
    'switch',
    'date-picker',
    'tag-input',
    'radio-group',
    'color-picker',
    'image-upload',
    'file-upload',
    'graphql-select',
    'api-script-select',
  ]

  it('registers all built-in controls', () => {
    const registered = getRegisteredControls()
    for (const name of expectedControls) {
      expect(registered).toContain(name)
    }
  })

  it.each(expectedControls)('registers %s as a valid component', (name) => {
    const control = getControl(name)
    expect(control).toBeDefined()
  })
})
