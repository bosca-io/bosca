import { readFile, writeFile } from 'node:fs/promises'

const schemaUrl = new URL('../schema.graphqls', import.meta.url)
const schema = await readFile(schemaUrl, 'utf8')
const normalizedSchema = schema.replace(/^ +(?=\r?$)/gm, '')

if (normalizedSchema !== schema) {
  await writeFile(schemaUrl, normalizedSchema)
}
