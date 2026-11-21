import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../../../bml/bml-runtime/package.json', import.meta.url));
const { build } = require('esbuild');
const bundle = await build({ entryPoints: ['src/main/client/helpers/release-live-state.ts'], bundle: true, write: false, format: 'esm' });
const { WorkOpsLiveSessions, workOpsLiveTtl } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const source = (appId = 'mobile', appVersion = '1.0.0', projectId = 'project') => ({ appId, appVersion, projectId, projectLabel: projectId });
const message = (overrides = {}) => ({ sessionId: 'visitor', lat: 40, lon: -74, appId: 'mobile', appVersion: '1.0.0', ...overrides });

test('heartbeats update one application/session identity and its arrival clock', () => {
  const selected = source(), state = new WorkOpsLiveSessions([selected]);
  assert.equal(state.accept(selected, message(), 100), true);
  assert.equal(state.accept(selected, message({ lat: 41 }), 200), true);
  assert.equal(state.size, 1); assert.equal(state.rows[0].lat, 41); assert.equal(state.rows[0].lastSeen, 200);
});
test('application and session pairs remain distinct including delimiter-bearing identities', () => {
  const first = source('a:b'), second = source('a'), state = new WorkOpsLiveSessions([first, second]);
  state.accept(first, message({ appId: 'a:b', sessionId: 'c' }), 0);
  state.accept(second, message({ appId: 'a', sessionId: 'b:c' }), 0);
  assert.equal(state.size, 2);
});
test('release scope rejects other or unknown versions while explicit all-versions scope accepts them', () => {
  const selected = source(), state = new WorkOpsLiveSessions([selected]);
  for (const appVersion of ['2.0.0', null, undefined]) assert.equal(state.accept(selected, message({ appVersion }), 0), false);
  const all = source('mobile', null), expanded = new WorkOpsLiveSessions([all]);
  assert.equal(expanded.accept(all, message({ appVersion: null }), 0), true); assert.equal(expanded.rows[0].appVersion, null);
  assert.equal(expanded.accept(all, message({ appId: 'foreign' }), 0), false);
  assert.equal(expanded.accept(selected, message(), 0), false);
});
test('shared applications keep every linked project without duplicating the session count', () => {
  const first = source(), second = source('mobile', '1.0.0', 'other'), state = new WorkOpsLiveSessions([first, second]);
  state.accept(first, message(), 0); state.accept(second, message(), 1);
  assert.equal(state.size, 1); assert.deepEqual(state.rows[0].projectIds, ['project', 'other']);
  assert.deepEqual([state.rows[0].lon, state.rows[0].lat], [-74, 40]);
});
test('malformed locations and empty session keys cannot become plotted points', () => {
  const selected = source(), state = new WorkOpsLiveSessions([selected]);
  for (const value of [null, [], {}, message({ sessionId: ' ' }), message({ lat: NaN }), message({ lon: Infinity }), message({ lat: 91 }), message({ lon: -181 }), message({ lat: '40' })]) assert.equal(state.accept(selected, value, 0), false);
  assert.equal(state.size, 0); assert.equal(state.accept(selected, message({ appId: null }), 0), true);
});
test('expiry uses the latest arrival and never expires a refreshed point with an older peer', () => {
  const selected = source(), state = new WorkOpsLiveSessions([selected]);
  state.accept(selected, message(), 0); state.accept(selected, message({ sessionId: 'other' }), 0);
  assert.equal(state.expire(workOpsLiveTtl), false);
  state.accept(selected, message(), workOpsLiveTtl - 1);
  assert.equal(state.expire(workOpsLiveTtl + 1), true); assert.equal(state.size, 1); assert.equal(state.rows[0].sessionId, 'visitor');
  assert.equal(state.expire(2 * workOpsLiveTtl), true); assert.equal(state.size, 0);
});
test('source replacement and disposal begin with an empty set and reject removed authority', () => {
  const selected = source(), state = new WorkOpsLiveSessions([selected]); state.accept(selected, message(), 0); state.clear(); assert.equal(state.size, 0);
  const replacement = new WorkOpsLiveSessions([source('replacement')]); assert.equal(replacement.accept(selected, message(), 0), false); assert.equal(replacement.size, 0);
});
