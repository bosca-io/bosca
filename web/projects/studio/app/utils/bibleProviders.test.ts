import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { buildSchema, parse, print, validate, type DocumentNode } from 'graphql'
import { describe, expect, it } from 'vitest'
import * as exported from './bibleProviders'
import { audioTargetKey, providerLabel } from './bibleProviders'

const snapshot = readFileSync(resolve('schema.graphqls'), 'utf8')
const schema = buildSchema(snapshot)
const documents = Object.entries(exported).filter((entry): entry is [string, DocumentNode] =>
  typeof entry[1] === 'object' && entry[1] !== null && (entry[1] as DocumentNode).kind === 'Document')
const api = parse(readFileSync(resolve('../../../content/content/src/main/resources/graphql/metadata/bible-providers.graphqls'), 'utf8'))

describe('Bible provider GraphQL contracts', () => {
  it('finds every operation document', () => {
    expect(documents.map(([name]) => name)).toContain('saveConfigurationMutation')
    expect(documents).toHaveLength(11)
  })

  it.each(documents)('validates %s against the Studio schema', (_name, document) => {
    expect(validate(schema, document)).toEqual([])
  })

  it('keeps the administration schema snapshot aligned with the server contract', () => {
    for (const definition of api.definitions) {
      if (definition.kind === 'ObjectTypeDefinition' || definition.kind === 'InputObjectTypeDefinition' || definition.kind === 'ObjectTypeExtension') {
        const type = schema.getType(definition.name.value)
        if (!type || !('getFields' in type)) throw new Error(`Missing type ${definition.name.value}`)
        for (const field of definition.fields ?? []) {
          const actual = type.getFields()[field.name.value]
          if (!actual) throw new Error(`Missing field ${definition.name.value}.${field.name.value}`)
          expect(String(actual.type)).toBe(print(field.type))
          if ('args' in actual && 'arguments' in field) {
            expect(actual.args.map(argument => `${argument.name}: ${argument.type}`).sort())
              .toEqual((field.arguments ?? []).map(argument => `${argument.name.value}: ${print(argument.type)}`).sort())
          }
        }
      }
    }
  })
})

describe('Bible provider display helpers', () => {
  it('labels known providers and falls back to the provider identifier', () => {
    expect(providerLabel('API_BIBLE')).toBe('API.Bible')
    expect(providerLabel('OTHER_PROVIDER')).toBe('OTHER_PROVIDER')
  })

  it('keys audio targets by Bible, version, and variant', () => {
    expect(audioTargetKey({ metadataId: 'metadata-1', version: 2, variant: 'study' })).toBe('metadata-1:2:study')
    expect(audioTargetKey({ metadataId: 'metadata-1', version: 2, variant: 'reader' }))
      .not.toBe(audioTargetKey({ metadataId: 'metadata-1', version: 2, variant: 'study' }))
  })
})
