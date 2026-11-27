import { releaseHeaderControls } from './release-detail-checks.mjs';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';
import { until } from './release-ci-fixture.mjs';
import { releaseHealthFixture } from './release-health-fixture.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, api = process.env.ATLAS_WORKOPS_TEST_API_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && api && [base, api].every(url => ['localhost', '127.0.0.1'].includes(new URL(url).hostname)) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/release-health-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), admin = await context.newPage(), readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(), errors = [];
let expectedRejection = false, fixture, readerId, deliverDeferred;
for (const tab of [admin, reader]) {
  tab.on('pageerror', error => errors.push(error.message));
  tab.on('console', message => { if (message.type() === 'error' && !(expectedRejection && /500|bml:|deferred/i.test(message.text()))) errors.push(message.text()); });
}
const query = async (tab, source, variables = {}) => {
  const result = await tab.evaluate(async ({ source, variables }) => (await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query: source, variables }) })).json(), { source, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const gql = (source, variables) => query(admin, source, variables);
const login = async (tab, isReader = false) => {
  await tab.goto(base + '/workops/releases'); await tab.locator('input[name=identifier]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER : 'admin');
  await tab.locator('input[name=password]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD : process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await tab.locator('button[type=submit]').click(); await tab.waitForURL(url => url.pathname === '/workops/releases');
};
const health = () => reader.locator('[data-workops-release-health]');
const application = () => health().locator(`[data-workops-release-application="${fixture.app}"]`);
const service = identifier => health().locator(`[data-workops-release-service="${identifier}"]`);
const fact = (scope, label) => scope.locator('dl > div').filter({ has: scope.page().getByText(label, { exact: true }) }).locator('dd');
const refresh = async () => { await health().getByRole('button', { name: 'Refresh health', exact: true }).click(); await health().locator('[data-bml-action-pending]').waitFor({ state: 'detached' }); };
const overviewRefresh = async () => { await reader.getByRole('button', { name: 'Refresh release overview', exact: true }).evaluate(node => node.click()); await reader.waitForFunction(() => !document.querySelector('[data-workops-release-overview] [data-bml-action-pending]')); };
const tone = scope => scope.locator('.badge').first().getAttribute('data-tone');
const deferredPattern = '**/_bml/deferred/*workops-release-health*';
try {
  await login(admin); fixture = await releaseHealthFixture(gql, fixtureProject); await fixture.permission(true);
  const token = await admin.evaluate(() => globalThis.boscaAuth.getToken());
  const seedResponse = await fetch(api + '/api/_fixture/release-health', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: JSON.stringify({ app: fixture.app, service: fixture.service }) });
  assert.equal(seedResponse.status, 200, await seedResponse.clone().text()); const seed = await seedResponse.json();
  await login(reader, true); readerId = (await query(reader, '{security{principals{current{id}}}}')).security.principals.current.id;
  const path = `/workops/releases/${fixture.release}`;

  // Hold a real completed private-fragment read while the parent moves to a newer source revision.
  let heldResponse;
  const delivery = new Promise(resolve => { deliverDeferred = resolve; });
  await reader.route(deferredPattern, async route => { heldResponse = await route.fetch(); await delivery; await route.fulfill({ response: heldResponse }); });
  await reader.goto(base + path); await until('Health deferred read reached the server', () => Boolean(heldResponse));
  await reader.locator('[data-workops-release-health-loading]').waitFor();
  assert.equal(await health().count(), 0); await reader.getByRole('button', { name: 'Refresh release overview', exact: true }).waitFor();
  await fixture.replaceApp('atlas-health-replacement'); await overviewRefresh(); deliverDeferred();
  await health().locator('[data-workops-release-application="atlas-health-replacement"]').waitFor();
  await until('The current source replaces the older deferred response', () => application().count(), count => count === 0);
  await reader.unroute(deferredPattern);
  await fixture.replaceApp(fixture.app); await overviewRefresh(); await application().waitFor();

  await application().getByText(/Error groups unavailable/).waitFor();
  await application().getByText(/Session metrics unavailable/).waitFor();
  assert.equal((await fact(application(), 'Recent active sessions').innerText()).trim(), 'Unavailable');
  assert.equal((await fact(application(), 'Open error groups').innerText()).trim(), 'Unavailable');
  assert.equal(await tone(application()), 'neutral');
  assert.equal((await fact(service(fixture.service), 'HTTP error rate').innerText()).trim(), 'Unavailable');
  assert.equal(await tone(service(fixture.service)), 'neutral');
  assert.equal(await health().getByText('Stale', { exact: true }).count(), 0);

  await fixture.analyticsPermission(readerId, true); await refresh();
  assert.equal((await fact(application(), 'Recent active sessions').innerText()).trim(), '7');
  assert.equal((await fact(service(fixture.service), 'HTTP error rate').innerText()).trim(), '9.09%');
  for (const [label, value] of [['2xx responses', '100'], ['4xx responses', '5'], ['5xx responses', '5']]) assert.equal((await fact(service(fixture.service), label).innerText()).trim(), value);
  assert.equal(await tone(service(fixture.service)), 'error');
  assert.equal((await fact(service(fixture.idle), 'HTTP error rate').innerText()).trim(), '0.00%');
  await service(fixture.idle).getByText('No responses were reported in this window.', { exact: true }).waitFor(); assert.equal(await tone(service(fixture.idle)), 'neutral');

  assert.equal((await fact(application(), 'Open error groups').innerText()).trim(), '70');
  assert.equal((await fact(application(), 'Fatal error groups').innerText()).trim(), '60'); assert.equal(await tone(application()), 'error');
  assert.equal(Date.parse(await application().locator('p').filter({ hasText: 'Latest open error' }).locator('time').getAttribute('datetime')), Date.parse(seed.latestError));
  for (const [scope, time, value, rows] of [[application(), seed.sessionTime, 7, 24], [service(fixture.service), seed.httpTime, 100 / 11, 60]]) {
    const chart = scope.locator('[data-bml-chart]'); await chart.locator('summary').click();
    await until('The real chart series is available as a readable data table', () => chart.locator('tbody tr').count(), count => count === rows);
    const samples = await chart.locator('tbody tr').evaluateAll(nodes => nodes.map(row => ({ time: row.querySelector('td').textContent, value: Number(row.querySelectorAll('td')[1].textContent.split(', ').at(-1)) })));
    const sample = samples.find(row => Date.parse(row.time) === Date.parse(time)); assert.ok(sample, JSON.stringify(samples)); assert.ok(Math.abs(sample.value - value) < 0.0001);
    await chart.locator('summary').click();
  }
  await fixture.analyticsPermission(readerId, false); expectedRejection = true; await refresh(); expectedRejection = false;
  await application().getByText(/Error groups unavailable/).waitFor();
  assert.match(await fact(application(), 'Open error groups').innerText(), /70\s*Stale/); assert.equal(await tone(application()), 'neutral');
  assert.match(await fact(application(), 'Recent active sessions').innerText(), /7\s*Stale/);
  assert.match(await fact(service(fixture.service), 'HTTP error rate').innerText(), /9.09%\s*Stale/); assert.equal(await tone(service(fixture.service)), 'neutral');
  await fixture.analyticsPermission(readerId, true); await refresh();
  await fixture.permission(false); expectedRejection = true; await refresh(); expectedRejection = false;
  await health().getByText(/Telemetry sources are unavailable/).waitFor(); assert.ok(await health().getByText('Stale', { exact: true }).count() >= 3);
  assert.equal(await tone(application()), 'neutral'); assert.equal(await tone(service(fixture.service)), 'neutral');
  await fixture.permission(true); await refresh(); assert.equal(await health().getByText('Stale', { exact: true }).count(), 0);

  // Transport failure belongs to the deferred health boundary, independently of the release shell.
  await reader.route(deferredPattern, route => route.fulfill({ status: 500, body: 'Isolated health transport failure' }));
  expectedRejection = true; await reader.goto(base + path); await reader.getByText('Application and service health could not be loaded. Retry to check again.', { exact: true }).waitFor();
  await reader.getByRole('button', { name: 'Refresh release overview', exact: true }).waitFor(); await reader.unroute(deferredPattern);
  await reader.getByRole('button', { name: 'Retry health', exact: true }).click(); await application().waitFor(); expectedRejection = false;
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await readerContext.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await reader.setViewportSize({ width, height: 1000 }); await reader.goto(base + path); await application().waitFor();
    await releaseHeaderControls(reader, width);
    await reader.locator('[data-workops-release-content]').getByText('Manage permission on the release program is required to change this release.', { exact: true }).waitFor();
    await application().locator('[data-chart-ready]').waitFor(); await service(fixture.service).locator('[data-chart-ready]').waitFor();
    await application().scrollIntoViewIfNeeded(); assert.ok(await reader.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)); await reader.screenshot({ path: resolve(reports, `application-${appearance}-${width}.png`), fullPage: true });
    await service(fixture.service).scrollIntoViewIfNeeded(); await reader.screenshot({ path: resolve(reports, `service-${appearance}-${width}.png`), fullPage: true });
    await application().locator('summary').click(); await application().locator('table').waitFor(); await reader.screenshot({ path: resolve(reports, `history-${appearance}-${width}.png`), fullPage: true });
  }
  assert.deepEqual(errors, []);
  console.log('Atlas release app/service health passed real counters and error-group persistence, totals beyond a page, separate Analytics permission, actual timestamped histories, known zero versus unavailable, stale reads/retry, source replacement during deferred loading, independent shell failure/retry and both palettes/four widths.');
} catch (error) {
  await reader.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error('Release health state', JSON.stringify(await health().innerText().catch(String))); throw error;
} finally {
  deliverDeferred?.();
  try { if (fixture) { await fixture.permission(false); if (readerId) await fixture.analyticsPermission(readerId, false); } }
  finally { await readerContext.close(); await context.close(); await owned.close(); }
}
