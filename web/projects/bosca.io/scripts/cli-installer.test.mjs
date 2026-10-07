import assert from 'node:assert/strict'
import { spawn, execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

test('website config selects Bosca packages for the served installer', { timeout: 120_000 }, async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'bosca-website-installer-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  const script = await readFile(new URL('../../../../cli/install.sh', import.meta.url), 'utf8')
  const tools = join(root, 'tools')
  const source = join(root, 'source')
  await mkdir(tools)
  await mkdir(source)
  await writeFile(join(tools, 'uname'), '#!/bin/sh\ncase "$1" in -s) echo Linux;; -m) echo x86_64;; esac\n', { mode: 0o755 })
  await writeFile(join(tools, 'sudo'), '#!/bin/sh\nexit 99\n', { mode: 0o755 })
  const binary = '#!/bin/sh\necho bosca-fixture\n'
  await writeFile(join(source, 'bosca'), binary)
  const filename = 'bosca-6.10.0-linux-x86_64.tar.gz'
  const archive = join(root, filename)
  execFileSync('tar', ['-czf', archive, '-C', source, 'bosca'])
  const bytes = await readFile(archive)
  const checksum = createHash('sha256').update(bytes).digest('hex')
  const requests = []
  let outdated = false
  const artifacts = createServer((request, response) => {
    requests.push(request.url)
    if (request.url === '/raw/team/bosca-cli/6.10.0/install.sh') {
      response.end(outdated ? '#!/bin/sh\necho old-installer\n' : script)
    } else if (request.url === '/raw/team/api/bosca-cli' || request.url === '/raw/override/api/bosca-cli') {
      response.setHeader('Content-Type', 'application/json')
      response.end('{"name":"bosca-cli","versions":[{"version":"6.11.0-rc1","files":[]},{"version":"6.10.0","files":[]}]}')
    } else if (request.url.endsWith('/' + filename)) {
      response.end(bytes)
    } else if (request.url.endsWith('/SHA256SUMS')) {
      response.end(checksum + '  ' + filename + '\n')
    } else {
      response.writeHead(404).end()
    }
  })
  await listen(artifacts)
  t.after(() => close(artifacts))
  const artifactOrigin = 'http://127.0.0.1:' + artifacts.address().port

  const reservation = createServer()
  await listen(reservation)
  const port = reservation.address().port
  await close(reservation)
  const website = spawn('pnpm', ['exec', 'nuxt', 'dev', '--host', '127.0.0.1', '--port', String(port)], {
    cwd: fileURLToPath(new URL('..', import.meta.url)),
    detached: true,
    stdio: ['ignore', 'pipe', 'pipe'],
    env: {
      ...process.env,
      NUXT_CLI_INSTALL_SCRIPT_URL: artifactOrigin + '/raw/team/bosca-cli/6.10.0/install.sh'
    }
  })
  let logs = ''
  website.stdout.on('data', (chunk) => {
    logs += chunk.toString()
  })
  website.stderr.on('data', (chunk) => {
    logs += chunk.toString()
  })
  t.after(() => {
    if (website.exitCode === null) process.kill(-website.pid, 'SIGTERM')
  })
  const url = 'http://127.0.0.1:' + port + '/cli/install.sh'
  let response
  const deadline = Date.now() + 90_000
  while (Date.now() < deadline) {
    if (website.exitCode !== null) assert.fail('Nuxt exited before startup:\n' + logs)
    try {
      response = await fetch(url, { signal: AbortSignal.timeout(2000), redirect: 'manual' })
      if (response.status === 200) break
    } catch {
      // The server may not yet be listening.
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  assert.equal(response?.status, 200, logs)
  assert.match(response.headers.get('content-type'), /text\/x-shellscript/)
  const served = await response.text()
  assert.match(served, /export BOSCA_CLI_ARTIFACTS_URL/)

  await t.test('release metadata points at the installer repository and skips prereleases', async () => {
    const result = await fetch('http://127.0.0.1:' + port + '/cli/releases.json')
    assert.equal(result.status, 200)
    assert.equal(result.headers.get('cache-control'), 'no-store')
    assert.deepEqual(await result.json(), {
      version: '6.10.0',
      artifactsUrl: artifactOrigin + '/raw/team/bosca-cli'
    })
  })

  await t.test('default curl pipe flow installs from the configured artifact repository', async () => {
    const installed = join(root, 'installed-default')
    const result = await runInstaller(served, installed)
    assert.equal(result.code, 0, result.output)
    assert.equal(await readFile(join(installed, 'bosca'), 'utf8'), binary)
    assert.ok(requests.includes('/raw/team/api/bosca-cli'))
    assert.ok(requests.includes('/raw/team/bosca-cli/6.10.0/' + filename))
    assert.ok(requests.includes('/raw/team/bosca-cli/6.10.0/SHA256SUMS'))
    assert.doesNotMatch(result.output, /api\.github\.com/)
  })

  await t.test('an explicit client artifact repository still overrides the website default', async () => {
    const installed = join(root, 'installed-override')
    const result = await runInstaller(served, installed, artifactOrigin + '/raw/override/bosca-cli')
    assert.equal(result.code, 0, result.output)
    assert.equal(await readFile(join(installed, 'bosca'), 'utf8'), binary)
    assert.ok(requests.includes('/raw/override/api/bosca-cli'))
    assert.ok(requests.includes('/raw/override/bosca-cli/6.10.0/' + filename))
  })

  await t.test('a published installer without artifact support produces a clear HTTP error', async () => {
    outdated = true
    const result = await fetch(url)
    assert.equal(result.status, 502)
    assert.match(await result.text(), /configured installer lacks Bosca Artifacts support/)
  })

  async function runInstaller(body, installed, override) {
    const env = {
      ...process.env,
      PATH: tools + ':/usr/bin:/bin',
      BOSCA_INSTALL_DIR: installed
    }
    delete env.BOSCA_VERSION
    delete env.BOSCA_CLI_ARTIFACTS_URL
    delete env.BOSCA_CLI_ARTIFACTS_TOKEN
    delete env.GITHUB_TOKEN
    if (override) env.BOSCA_CLI_ARTIFACTS_URL = override
    const child = spawn('/bin/sh', [], { env })
    let output = ''
    child.stdout.on('data', (chunk) => {
      output += chunk.toString()
    })
    child.stderr.on('data', (chunk) => {
      output += chunk.toString()
    })
    const timer = setTimeout(() => child.kill('SIGKILL'), 15_000)
    child.stdin.end(body)
    return await new Promise((resolve, reject) => {
      child.on('error', reject)
      child.on('close', (code) => {
        clearTimeout(timer)
        resolve({ code, output })
      })
    })
  }
})

function listen(server) {
  return new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
}

function close(server) {
  server.closeAllConnections()
  return new Promise(resolve => server.close(resolve))
}
