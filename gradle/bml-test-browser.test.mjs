import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createServer } from 'node:http';
import { launchTestBrowser, closeTestBrowser, waitForTestCondition } from './bml-test-browser.mjs';

test('history readiness accepts restored documents without waiting for a fresh load event', { timeout: 15000 }, async () => {
  const http = createServer((request, response) => {
    response.setHeader('Content-Type', 'text/html');
    response.end(`<main>${request.url}</main>`);
  });
  await new Promise(resolve => http.listen(0, '127.0.0.1', resolve));
  let owned;
  try {
    owned = await launchTestBrowser(0);
    const page = owned.connection.contexts()[0].pages()[0];
    const base = `http://127.0.0.1:${http.address().port}`;
    await page.goto(base + '/first');
    await page.goto(base + '/second');
    await page.goBack({ waitUntil: 'commit', timeout: 5000 });
    assert.equal(await waitForTestCondition(page, "document.readyState==='complete'&&document.querySelector('main')?.textContent==='/first'"), true);
  } finally {
    try { await owned?.close(); }
    finally { await new Promise(resolve => http.close(resolve)); }
  }
});

test('readiness follows a navigation that destroys the original document', { timeout: 15000 }, async () => {
  const owned = await launchTestBrowser(0);
  try {
    const page = owned.connection.contexts()[0].pages()[0];
    const ready = waitForTestCondition(page, "window.conditionStarted=true; document.querySelector('main')?.textContent==='ready'");
    await waitForTestCondition(page, 'window.conditionStarted===true');
    await page.goto('data:text/html,<main>ready</main>');
    assert.equal(await ready, true);
    assert.deepEqual(await waitForTestCondition(page, "['ready',document.querySelector('main').textContent]"), ['ready', 'ready']);
    await assert.rejects(waitForTestCondition(page, "(()=>{throw new Error('invalid readiness predicate');})()"), /invalid readiness predicate/);
  } finally {
    await owned.close();
  }
});

test('readiness awaits asynchronous false results before accepting a fulfilled condition', { timeout: 15000 }, async () => {
  const owned = await launchTestBrowser(0);
  try {
    const page = owned.connection.contexts()[0].pages()[0];
    await page.evaluate('window.asyncConditionPasses=0');
    const value = await waitForTestCondition(page, `(async()=>{
      await new Promise(resolve=>requestAnimationFrame(resolve));
      const passes=++window.asyncConditionPasses;
      return passes>=3 ? {ready:true,passes} : false;
    })()`);
    assert.deepEqual(value, { ready: true, passes: 3 });
    await assert.rejects(waitForTestCondition(page, '(async()=>{throw new Error("invalid async predicate");})()'), /invalid async predicate/);
  } finally {
    await owned.close();
  }
});

test('readiness observes changes with page scripts disabled', { timeout: 15000 }, async () => {
  const owned = await launchTestBrowser(0);
  try {
    const page = owned.connection.contexts()[0].pages()[0];
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Emulation.setScriptExecutionDisabled', { value: true });
    await page.evaluate('window.conditionPasses=0;window.conditionReady=false');
    const ready = waitForTestCondition(page, '(++window.conditionPasses,window.conditionReady)', { scriptsDisabled: true });
    await waitForTestCondition(page, 'window.conditionPasses>=2', { scriptsDisabled: true });
    await page.evaluate("window.conditionReady='ready'");
    assert.equal(await ready, 'ready');
    const navigation = waitForTestCondition(page, `(async()=>{
      if(document.querySelector('main'))return document.querySelector('main').textContent==='native ready';
      window.nativeWaitStarted=true;await new Promise(()=>{});return false;
    })()`, { scriptsDisabled: true });
    await waitForTestCondition(page, 'window.nativeWaitStarted===true', { scriptsDisabled: true });
    await page.goto('data:text/html,<main>native ready</main>');
    assert.equal(await navigation, true);
    await assert.rejects(waitForTestCondition(page, "(()=>{throw new Error('invalid native predicate');})()", { scriptsDisabled: true }), /invalid native predicate/);
  } finally {
    await owned.close();
  }
});

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
