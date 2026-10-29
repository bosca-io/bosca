import assert from 'node:assert/strict';
import { test } from 'node:test';
import { launchTestBrowser, closeTestBrowser } from './bml-test-browser.mjs';

test('separate checks own isolated browser cookies and reap their processes', { timeout: 15000 }, async () => {
  const first = await launchTestBrowser(0);
  let second;
  try {
    await first.connection.contexts()[0].addCookies([{ name: 'check', value: 'first', url: 'http://localhost/' }]);
    second = await launchTestBrowser(0);
    assert.deepEqual(await second.connection.contexts()[0].cookies(), []);
    assert.notEqual(first.process.pid, second.process.pid);
  } finally {
    await first.close();
    await second?.close();
  }
  assert.ok(first.process.exitCode !== null || first.process.signalCode !== null);
  assert.ok(second.process.exitCode !== null || second.process.signalCode !== null);
});

test('cleanup reaps a browser whose renderer is executing an endless loop', { timeout: 15000 }, async () => {
  const owned = await launchTestBrowser(0);
  try {
    const page = owned.connection.contexts()[0].pages()[0];
    const started = page.waitForEvent('console');
    const interrupted = page.evaluate(() => { console.log('busy'); while (true) {} }).then(() => false, () => true);
    await started;
    await owned.close();
    assert.equal(await interrupted, true);
    assert.ok(owned.process.exitCode !== null || owned.process.signalCode !== null);
  } finally {
    await owned.close();
  }
});

test('cleanup force kills and reaps an unresponsive browser process', {
  skip: process.platform === 'win32', timeout: 15000,
}, async () => {
  const owned = await launchTestBrowser(0);
  try {
    process.kill(owned.process.pid, 'SIGSTOP');
    await closeTestBrowser(owned.server, 50);
    assert.equal(owned.process.signalCode, 'SIGKILL');
  } finally {
    await owned.close();
  }
});
