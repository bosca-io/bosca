import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { copyFile, mkdir, mkdtemp, readFile, rm, stat, utimes, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import { afterEach, test } from 'node:test'
import { parse } from 'graphql'

const execFileAsync = promisify(execFile)
const normalizerPath = fileURLToPath(new URL('./normalize-generated-schema.mjs', import.meta.url))
const temporaryDirectories = []

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map(path => rm(path, { recursive: true, force: true })))
})

async function createFixture(schema) {
  const directory = await mkdtemp(join(tmpdir(), 'studio-schema-normalizer-'))
  temporaryDirectories.push(directory)

  const scriptPath = join(directory, 'scripts', 'normalize-generated-schema.mjs')
  const schemaPath = join(directory, 'schema.graphqls')
  await mkdir(dirname(scriptPath))
  await copyFile(normalizerPath, scriptPath)

  if (schema !== undefined) {
    await writeFile(schemaPath, schema)
  }

  return { schemaPath, scriptPath }
}

test('removes whitespace-only lines without changing description content or line endings', async () => {
  for (const lineEnding of ['\n', '\r\n']) {
    const input = [
      'type Query {',
      '  """',
      '  Line one  ',
      '  ',
      '  Line two\t',
      '  """',
      '  value: String',
      '}',
      '',
    ].join(lineEnding)
    const expected = input.replace(`${lineEnding}  ${lineEnding}`, `${lineEnding}${lineEnding}`)
    const { schemaPath, scriptPath } = await createFixture(input)

    await execFileAsync(process.execPath, [scriptPath])
    assert.equal(await readFile(schemaPath, 'utf8'), expected)
    assert.equal(
      parse(expected).definitions[0].fields[0].description.value,
      parse(input).definitions[0].fields[0].description.value,
    )

    await execFileAsync(process.execPath, [scriptPath])
    assert.equal(await readFile(schemaPath, 'utf8'), expected)
  }
})

test('does not rewrite an already normalized schema', async () => {
  const { schemaPath, scriptPath } = await createFixture('type Query { value: String }\n')
  const timestamp = new Date('2020-01-01T00:00:00.000Z')
  await utimes(schemaPath, timestamp, timestamp)

  await execFileAsync(process.execPath, [scriptPath])

  assert.equal((await stat(schemaPath)).mtimeMs, timestamp.getTime())
})

test('fails when the generated schema is missing', async () => {
  const { scriptPath } = await createFixture()

  await assert.rejects(
    execFileAsync(process.execPath, [scriptPath]),
    error => error.code !== 0 && /ENOENT/.test(error.stderr),
  )
})
