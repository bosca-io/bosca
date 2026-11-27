import assert from 'node:assert/strict';
import test from 'node:test';
import { EventEmitter } from 'node:events';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../bml-runtime/package.json', import.meta.url));
const { build } = require('esbuild');
const bundle = await build({ stdin: { contents: 'export {waitForDocumentSync} from "./src/main/client/helpers/document-sync.ts";', resolveDir: process.cwd() }, bundle: true, write: false, format: 'esm', platform: 'browser' });
const { waitForDocumentSync } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const provider = pending => Object.assign(new EventEmitter(), { hasUnsyncedChanges: pending });
const clean = source => assert.equal(source.eventNames().length, 0, 'all wait listeners are released');
test('already persisted updates do not defer submission', async () => {
  const source = provider(false); await waitForDocumentSync(source, new AbortController().signal); clean(source);
});
test('submission waits for all pending updates including updates added while waiting', async () => {
  const source = provider(true); let finished = false;
  const waiting = waitForDocumentSync(source, new AbortController().signal).then(() => { finished = true; });
  source.emit('unsyncedChanges', { number: 2 }); await Promise.resolve(); assert.equal(finished, false);
  source.emit('unsyncedChanges', { number: 1 }); await Promise.resolve(); assert.equal(finished, false);
  source.hasUnsyncedChanges = false; source.emit('unsyncedChanges', { number: 0 }); await waiting;
  assert.equal(finished, true); clean(source);
});
test('a failed sync has explicit retry feedback and a later retry can succeed', async () => {
  const source = provider(true);
  await assert.rejects(waitForDocumentSync(source, new AbortController().signal, 5), /draft is retained.*save again/); clean(source);
  const waiting = waitForDocumentSync(source, new AbortController().signal);
  source.hasUnsyncedChanges = false; source.emit('unsyncedChanges', { number: 0 }); await waiting; clean(source);
});
test('access loss fails the waiting submission without claiming it was saved', async () => {
  const source = provider(true), waiting = waitForDocumentSync(source, new AbortController().signal);
  source.emit('authenticationFailed'); await assert.rejects(waiting, /access was lost/); clean(source);
});
test('unmount and destruction cancel waits and release timers and listeners', async () => {
  for (const destroy of [false, true]) {
    const source = provider(true), lifetime = new AbortController(), waiting = waitForDocumentSync(source, lifetime.signal);
    if (destroy) source.emit('destroy'); else lifetime.abort();
    await assert.rejects(waiting, { name: 'AbortError' }); clean(source);
  }
  const lifetime = new AbortController(); lifetime.abort();
  await assert.rejects(waitForDocumentSync(provider(false), lifetime.signal), { name: 'AbortError' });
});
