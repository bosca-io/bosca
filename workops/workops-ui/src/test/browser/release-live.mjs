import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';
import { until } from './release-ci-fixture.mjs';
import { releaseLiveFixture } from './release-live-fixture.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, api = process.env.ATLAS_WORKOPS_TEST_API_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && api && [base, api].every(url => ['localhost', '127.0.0.1'].includes(new URL(url).hostname)) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/release-live-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const adminContext = await owned.connection.newContext(), admin = await adminContext.newPage(), context = await owned.connection.newContext(), reader = await context.newPage(), errors = [];
let fixture, token, deliverDeferred, expectedRejection = false;
for (const tab of [admin, reader]) {
  tab.on('pageerror', error => errors.push(error.message));
  tab.on('console', message => { if (message.type() === 'error' && !(expectedRejection && /500|bml:|deferred/i.test(message.text()))) errors.push(message.text()); });
}
const gql = async (source, variables = {}) => {
  const result = await admin.evaluate(async ({ source, variables }) => (await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query: source, variables }) })).json(), { source, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const control = async input => {
  const response = await fetch(api + '/api/_fixture/release-live', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: JSON.stringify(input) });
  assert.equal(response.status, 200, await response.clone().text()); return response.json();
};
const publish = (appId, sessionId = 'visitor', appVersion = '1.0.0', lat = 40, lon = -74, action = 'publish') => control({ action, appId, sessionId, appVersion, lat, lon });
const stats = () => control({ action: 'stats' });
const active = () => stats().then(state => state.subscriptions.filter(row => row.appId?.startsWith('atlas-live-')));
const login = async (tab, isReader = false) => {
  await tab.goto(base + '/workops/releases'); await tab.locator('input[name=identifier]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER : 'admin');
  await tab.locator('input[name=password]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD : process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await tab.locator('button[type=submit]').click(); await tab.waitForURL(url => url.pathname === '/workops/releases');
};
const live = () => reader.locator('[data-workops-release-live]');
const compact = () => live().locator('.live-map').first();
const chart = () => compact().locator('[data-workops-live-chart]');
const options = () => chart().evaluate(node => JSON.parse(node.dataset.config));
const count = value => until('Received session count', () => compact().locator('[data-live-count]').innerText(), text => text === `${value} sessions received`);
const refresh = async () => { await live().getByRole('button', { name: 'Refresh live sources', exact: true }).first().click(); await live().locator('[data-bml-action-pending]').waitFor({ state: 'detached' }); };
const overviewRefresh = async () => { await reader.getByRole('button', { name: 'Refresh release overview', exact: true }).evaluate(node => node.click()); await reader.waitForFunction(() => !document.querySelector('[data-workops-release-overview] [data-bml-action-pending]')); };
const subscriptions = async (size, version) => until('Real subscription scope and cleanup', active, rows => rows.length === size && rows.every(row => row.active === 1 && (version === undefined || row.appVersion === version)));
const deferredPattern = '**/_bml/deferred/*workops-release-live*';
try {
  await login(admin); token = await admin.evaluate(() => globalThis.boscaAuth.getToken()); fixture = await releaseLiveFixture(gql, fixtureProject); await fixture.permission(true);
  // Actual retained messages precede the browser subscription, followed by actual live publications.
  await publish(fixture.app); await publish(fixture.second, 'visitor', '1.0.0', 51, 0);
  await publish(fixture.app, 'other-version', '2.0.0', -33, 151); await publish('atlas-live-outside', 'outside');
  await login(reader, true); const path = `/workops/releases/${fixture.release}`; await reader.goto(base + path);
  await live().waitFor(); await chart().locator('canvas').waitFor(); await count(2); await subscriptions(2, '1.0.0');
  const initial = await options(); assert.equal(initial.series.length, 2); assert.equal(initial.series.flatMap(row => row.data).length, 3);
  assert.equal(new Set(initial.series.map(row => row.itemStyle.color)).size, 2);
  assert.ok(initial.series.every(row => row.data.every(point => !point.name.includes('other-version') && !point.name.includes('outside'))));
  await publish(fixture.app, 'visitor', '1.0.0', 41, -73);
  await until('Real tail replaces the retained position', options, option => option.series.filter(row => row.data.some(point => point.name.startsWith(fixture.app))).every(row => row.data.find(point => point.name.startsWith(fixture.app)).value.join() === '-73,41'));
  await count(2);
  await chart().locator('summary').click(); await until('The native chart exposes all project points as readable rows', () => chart().locator('tbody tr').count(), size => size === 3); await chart().locator('summary').click();

  const starts = (await stats()).started;
  await live().getByRole('button', { name: 'Expand map', exact: true }).click(); const dialog = reader.getByRole('dialog', { name: 'Live sessions', exact: true }); await dialog.waitFor();
  await dialog.locator('[data-chart-ready]').waitFor(); await until('Expanded chart shares the compact projection', () => dialog.locator('[data-workops-live-chart]').getAttribute('data-config'), config => Boolean(config && JSON.parse(config).series.flatMap(row => row.data).length === 3));
  assert.equal((await stats()).started, starts); await publish(fixture.second, 'visitor', '1.0.0', 52, 1);
  await until('Both maps receive tail updates', () => live().locator('[data-workops-live-chart]').evaluateAll(nodes => nodes.map(node => JSON.parse(node.dataset.config).series.flatMap(row => row.data).find(point => point.name.startsWith('atlas-live-desktop'))?.value.join())), values => values.length === 2 && values.every(value => value === '1,52'));
  await dialog.getByRole('switch', { name: 'This release only', exact: true }).click(); await dialog.getByText('All versions', { exact: true }).waitFor(); await count(3); await subscriptions(2, null);
  await dialog.getByRole('switch', { name: 'This release only', exact: true }).click(); await count(2); await subscriptions(2, '1.0.0');
  await dialog.getByRole('button', { name: 'Close map', exact: true }).click(); await dialog.waitFor({ state: 'hidden' });

  await live().getByRole('switch', { name: 'This release only', exact: true }).click(); await live().getByText('All versions', { exact: true }).waitFor(); await count(3); await subscriptions(2, null);
  const beforeReconnect = (await stats()).started;
  await publish(fixture.app, 'visitor', '1.0.0', 42, -72, 'complete');
  await until('Normal completion reconnects to the real retained stream', stats, state => state.started > beforeReconnect); await count(3); await subscriptions(2, null);
  await live().getByRole('switch', { name: 'This release only', exact: true }).click(); await count(2); await subscriptions(2, '1.0.0');

  await fixture.permission(false); await refresh(); await live().getByText(/Application sources are unavailable/).first().waitFor(); await subscriptions(0);
  assert.equal(await live().locator('[data-workops-live-chart]').count(), 0);
  await fixture.permission(true); await refresh(); await count(2); await subscriptions(2, '1.0.0');

  // Deliver an older genuine deferred response after the parent has changed its application links.
  let heldResponse; const delivery = new Promise(resolve => { deliverDeferred = resolve; });
  await reader.route(deferredPattern, async route => { heldResponse = await route.fetch(); await delivery; await route.fulfill({ response: heldResponse }); });
  await reader.goto(base + path); await until('Live-source deferred read reached the server', () => Boolean(heldResponse)); await subscriptions(0);
  await fixture.replace(); await publish(fixture.replacement, 'replacement', '1.0.0', 35, 139); await overviewRefresh(); deliverDeferred();
  await until('Current application authority replaces the older deferred response', () => live().getAttribute('data-live-sources'), raw => raw && JSON.parse(raw).some(source => source.appId === fixture.replacement) && JSON.parse(raw).every(source => source.appId !== fixture.app));
  await reader.unroute(deferredPattern); await count(2); await subscriptions(2, '1.0.0');
  await publish(fixture.app, 'late-removed'); await publish(fixture.replacement, 'replacement', '1.0.0', 36, 140); await count(2);
  await until('Removed applications cannot add points after authority replacement', options, option => option.series.flatMap(row => row.data).every(point => !point.name.startsWith(fixture.app)) && option.series.flatMap(row => row.data).some(point => point.value.join() === '140,36'));

  await reader.route(deferredPattern, route => route.fulfill({ status: 500, body: 'Isolated live-source transport failure' })); expectedRejection = true;
  await reader.goto(base + path); await reader.getByText('Live session sources could not be loaded.', { exact: true }).waitFor(); await subscriptions(0);
  await reader.getByRole('button', { name: 'Refresh release overview', exact: true }).waitFor(); await reader.unroute(deferredPattern);
  await reader.getByRole('button', { name: 'Retry live sources', exact: true }).click(); await count(2); expectedRejection = false;
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await reader.setViewportSize({ width, height: 1000 }); await reader.goto(base + path);
    await count(2); await chart().locator('canvas').waitFor(); await compact().scrollIntoViewIfNeeded();
    assert.ok(await reader.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await reader.screenshot({ path: resolve(reports, `map-${appearance}-${width}.png`), fullPage: true });
    await chart().locator('summary').click(); await chart().locator('table').waitFor(); await reader.screenshot({ path: resolve(reports, `data-${appearance}-${width}.png`), fullPage: true }); await chart().locator('summary').click();
    const state = await stats(); await live().getByRole('button', { name: 'Expand map', exact: true }).click(); await dialog.waitFor(); await dialog.locator('[data-chart-ready]').waitFor();
    const bounds = await dialog.boundingBox(); assert.ok(bounds && bounds.x >= -1 && bounds.x + bounds.width <= width + 1, JSON.stringify(bounds));
    const closeBounds = await dialog.getByRole('button', { name: 'Close map', exact: true }).boundingBox(); assert.ok(closeBounds && closeBounds.y >= 0 && closeBounds.y + closeBounds.height <= 1001, JSON.stringify(closeBounds));
    if (width >= 1024) assert.ok(bounds.width >= 750, 'Expanded maps use the existing large dialog.');
    assert.equal((await stats()).started, state.started); await reader.screenshot({ path: resolve(reports, `expanded-${appearance}-${width}.png`), fullPage: true });
    await dialog.getByRole('button', { name: 'Close map', exact: true }).click(); await dialog.waitFor({ state: 'hidden' });
  }
  await reader.goto(base + '/workops/releases'); await subscriptions(0); assert.deepEqual(errors, []);
  console.log('Atlas live-session maps passed the actual retained/live Analytics broker and authenticated relay, exact release/all-version scope, application/session deduplication, shared project colors and readable coordinates, compact/expanded synchronization, completion/reconnect, source permission revocation and replacement during deferred loading, independent failure/retry, navigation cleanup and both palettes/four widths. TTL/auth-generation races are separately exercised by client lifecycle tests.');
} catch (error) {
  await reader.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error('Release live state', JSON.stringify(await live().innerText().catch(String)), JSON.stringify(await stats().catch(String))); throw error;
} finally {
  deliverDeferred?.();
  try { if (fixture) await fixture.permission(false); }
  finally { await context.close(); await adminContext.close(); await owned.close(); }
}
