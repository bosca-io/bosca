// Local native/JVM CLI smoke check. No provider or deployed Bosca calls.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createInterface } from 'node:readline';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const binary = resolve(process.argv[2]);
const family = process.argv[3] ?? 'codex';
assert(['codex', 'claude'].includes(family));
const descriptor = JSON.parse(await readFile(new URL(family === 'claude' ? '../claude/compatibility.json' : '../compatibility.json', import.meta.url)));
const root = await mkdtemp(join(tmpdir(), 'bosca-runtime-cli-'));
await writeFile(join(root, 'compatibility.json'), JSON.stringify(descriptor));
const token = randomUUID() + randomUUID(), session = randomUUID();
const processes = new Set();
const methods = [];
const secret = 'fixture-external-secret';
const mcp = createServer(async (request, response) => {
  assert.equal(request.headers.authorization, 'Bearer ' + secret);
  if (request.method === 'GET') { response.writeHead(405).end(); return; }
  if (request.method === 'DELETE') { response.writeHead(200).end(); return; }
  const parts = []; for await (const part of request) parts.push(part);
  const message = JSON.parse(Buffer.concat(parts).toString());
  methods.push(message.method);
  if (!('id' in message)) { response.writeHead(202).end(); return; }
  const result = message.method === 'initialize'
    ? { protocolVersion: message.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } }
    : { tools: [] };
  response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ jsonrpc: '2.0', id: message.id, result }));
});
await new Promise(resolve => mcp.listen(0, '127.0.0.1', resolve));
const mcpUrl = `http://127.0.0.1:${mcp.address().port}/mcp`;
const graphqlRequests = [];
const delegationId = randomUUID();
let slowReceived, slowClosed;
const receivedSlow = new Promise(resolve => { slowReceived = resolve; });
const closedSlow = new Promise(resolve => { slowClosed = resolve; });
const graphql = createServer(async (request, response) => {
  assert.equal(request.headers.authorization, 'Bearer bsk_fixture_scoped');
  const parts = []; for await (const part of request) parts.push(part);
  const operation = JSON.parse(Buffer.concat(parts).toString());
  graphqlRequests.push(operation);
  if (operation.operationName === 'Slow') {
    response.on('close', slowClosed);
    response.writeHead(200, { 'Content-Length': '1000' }); response.write('{');
    slowReceived(); return;
  }
  if (operation.operationName === 'Denied') { response.writeHead(401).end('unauthorized'); return; }
  if (operation.operationName === 'Retry') { response.writeHead(503, { 'Retry-After': '0' }).end('unavailable'); return; }
  const delegationField = { KitDelegationSettings: 'settings', StartKitDelegation: 'start', GetKitDelegation: 'get',
    ListKitDelegations: 'tasks', WaitKitDelegation: 'wait', CancelKitDelegation: 'cancel', SubmitKitDelegation: 'submit' }[operation.operationName];
  if (delegationField) {
    assert.equal(operation.variables.sessionId, session);
    const result = { id: delegationId, parentSessionId: session, parentTurnId: delegationId, childSessionId: delegationId,
      profile: 'child-fixture', depth: 1, runtimeSeconds: 30, deadline: '2026-10-28T01:00:00Z',
      status: delegationField === 'wait' ? 'RUNNING' : 'COMPLETED', partial: delegationField === 'wait', failure: null,
      result: { summary: 'Native child result', artifacts: [{ metadataId: delegationId, label: 'Plan' }] } };
    const value = delegationField === 'settings' ? { profiles: ['child-fixture'], maxDepth: 1, maxActive: 1, maxChildren: 4, maxRuntimeSeconds: 60, totalRuntimeSeconds: 120 }
      : delegationField === 'tasks' ? [result] : result;
    response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ data: { ai: { delegations: { [delegationField]: value } } } }));
    return;
  }
  const collection = { id: '11111111-1111-4111-8111-111111111111', locked: false, slug: null, traitIds: [], name: 'Accessible topic', labels: [],
    created: '2026-10-27T00:00:00Z', modified: '2026-10-27T00:00:00Z', attributes: { type: 'Topic' }, systemAttributes: {}, ready: null,
    public: false, publicList: false, ordering: null, categories: [], collectionWorkflow: { state: 'pending', stateValid: null, pending: null }, supplementary: [] };
  if (operation.operationName === 'FindCollections') {
    assert.deepEqual(operation.variables.query, { attributes: [{ attributes: [{ key: 'type', value: 'Topic' }] }], offset: 40, limit: 5 });
  }
  const data = operation.operationName === 'FindCollections' ? { content: { findCollections: [collection] } }
    : operation.operationName === 'McpGraphQLSearch'
    ? { __schema: { types: [{ name: 'Draft', description: 'A document', fields: [{ name: 'name', description: 'Title' }] }] } }
    : operation.operationName === 'McpGraphQLType' ? { __type: { kind: 'OBJECT', name: operation.variables.name, fields: [] } }
      : operation.operationName === 'McpGraphQLRoots' ? { __schema: { queryType: { name: 'Query', fields: [] } } }
        : { saved: true, value: operation.variables?.value ?? null };
  response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ data }));
});
await new Promise(resolve => graphql.listen(0, '127.0.0.1', resolve));
const graphqlUrl = `http://127.0.0.1:${graphql.address().port}/graphql`;
function launch(args, environment = {}) {
  const child = spawn(binary, args, { env: { ...process.env, BOSCA_NO_UPDATE_CHECK: '1', ...environment }, stdio: ['pipe', 'pipe', 'inherit'] });
  processes.add(child);
  child.once('exit', () => processes.delete(child));
  return child;
}
async function stop(child) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  const stopped = new Promise(resolve => child.once('exit', resolve));
  child.kill('SIGTERM');
  await Promise.race([stopped, delay(10000, undefined, { ref: false }).then(() => { child.kill('SIGKILL'); throw new Error('CLI failed to stop'); })]);
}
async function start(generation) {
  const state = join(root, String(generation)); await mkdir(state);
  for (const reference of [descriptor.adapter, descriptor.harness]) {
    const separator = reference.lastIndexOf('@');
    const name = reference.slice(0, separator), version = reference.slice(separator + 1);
    const path = join(root, 'node_modules', name); await mkdir(path, { recursive: true });
    await writeFile(join(path, 'package.json'), JSON.stringify({ name, version }));
  }
  const portServer = createServer();
  await new Promise(resolve => portServer.listen(0, '127.0.0.1', resolve));
  const port = portServer.address().port; await new Promise(resolve => portServer.close(resolve));
  const child = launch(['harness', 'serve', '--host', '127.0.0.1', '--port', String(port), '--root', state], {
    BOSCA_CHAT_SESSION_ID: session, BOSCA_RUNTIME_GENERATION: String(generation), BOSCA_RUNTIME_TOKEN: token,
    BOSCA_RUNTIME_EXPIRES: String(Math.floor(Date.now() / 1000) + 300), BOSCA_TOKEN: 'bsk_fixture_scoped', BOSCA_ENDPOINT: graphqlUrl,
    BOSCA_ACP_COMMAND: JSON.stringify([process.execPath, fileURLToPath(new URL('./fake_adapter.mjs', import.meta.url))]),
    BOSCA_RUNTIME_HOME: root,
    TEST_HARNESS_HOME: join(state, 'harness'), TEST_HARNESS_STATE_PATH: family === 'claude' ? 'projects/fixture/test.jsonl' : 'sessions/test.json',
    DOCS_ACCESS: secret, BOSCA_MCP_SECRET_NAMES: '["DOCS_ACCESS"]'
  });
  child.stdout.resume();
  const base = `http://127.0.0.1:${port}`;
  for (let i = 0; i < 200; i++) {
    assert.equal(child.exitCode, null, 'runtime exited before ready');
    try { if ((await fetch(base + '/healthz')).ok) break; } catch {}
    await delay(50);
  }
  let instance;
  async function request(path, { method = 'POST', body = '', headers = {} } = {}) {
    const response = await fetch(base + path, { method, body: method === 'GET' ? undefined : body,
      headers: { Authorization: 'Bearer ' + token, 'X-Bosca-Generation': String(generation), 'X-Bosca-Epoch': '1', ...(instance ? { 'X-Bosca-Instance': instance } : {}), ...headers } });
    assert.equal(response.status, 200, path + ': ' + response.status + (response.ok ? '' : ' ' + await response.text()));
    return response;
  }
  const identity = await (await request('/v1/claim')).json();
  instance = identity.instanceId;
  assert.equal(typeof instance, 'string');
  assert(instance.length > 0);
  assert.equal(identity.versions.adapter, descriptor.adapter.slice(descriptor.adapter.lastIndexOf('@') + 1));
  assert.equal(identity.versions.harness, descriptor.harness.slice(descriptor.harness.lastIndexOf('@') + 1));
  assert.equal(identity.versions.statePolicy, descriptor.statePolicy);
  assert(identity.versions.cli.length > 0);
  const capabilities = await (await request('/v1/capabilities', { method: 'GET' })).json();
  assert.equal(capabilities.adapter, identity.versions.adapter);
  assert.equal(capabilities.harness, identity.versions.harness);
  assert.equal(capabilities.statePolicy, descriptor.statePolicy);
  assert.equal(capabilities.transportVersion, descriptor.transportVersion);
  assert.equal(capabilities.acpVersion, descriptor.acpVersion);
  assert.equal(capabilities.archiveFormatVersion, descriptor.archiveFormatVersion);
  return { state, child, request };
}
async function checkMcp(control, key, expected) {
  const child = launch(['harness', 'mcp', '--control', control, key]);
  const lines = createInterface({ input: child.stdout })[Symbol.asyncIterator]();
  async function rpc(id, method, params) {
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    while (true) {
      const line = await Promise.race([lines.next(), delay(10000, undefined, { ref: false }).then(() => { throw new Error('MCP response timed out'); })]);
      assert.equal(line.done, false, 'MCP exited before responding');
      const message = JSON.parse(line.value);
      assert.notEqual(message.id, 90, 'Cancelled GraphQL request emitted a response');
      if (message.id === id) { assert.equal(message.error, undefined); return message.result; }
    }
  }
  try {
    await rpc(1, 'initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'fixture', version: '1' } });
    child.stdin.write('{"jsonrpc":"2.0","method":"notifications/initialized"}\n');
    const result = await rpc(2, 'tools/list', {});
    assert(result.tools.some(tool => tool.name === expected));
    if (key === 'bosca') {
      assert(result.tools.some(tool => tool.name === 'bosca_graphql'));
      async function call(id, args) {
        return rpc(id, 'tools/call', { name: 'bosca_graphql', arguments: args });
      }
      assert.notEqual((await call(3, { action: 'roots' })).isError, true);
      const type = await call(4, { action: 'type', typeName: 'Draft' });
      assert.equal(type.structuredContent.data.__type.name, 'Draft');
      const search = await call(5, { action: 'search', query: 'document' });
      assert.equal(search.structuredContent.data.results[0].type, 'Draft');
      const found = await rpc(8, 'tools/call', { name: 'content_collection', arguments: {
        action: 'find', filters: [{ key: 'type', value: 'Topic' }], offset: 40, limit: 5
      } });
      assert.notEqual(found.isError, true);
      assert.deepEqual(JSON.parse(found.content[0].text), [{ id: '11111111-1111-4111-8111-111111111111', name: 'Accessible topic', public: false }]);
      assert.equal(graphqlRequests.filter(request => request.operationName === 'FindCollections').length, 1);
      const saved = await call(6, { action: 'execute', operationName: 'Save', query: 'mutation Save($value: String) { save(value: $value) }', variables: { value: 'native draft' } });
      assert.equal(saved.structuredContent.data.value, 'native draft');
      assert(saved.content.some(content => content.type === 'resource' && content.resource.mimeType === 'application/json'));
      assert.equal((await call(7, { action: 'execute', operationName: 'Denied', query: 'query Denied { secret }' })).isError, true);
      assert.equal((await call(9, { action: 'execute', operationName: 'Retry', query: 'mutation Retry { start }' })).isError, true);
      assert.equal(graphqlRequests.filter(request => request.operationName === 'Retry').length, 1);
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 90, method: 'tools/call', params: {
        name: 'bosca_graphql', arguments: { action: 'execute', operationName: 'Slow', query: 'query Slow { slow }' }
      } }) + '\n');
      await Promise.race([receivedSlow, delay(10000, undefined, { ref: false }).then(() => { throw new Error('Slow GraphQL request never arrived'); })]);
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/cancelled', params: { requestId: 90 } }) + '\n');
      const recovered = await call(91, { action: 'execute', query: '{ recovered }' });
      assert.notEqual(recovered.isError, true);
      await Promise.race([closedSlow, delay(10000, undefined, { ref: false }).then(() => { throw new Error('GraphQL cancellation did not close the stalled response'); })]);
      assert.equal(graphqlRequests.filter(request => request.operationName === 'Save').length, 1);
      assert.equal(graphqlRequests.filter(request => request.operationName === 'Denied').length, 1);
      assert.equal(graphqlRequests.filter(request => request.operationName === 'Slow').length, 1);
      assert(result.tools.some(tool => tool.name === 'bosca_delegate'));
      const input = { requestId: delegationId, brief: 'Inspect plan', expectedResult: 'Summary', profile: 'child-fixture', runtimeSeconds: 30,
        scopes: ['content:view'], deadline: '2026-10-28T00:59:00Z', artifacts: [delegationId],
        context: [{ kind: 'GIT_FILE', id: delegationId, ref: 'main', path: 'docs/plan.md' }] };
      let rpcId = 200;
      for (const action of ['settings', 'start', 'start', 'list', 'get', 'wait', 'submit', 'cancel']) {
        const response = await rpc(++rpcId, 'tools/call', { name: 'bosca_delegate', arguments: { action, id: delegationId, input,
          output: { summary: 'Structured output', artifacts: [{ metadataId: delegationId, label: 'Plan' }] } } });
        assert.notEqual(response.isError, true, JSON.stringify(response));
        if (action !== 'settings') assert(JSON.stringify(response.content).includes('Native child result'));
      }
      const starts = graphqlRequests.filter(request => request.operationName === 'StartKitDelegation');
      assert.equal(starts.length, 2); assert.deepEqual(starts[0].variables, starts[1].variables);
      assert.equal(graphqlRequests.filter(request => request.operationName === 'WaitKitDelegation').length, 1);
    }
  } finally { await stop(child); }
}
try {
  const first = await start(1);
  await first.request('/v1/start');
  const connections = [
    { key: 'local', name: 'Local', required: true, transport: 'STDIO', command: [process.execPath, fileURLToPath(new URL('./fake_mcp.mjs', import.meta.url))], environment: { REQUIRED_SECRET: { environment: 'DOCS_ACCESS' } } },
    { key: 'http', name: 'HTTP', required: true, transport: 'STREAMABLE_HTTP', url: mcpUrl, headers: { Authorization: { environment: 'DOCS_ACCESS', prefix: 'Bearer ' } } }
  ];
  const readiness = await (await first.request('/v1/mcp', { body: JSON.stringify(connections) })).json();
  assert(readiness.every(status => status.ready), JSON.stringify(readiness));
  assert(methods.includes('initialize') && methods.includes('tools/list'));
  await checkMcp(join(first.state, 'control'), 'local', 'echo');
  await checkMcp(join(first.state, 'control'), 'bosca', 'content_metadata');
  await writeFile(join(first.state, 'workspace', 'artifact.txt'), 'persistence-test');
  const receipt = await (await first.request('/v1/archive')).json();
  const archive = Buffer.from(await (await first.request('/v1/archive', { method: 'GET' })).arrayBuffer());
  await stop(first.child);
  const second = await start(2);
  await second.request('/v1/restore', { body: archive, headers: { 'X-Archive-Sha256': receipt.sha256 } });
  assert.equal(await readFile(join(second.state, 'workspace', 'artifact.txt'), 'utf8'), 'persistence-test');
  await second.request('/v1/start');
  console.log('CLI runtime, scoped GraphQL and delegation MCP, cancellation, native MCP probes/launchers, and archive/restore passed');
} finally {
  await Promise.all([...processes].map(stop));
  await new Promise(resolve => mcp.close(resolve));
  await new Promise(resolve => graphql.close(resolve));
  await rm(root, { recursive: true, force: true });
}
