/**
 * Reads fixtures.json and outputs WireCompatFixtures.kt with all hex string constants.
 *
 * Usage: node generate-kotlin.mjs > ../src/commonTest/kotlin/yks/WireCompatFixtures.kt
 */
import { readFileSync } from 'fs'

const fixtures = JSON.parse(readFileSync('./fixtures.json', 'utf8'))

function toConstName(section, name, key) {
  // Convert camelCase/snake_case to UPPER_SNAKE_CASE
  const parts = [section, name, key].map(s =>
    s.replace(/([a-z])([A-Z])/g, '$1_$2')
      .replace(/[^a-zA-Z0-9]/g, '_')
      .toUpperCase()
  )
  return parts.join('_')
}

function escapeKotlin(str) {
  return str.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\$/g, '\\$')
}

const lines = []
lines.push('package yks')
lines.push('')
lines.push(`/**`)
lines.push(` * Generated from yjs ${fixtures.metadata.yjsVersion} on ${fixtures.metadata.generatedAt}.`)
lines.push(` * DO NOT EDIT — regenerate with: cd fixtures && node generate.mjs > fixtures.json && node generate-kotlin.mjs`)
lines.push(` */`)
lines.push('@Suppress("all")')
lines.push('object WireCompatFixtures {')

for (const [section, entries] of Object.entries(fixtures)) {
  if (section === 'metadata') continue

  lines.push('')
  lines.push(`    // ── ${section} ──`)

  for (const [name, data] of Object.entries(entries)) {
    for (const [key, value] of Object.entries(data)) {
      if (typeof value === 'string') {
        const constName = toConstName(section, name, key)
        lines.push(`    const val ${constName} = "${escapeKotlin(value)}"`)
      } else if (typeof value === 'number' || typeof value === 'boolean') {
        const constName = toConstName(section, name, key)
        lines.push(`    const val ${constName} = ${value}`)
      }
      // Skip objects/arrays — expected state is verified in test logic, not as constants
    }
  }
}

lines.push('}')
lines.push('')

process.stdout.write(lines.join('\n'))
