// Installed adapter negotiation only. No session creation, inference, or provider credentials.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { setTimeout as delay } from 'node:timers/promises';

const installation = resolve(process.argv[2]);
const descriptor = JSON.parse(await readFile(join(installation, 'compatibility.json')));
let adapter;
for (const reference of [descriptor.adapter, descriptor.harness]) {
  const separator = reference.lastIndexOf('@');
  const name = reference.slice(0, separator), version = reference.slice(separator + 1);
  const metadata = JSON.parse(await readFile(join(installation, 'node_modules', name, 'package.json')));
  assert.equal(metadata.name, name);
  assert.equal(metadata.version, version);
  if (reference === descriptor.adapter) adapter = { name, metadata };
}
const home = await mkdtemp(join(tmpdir(), 'bosca-adapter-negotiation-'));
await Promise.all(['codex', 'claude', 'scratch'].map(path => mkdir(join(home, path))));
const child = spawn(process.execPath, [join(installation, 'node_modules', adapter.name, Object.values(adapter.metadata.bin)[0])], {
  detached: true,
  env: { PATH: process.env.PATH, HOME: home, CODEX_HOME: join(home, 'codex'), CLAUDE_CONFIG_DIR: join(home, 'claude'),
    CLAUDE_CODE_TMPDIR: join(home, 'scratch'), NO_BROWSER: '1', DISABLE_AUTOUPDATER: '1' },
  stdio: ['pipe', 'pipe', 'pipe']
});
let diagnostics = '';
child.stderr.on('data', chunk => { diagnostics = (diagnostics + chunk).slice(-4096); });
const exit = new Promise(resolve => child.once('exit', (code, signal) => resolve({ code, signal })));
const lines = createInterface({ input: child.stdout })[Symbol.asyncIterator]();
try {
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {
    protocolVersion: 1, clientCapabilities: {}, clientInfo: { name: 'bosca-contract-check', version: '1' }
  } }) + '\n');
  const negotiation = async () => {
    for (;;) {
      const line = await lines.next();
      assert(!line.done, 'Adapter exited before initialization: ' + diagnostics);
      const message = JSON.parse(line.value);
      if (message.id !== 1) continue;
      assert(!message.error, JSON.stringify(message.error));
      assert.equal(message.result.protocolVersion, descriptor.acpVersion);
      assert.equal(message.result.agentInfo.version, adapter.metadata.version);
      assert.equal(message.result.agentCapabilities.loadSession, true);
      assert(Array.isArray(message.result.authMethods));
      return message.result;
    }
  };
  await Promise.race([negotiation(), delay(30000, undefined, { ref: false }).then(() => { throw new Error('Adapter initialize timed out: ' + diagnostics); })]);
  console.log(`${descriptor.adapter}: installed identity and ACP initialization passed; no provider session was opened`);
} finally {
  child.stdin.end();
  if (child.exitCode === null && child.signalCode === null) process.kill(-child.pid, 'SIGTERM');
  await Promise.race([exit, delay(5000, undefined, { ref: false }).then(() => {
    if (child.exitCode === null && child.signalCode === null) process.kill(-child.pid, 'SIGKILL');
    return exit;
  })]);
  await rm(home, { recursive: true, force: true });
}
