// Native subprocess fixture for runtime lifecycle tests; no provider calls.
import { createInterface } from 'node:readline';
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync, existsSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
const home = process.env.TEST_HARNESS_HOME;
const nativeState = process.env.TEST_HARNESS_STATE_PATH ?? 'sessions/test.json';
writeFileSync(join(dirname(home), 'native-started'), 'started');
writeFileSync(join(home, 'process-pid'), String(process.pid));
let initialized = false, active = null, model = 'small', reasoning = 'medium';
const sessions = new Set(), callbacks = new Map();
const emit = message => process.stdout.write(JSON.stringify(message) + '\n');
const save = (file, value) => writeFileSync(join(home, file), value);
function options() {
  const option = (id, category, currentValue, values) => ({ id, name: id, category, type: 'select', currentValue, options: values.map(value => ({ value, name: value })) });
  return [option('model', 'model', model, ['small', 'large']), option('reasoning_effort', 'thought_level', reasoning,
    model === 'small' ? ['low', 'medium'] : existsSync(join(dirname(home), 'disable-high')) ? ['medium'] : ['medium', 'high'])];
}
async function prompt(request, current) {
  const text = current.text, signal = current.abort.signal;
  const count = join(home, 'prompt-count');
  writeFileSync(count, String((existsSync(count) ? Number(readFileSync(count, 'utf8')) : 0) + 1));
  save('last-model.json', JSON.stringify({ model, reasoning }));
  save('last-prompt.json', JSON.stringify(request.params.prompt));
  try {
    if (text === 'delegate-wait' || text.includes('fixture-child-wait')) {
      save('waiting-pid', String(process.pid));
      while (!existsSync(join(dirname(home), 'release-delegation'))) await delay(10, null, { signal });
    }
    if (text === 'spawn-writer') {
      const file = join(process.cwd(), 'writer.log');
      const child = spawn(process.execPath, ['-e', 'setInterval(() => require("node:fs").appendFileSync(process.argv[1], "writer\\n"), 10)', file], {
        detached: true, stdio: 'ignore'
      });
      child.unref();
      save('writer-pid', String(child.pid));
      while (!existsSync(file)) await delay(10, null, { signal });
    }
    if (text === 'journal-flood' || text === 'journal-flood-recovery' || text === 'oversized-line') {
      save('journal-writer-pid', String(process.pid));
      if (text === 'journal-flood-recovery') while (!existsSync(join(dirname(home), 'release-journal-flood'))) await delay(10, null, { signal });
      if (text === 'oversized-line') process.stdout.write('x'.repeat(2048));
      else for (let i = 0; i < 3; i++) emit({ jsonrpc: '2.0', method: 'session/update', params: { sessionId: 'native-test', update: {
        sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'x'.repeat(600) } } } });
      await delay(60000, null, { signal });
    }
    if (text === 'permission') {
      const response = new Promise(resolve => callbacks.set('approval-1', resolve));
      emit({ jsonrpc: '2.0', id: 'approval-1', method: 'session/request_permission', params: { sessionId: 'native-test',
        toolCall: { toolCallId: 'tool-1', title: 'Write fixture' }, options: [
          { optionId: 'allow', name: 'Allow once', kind: 'allow_once' }, { optionId: 'deny', name: 'Deny', kind: 'reject_once' }] } });
      const result = await response;
      if (result.outcome === 'cancelled') throw Object.assign(new Error('cancelled'), { name: 'AbortError' });
      if (result.optionId === 'deny') { emit({ jsonrpc: '2.0', id: request.id, result: { stopReason: 'end_turn' } }); return; }
    }
    if (text === 'wait' || text === 'ignore-cancel') {
      save('waiting-pid', String(process.pid));
      await delay(60000, null, { signal });
    }
    if (text === 'slow') await delay(250, null, { signal });
    if (text === 'slow-turn') await delay(1500, null, { signal });
    if (text === 'rich-results' || text === 'rich-results-recovery') {
      const payload = JSON.stringify({ records: [{ day: 'Monday', sessions: 12 }], returnedRecords: 1, truncated: true, cached: false });
      const update = { sessionUpdate: 'tool_call_update', toolCallId: 'mcp-result', status: 'completed',
        rawInput: { server: 'bosca', tool: 'analytics_query', arguments: { private: 'private-input' } },
        rawOutput: { result: { content: [{ type: 'text', text: payload }, { type: 'resource', resource: {
          uri: 'bosca://analytics/queries/by-key/traffic/results', mimeType: 'application/json', text: payload } },
          { type: 'image', data: 'aGVsbG8=', mimeType: 'image/png' }, { type: 'resource_link', name: 'Report',
            uri: 'bosca://content/metadata/11111111-1111-1111-1111-111111111111' }] }, error: null } };
      emit({ jsonrpc: '2.0', method: 'session/update', params: { sessionId: 'native-test', update: {
        sessionUpdate: 'tool_call', toolCallId: 'mcp-result', title: 'mcp.bosca.analytics_query' } } });
      for (let i = 0; i < 2; i++) emit({ jsonrpc: '2.0', method: 'session/update', params: { sessionId: 'native-test', update } });
      save('rich-results-emitted', text);
      if (text === 'rich-results-recovery') while (!existsSync(join(dirname(home), 'release-rich-results'))) await delay(10, null, { signal });
    }
    mkdirSync(dirname(join(home, nativeState)), { recursive: true }); save(nativeState, text);
    emit({ jsonrpc: '2.0', method: 'session/update', params: { sessionId: 'native-test', update: {
      sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: text === 'leak' ? process.env.TEST_SECRET_TOKEN : 'saved:' + text } } } });
    emit({ jsonrpc: '2.0', id: request.id, result: { stopReason: 'end_turn' } });
  } catch (failure) {
    if (failure.name !== 'AbortError') throw failure;
    emit({ jsonrpc: '2.0', id: request.id, result: { stopReason: 'cancelled' } });
  } finally { if (active === current) active = null; }
}
for await (const line of createInterface({ input: process.stdin })) {
  const request = JSON.parse(line), method = request.method;
  let result = {};
  if (!method) { callbacks.get(request.id)?.(request.result.outcome); callbacks.delete(request.id); continue; }
  if (method === '$/cancelRequest') {
    save('rpc-cancel-observed', 'cancelled');
    if (active?.id === request.params.requestId) active.abort.abort();
    continue;
  }
  if (method === 'session/cancel') { save('cancel-observed', 'cancelled'); if (active?.text !== 'ignore-cancel') active?.abort.abort(); continue; }
  if (method === 'initialize') {
    if (existsSync(join(dirname(home), 'hold-initialize'))) {
      process.stdin.pause();
      save('initialization-started', '');
      while (existsSync(join(dirname(home), 'hold-initialize'))) await delay(10);
      process.stdin.resume();
    }
    if (initialized) throw new Error('Already initialized');
    initialized = true; result = { protocolVersion: 1, agentCapabilities: { loadSession: true } };
    save('initialization-completed', '');
  } else if (method === 'session/new' || method === 'session/load') {
    save('last-setup.json', JSON.stringify(request.params));
    if (method === 'session/load' && !sessions.has('native-test') && !existsSync(join(home, nativeState))) throw new Error('Missing native state');
    sessions.add('native-test'); result = { sessionId: 'native-test', configOptions: options() };
  } else if (method === 'session/set_config_option') {
    const option = options().find(option => option.id === request.params.configId);
    if (!option?.options.some(value => value.value === request.params.value)) {
      emit({ jsonrpc: '2.0', id: request.id, error: { code: -32602, message: 'Invalid option' } }); continue;
    }
    if (request.params.configId === 'model') { model = request.params.value; reasoning = 'medium'; } else reasoning = request.params.value;
    result = { configOptions: options() };
  } else if (method === 'session/prompt') {
    active = { id: request.id, text: request.params.prompt.at(-1).text, abort: new AbortController() };
    prompt(request, active).catch(error => { console.error(error); process.exit(1); }); continue;
  }
  if ('id' in request) emit({ jsonrpc: '2.0', id: request.id, result });
}
