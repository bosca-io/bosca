import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';
import { until } from './release-ci-fixture.mjs';
import { releaseStoreFixture } from './release-store-fixture.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, vendor = process.env.ATLAS_WORKOPS_TEST_STORE_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && vendor && [base, vendor].every(url => ['localhost', '127.0.0.1'].includes(new URL(url).hostname)) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/release-store-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), admin = await context.newPage(), readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(), errors = [];
let expectedRejection = false, fixture, deliverDeferred;
for (const tab of [admin, reader]) {
  tab.on('pageerror', error => errors.push(error.message));
  tab.on('console', message => { if (message.type() === 'error' && !(expectedRejection && /500|bml:|deferred/i.test(message.text()))) errors.push(message.text()); });
}
const gql = async (query, variables = {}) => {
  const result = await admin.evaluate(async ({ query, variables }) => (await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) })).json(), { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const login = async (tab, isReader = false) => {
  await tab.goto(base + '/workops/releases'); await tab.locator('input[name=identifier]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER : 'admin');
  await tab.locator('input[name=password]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD : process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await tab.locator('button[type=submit]').click(); await tab.waitForURL(url => url.pathname === '/workops/releases');
};
const mode = async changes => { const response = await fetch(vendor + '/fixture/mode', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(changes) }); assert.equal(response.status, 200, await response.clone().text()); };
const state = async () => { const response = await fetch(vendor + '/fixture/state'); assert.equal(response.status, 200); return response.json(); };
const health = () => reader.locator('[data-workops-release-store-health]');
const target = name => health().locator('[data-workops-store-target]').filter({ has: reader.getByText(name, { exact: true }) });
const fact = (scope, label) => scope.locator('dl > div').filter({ has: reader.getByText(label, { exact: true }) }).locator('dd');
const refresh = async () => { await health().getByRole('button', { name: 'Refresh store health', exact: true }).click(); await health().locator('[data-bml-action-pending]').waitFor({ state: 'detached' }); };
const overviewRefresh = async () => { await reader.getByRole('button', { name: 'Refresh release overview', exact: true }).evaluate(node => node.click()); await reader.waitForFunction(() => !document.querySelector('[data-workops-release-overview] [data-bml-action-pending]')); };
const deferredPattern = '**/_bml/deferred/*workops-release-store-health*';
try {
  await login(admin); fixture = await releaseStoreFixture(gql, fixtureProject); await fixture.permission(true); await login(reader, true);
  const path = `/workops/releases/${fixture.release}`;
  let heldResponse; const delivery = new Promise(resolve => { deliverDeferred = resolve; });
  await reader.route(deferredPattern, async route => { heldResponse = await route.fetch(); await delivery; await route.fulfill({ response: heldResponse }); });
  await reader.goto(base + path); await until('Store deferred read reached the server', () => Boolean(heldResponse));
  await reader.locator('[data-workops-release-store-health-loading]').waitFor();
  await reader.getByText(/No visible analytics applications or services are linked/).waitFor(); await reader.getByRole('button', { name: 'Refresh release overview', exact: true }).waitFor();
  deliverDeferred(); await health().waitFor(); await reader.unroute(deferredPattern);
  await until('Two store target cards are rendered', () => health().locator('[data-workops-store-target]').count(), count => count === 2);
  assert.equal((await fact(target('Google Play'), 'Rollout').innerText()).trim(), '25.00%');
  assert.equal((await fact(target('Google Play'), 'Release state').innerText()).trim(), 'inProgress');
  assert.equal((await fact(target('Google Play'), 'Build').innerText()).trim(), '42');
  assert.equal((await fact(target('Google Play'), 'Crash rate').innerText()).trim(), '0.250%');
  assert.equal((await fact(target('Google Play'), 'ANR rate').innerText()).trim(), '0.100%');
  assert.equal((await fact(target('Google Play'), 'Review rating').innerText()).trim(), '4.50');
  for (const [label, value] of [['Build', '41'], ['Review state', 'WAITING_FOR_REVIEW'], ['Beta review', 'APPROVED'], ['Build processing', 'VALID'], ['Phased release', 'ACTIVE'], ['Crash rate', '0.000%'], ['ANR rate', 'Unavailable'], ['Review rating', '3.00']]) assert.equal((await fact(target('App Store'), label).innerText()).trim(), value);
  await target('App Store').getByText('TestFlight groups: Internal, External', { exact: true }).waitFor();
  await target('App Store').getByText('<img src=x onerror=alert(1)>', { exact: true }).waitFor(); assert.equal(await target('App Store').locator('img').count(), 0);
  await target('App Store').getByText('Showing 3 of 4 crash reports returned by the store.', { exact: true }).waitFor();
  assert.equal(await health().getByText('Wrong version review', { exact: true }).count(), 0);
  const initial = await state();
  for (const scope of [target('Google Play'), target('App Store')]) {
    const chart = scope.getByRole('group', { name: 'Store crash-rate history', exact: true }); await chart.locator('[role=img]').waitFor(); await chart.locator('summary').click();
    assert.equal(await chart.locator('tbody tr').count(), 2);
    const times = await chart.locator('tbody tr td:first-of-type').allTextContents(); assert.deepEqual(times.map(Date.parse), [initial.previous, initial.current].map(Date.parse)); await chart.locator('summary').click();
  }
  assert.ok(initial.calls.some(call => call.path.includes('/androidpublisher/') && call.method === 'POST'));
  assert.ok(initial.calls.some(call => call.path.includes('/androidpublisher/') && call.method === 'DELETE'));
  assert.ok(initial.calls.filter(call => call.path.startsWith('/v1/')).every(call => call.authenticated));
  assert.deepEqual([...new Set(initial.calls.filter(call => call.path === '/fixture/observations').map(call => call.query.version))], ['1.0.0']);

  await mode({ revision: 1, historyFailure: true }); await refresh();
  await health().getByText('Stale', { exact: true }).waitFor(); assert.equal((await fact(target('Google Play'), 'Release state').innerText()).trim(), 'inProgress');
  await mode({ historyFailure: false }); await refresh(); assert.equal((await fact(target('Google Play'), 'Release state').innerText()).trim(), 'completed');
  assert.equal((await fact(target('Google Play'), 'Rollout').innerText()).trim(), '100.00%'); assert.equal((await fact(target('App Store'), 'Review state').innerText()).trim(), 'READY_FOR_SALE');
  await mode({ vendorFailure: true }); await refresh(); await health().getByText('Stale', { exact: true }).waitFor(); await health().getByText(/Store telemetry unavailable/).waitFor();
  assert.equal(await health().getByText(/No store targets were reported/).count(), 0); await mode({ vendorFailure: false }); await refresh();
  await fixture.permission(false); const beforeDenied = (await state()).calls.length; expectedRejection = true; await refresh(); expectedRejection = false;
  await health().getByText('Stale', { exact: true }).waitFor(); assert.equal((await state()).calls.length, beforeDenied);
  await fixture.permission(true); await refresh(); assert.equal(await health().getByText('Stale', { exact: true }).count(), 0);
  await mode({ emptyHistory: true }); await refresh();
  assert.equal((await fact(target('Google Play'), 'Crash rate').innerText()).trim(), 'Unavailable'); await target('Google Play').getByText('No numeric crash-rate observations were reported.', { exact: true }).waitFor();
  await mode({ emptyHistory: false }); await refresh();
  await fixture.bundle(fixture.versions[1]); await overviewRefresh();
  await until('Current bundled marketing version reaches both store targets', () => health().locator('[data-workops-store-target]').allTextContents(), texts => texts.length === 2 && texts.every(text => text.includes('2.0.0')));
  assert.equal((await fact(target('Google Play'), 'Crash rate').innerText()).trim(), '0.750%'); assert.equal((await fact(target('Google Play'), 'Review rating').innerText()).trim(), '2.00');
  assert.equal((await fact(target('App Store'), 'Build').innerText()).trim(), '84');
  await fixture.bundle(null); await overviewRefresh(); await health().getByText('No store targets were reported for the bundled versions.', { exact: true }).waitFor(); assert.equal(await health().locator('[data-workops-store-target]').count(), 0);
  await fixture.bundle(fixture.versions[1]); await overviewRefresh(); await target('Google Play').waitFor();

  await reader.route(deferredPattern, route => route.fulfill({ status: 500, body: 'Isolated store transport failure' }));
  expectedRejection = true; await reader.goto(base + path); await reader.getByText('Store health could not be loaded. Retry to check again.', { exact: true }).waitFor(); await reader.unroute(deferredPattern);
  await reader.getByRole('button', { name: 'Retry store health', exact: true }).click(); await target('Google Play').waitFor(); expectedRejection = false;
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await readerContext.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await reader.setViewportSize({ width, height: 1000 }); await reader.goto(base + path); await target('Google Play').waitFor();
    await until('All store charts are initialized', () => health().locator('[data-chart-ready]').count(), count => count === 4);
    assert.ok(await health().locator('[data-workops-store-target]').evaluateAll(nodes => nodes.every(node => node.scrollWidth <= node.clientWidth + 1)));
    await target('Google Play').getByRole('heading', { name: 'io.atlas.play', exact: true }).scrollIntoViewIfNeeded(); assert.ok(await reader.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)); await reader.screenshot({ path: resolve(reports, `play-${appearance}-${width}.png`), fullPage: true });
    await target('App Store').getByRole('heading', { name: 'io.atlas.ios', exact: true }).scrollIntoViewIfNeeded(); await reader.screenshot({ path: resolve(reports, `app-store-${appearance}-${width}.png`), fullPage: true });
    await target('Google Play').locator('summary').first().click(); await reader.screenshot({ path: resolve(reports, `history-${appearance}-${width}.png`), fullPage: true });
    await target('App Store').getByRole('heading', { name: 'TestFlight crash feedback', exact: true }).scrollIntoViewIfNeeded(); await reader.screenshot({ path: resolve(reports, `feedback-${appearance}-${width}.png`), fullPage: true });
  }
  assert.deepEqual(errors, []);
  console.log('Atlas store health passed real Git deploy-config/tag and secret reads, production Play/App Store REST adapters against isolated vendor HTTP, scoped external history projection, current/review/TestFlight state, numeric versus unknown histories, escaped content, atomic failed reads/retry, WorkOps VIEW denial, parent bundle replacement/empty state, deferred isolation/retry and both palettes/four widths.');
} catch (error) {
  await reader.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {}); console.error('Store health state', JSON.stringify(await reader.evaluate(() => document.querySelector('[data-workops-release-store-health]')?.textContent ?? 'Store panel not mounted').catch(String))); throw error;
} finally {
  deliverDeferred?.();
  try { await mode({ vendorFailure: false, historyFailure: false, emptyHistory: false, revision: 0 }); if (fixture) await fixture.permission(false); }
  finally { await readerContext.close(); await context.close(); await owned.close(); }
}
