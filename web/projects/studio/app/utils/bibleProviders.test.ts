import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { buildSchema, parse, print, validate, type DocumentNode } from 'graphql'
import { describe, expect, it } from 'vitest'
import * as documents from './bibleProviders'

const snapshot = readFileSync(resolve('schema.graphqls'), 'utf8')
const schema = buildSchema(snapshot)
const api = parse(readFileSync(resolve('../../../content/content/src/main/resources/graphql/metadata/bible-providers.graphqls'), 'utf8'))

describe('Bible provider GraphQL contracts', () => {
  it.each(Object.entries(documents))('validates %s against the Studio schema', (_name, document) => {
    expect(validate(schema, document as DocumentNode)).toEqual([])
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
