import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';
import { releaseEnvironmentsFixture } from './release-environments-fixture.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/release-environments-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), admin = await context.newPage(), readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(), errors = [];
let fixture;
for (const tab of [admin, reader]) {
  tab.on('pageerror', error => errors.push(error.message));
  tab.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
}
const gql = async (query, variables = {}) => {
  const result = await admin.evaluate(async ({ query, variables }) => (await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) })).json(), { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const login = async (tab, path, isReader = false) => {
  await tab.goto(base + path); await tab.locator('input[name=identifier]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER : 'admin');
  await tab.locator('input[name=password]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD : process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await tab.locator('button[type=submit]').click(); await tab.waitForURL(url => url.pathname === path);
};
const scope = () => reader.locator('[data-workops-release-environments]');
const selected = id => scope().locator(`[data-workops-selected-environment="${id}"]`);
const select = async id => { await scope().locator(`[data-workops-release-environment="${id}"]`).click(); await selected(id).waitFor(); await scope().locator('[data-bml-action-pending]').waitFor({ state: 'detached' }); };
const refresh = async () => { await scope().getByRole('button', { name: 'Refresh environment state', exact: true }).click(); await scope().locator('[data-bml-action-pending]').waitFor({ state: 'detached' }); };
try {
  await login(admin, '/workops/releases'); fixture = await releaseEnvironmentsFixture(gql, fixtureProject); await fixture.permission(true);
  const path = `/workops/releases/${fixture.release}`; await login(reader, path, true); await reader.bringToFront();
  const families = scope().locator('[data-workops-promotion-family]'); assert.equal(await families.count(), 3);
  await scope().getByRole('heading', { name: 'Infrastructure', exact: true }).waitFor(); await scope().getByRole('heading', { name: 'Google Play', exact: true }).waitFor(); await scope().getByRole('heading', { name: 'App Store', exact: true }).waitFor();
  const infrastructure = families.filter({ has: reader.getByRole('heading', { name: 'Infrastructure', exact: true }) });
  assert.deepEqual(await infrastructure.locator('[data-workops-release-environment]').evaluateAll(nodes => nodes.map(node => node.getAttribute('data-workops-release-environment'))), [fixture.development.id, fixture.staging.id]);
  await selected(fixture.development.id).getByRole('cell', { name: '1.0.0', exact: true }).first().waitFor();
  await selected(fixture.development.id).getByRole('cell', { name: '2.0.0', exact: true }).waitFor();
  await selected(fixture.development.id).getByText('Differs', { exact: true }).waitFor();
  await selected(fixture.internal.id).getByText("None of this release's projects target this environment.", { exact: true }).waitFor();
  await select(fixture.staging.id); await selected(fixture.staging.id).getByText('Not deployed', { exact: true }).waitFor();
  await selected(fixture.staging.id).getByText('Promotes from Development', { exact: true }).waitFor();
  await fixture.deploy(fixture.development.id, fixture.current); await fixture.deploy(fixture.staging.id, fixture.current);
  await reader.getByRole('button', { name: 'Refresh release overview', exact: true }).click();
  await selected(fixture.staging.id).getByText('In sync', { exact: true }).waitFor();
  assert.equal(await scope().locator(`[data-workops-release-environment="${fixture.staging.id}"]`).getAttribute('aria-pressed'), 'true');
  await selected(fixture.staging.id).getByText(/1 deployed \/ 1 reported targets/).waitFor();
  await fixture.permission(false); await refresh(); await scope().getByRole('alert').filter({ hasText: 'Environments could not be refreshed' }).waitFor();
  assert.equal(await scope().getByText('In sync', { exact: true }).count(), 0); await selected(fixture.staging.id).getByText('Stale', { exact: true }).waitFor();
  await fixture.permission(true); await refresh(); await selected(fixture.staging.id).getByText('In sync', { exact: true }).waitFor();
  await select(fixture.play.id); await select(fixture.store.id);
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await readerContext.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await reader.setViewportSize({ width, height: 1000 }); await reader.goto(base + path);
    await scope().scrollIntoViewIfNeeded(); assert.ok(await reader.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await reader.screenshot({ path: resolve(reports, `families-${appearance}-${width}.png`), fullPage: true });
    await select(fixture.staging.id); await selected(fixture.staging.id).scrollIntoViewIfNeeded(); await reader.screenshot({ path: resolve(reports, `deployment-${appearance}-${width}.png`), fullPage: true });
    await select(fixture.play.id); await selected(fixture.play.id).scrollIntoViewIfNeeded(); await reader.screenshot({ path: resolve(reports, `store-family-${appearance}-${width}.png`), fullPage: true });
  }
  assert.deepEqual(errors, []);
  console.log('Atlas release environment state passed disjoint/topologically ordered families, real version drift, compatible-project scope, deployment readback, selected stop retention, parent refresh, denied stale-state retry and both palettes/four widths.');
} catch (error) {
  await reader.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error('Release environment state', JSON.stringify(await scope().innerText().catch(String))); throw error;
} finally {
  try { if (fixture) await fixture.permission(false); } finally { await readerContext.close(); await context.close(); await owned.close(); }
}
