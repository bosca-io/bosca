import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';
import { releaseContentFixture } from './release-content-fixture.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, api = process.env.ATLAS_WORKOPS_TEST_API_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && api && [base, api].every(url => ['localhost', '127.0.0.1'].includes(new URL(url).hostname)) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/release-content-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), admin = await context.newPage(), readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(), errors = [];
let expectedRejection = false, fixture;
for (const tab of [admin, reader]) {
  tab.on('pageerror', error => errors.push(error.message));
  tab.on('console', message => { if (message.type() === 'error' && !(expectedRejection && /500|bml: action/.test(message.text()))) errors.push(message.text()); });
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
const notes = () => reader.locator('[data-workops-release-notes]'), artifacts = () => reader.locator('[data-workops-release-artifacts]');
const refreshNotes = async () => { await notes().getByRole('button', { name: 'Refresh notes', exact: true }).click(); await notes().locator('[data-bml-action-pending]').waitFor({ state: 'detached' }); };
const refreshArtifacts = async () => { await artifacts().getByRole('button', { name: 'Refresh artifacts', exact: true }).click(); await artifacts().locator('[data-bml-action-pending]').waitFor({ state: 'detached' }); };
const panel = () => reader.getByRole('dialog', { name: 'Edit notes · RCNAPP · Release content application · 1.0.0', exact: true });
const editor = () => panel().locator('.cm-content[contenteditable=true]');
const edit = async () => { await notes().getByRole('button', { name: 'Edit notes', exact: true }).click(); await panel().waitFor(); await panel().locator('[data-json-ready]').waitFor(); };
const save = () => panel().getByRole('button', { name: 'Save notes', exact: true }).click();
const rejected = async () => { await panel().locator('[data-workops-release-notes-error]').waitFor(); await panel().locator('[data-bml-action-pending]').waitFor({ state: 'detached' }); };
const overviewRefresh = async () => { await reader.getByRole('button', { name: 'Refresh release overview', exact: true }).evaluate(node => node.click()); await reader.waitForFunction(() => !document.querySelector('[data-workops-release-overview] [data-bml-action-pending]')); };
try {
  await login(admin, '/workops/releases');
  const configureNotes = async body => {
    const token = await admin.evaluate(() => globalThis.boscaAuth.getToken());
    const response = await fetch(api + '/api/_fixture/release-notes', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: JSON.stringify(body) });
    assert.equal(response.status, 200, await response.clone().text()); return response.json();
  };
  fixture = await releaseContentFixture(gql, fixtureProject, configureNotes);
  await fixture.permission('VIEW', true); await fixture.projectPermission(true);
  const path = `/workops/releases/${fixture.release}`; await login(reader, path, true); await reader.bringToFront();
  assert.equal(await notes().getByRole('button', { name: 'Generate from tags', exact: true }).isDisabled(), true);
  await notes().getByText(/No localized notes are stored/).waitFor();
  await artifacts().getByText('PENDING', { exact: true }).waitFor(); await artifacts().getByText('EXPECTED', { exact: true }).waitFor();
  assert.ok((await artifacts().innerText()).includes('testing, production'));
  await fixture.publish(); await refreshArtifacts(); await artifacts().getByText('PUBLISHED', { exact: true }).waitFor();
  await fixture.permission('MANAGE', true); await refreshNotes(); await notes().getByRole('button', { name: 'Generate from tags', exact: true }).click();
  await notes().getByText('Release screen improvements', { exact: true }).waitFor();
  let stored = await fixture.notes(); assert.equal(stored[0].manuallyEdited, false); assert.equal(stored[0].variants[0].locale, 'en');
  await edit(); await editor().fill('[ invalid JSON'); await save(); await rejected();
  assert.ok((await editor().innerText()).includes('[ invalid JSON'));
  const draft = JSON.stringify([{ locale: 'en', playReleaseNotes: 'Saved Play <copy>', appStoreWhatsNew: 'Saved store copy', testFlightWhatToTest: 'Saved testing copy' }], null, 2);
  await editor().fill(draft); await fixture.permission('MANAGE', false); expectedRejection = true; await save(); await rejected(); expectedRejection = false;
  assert.ok((await editor().innerText()).includes('Saved Play <copy>')); assert.equal((await fixture.notes())[0].manuallyEdited, false);
  await fixture.permission('MANAGE', true); await save(); await panel().waitFor({ state: 'hidden' });
  await notes().getByText('Saved Play <copy>', { exact: true }).waitFor(); stored = await fixture.notes(); assert.equal(stored[0].manuallyEdited, true); assert.equal(stored[0].variants[0].playReleaseNotes, 'Saved Play <copy>');
  await fixture.breakNotesRead(); expectedRejection = true; await refreshNotes(); expectedRejection = false;
  await notes().getByText('Stale', { exact: true }).waitFor(); assert.match(await notes().innerText(), /missing localization fields/);
  assert.equal(await notes().getByRole('button', { name: 'Edit notes', exact: true }).isDisabled(), true);
  assert.equal(await notes().getByText(/No localized notes are stored/).count(), 0);
  await fixture.restoreNotes(JSON.parse(draft)); await refreshNotes(); assert.equal(await notes().getByText('Stale', { exact: true }).count(), 0);
  await edit(); await editor().fill('[ captured invalid draft'); await editor().focus();
  await fixture.bundle(false); await overviewRefresh();
  await notes().getByText(/No localized notes are stored/).waitFor(); await artifacts().getByText(/No declared or visible registered artifacts/).waitFor();
  await reader.waitForFunction(() => document.querySelector('dialog[open] .cm-content')?.contains(document.activeElement));
  assert.ok((await editor().innerText()).includes('[ captured invalid draft')); assert.equal(await editor().evaluate(node => node.contains(document.activeElement)), true);
  await editor().fill(draft); expectedRejection = true; await save(); await rejected(); expectedRejection = false;
  assert.match(await panel().locator('[data-workops-release-notes-error]').innerText(), /not bundled/);
  await fixture.bundle(true); await save(); await panel().waitFor({ state: 'hidden' }); await overviewRefresh(); await artifacts().getByText('PUBLISHED', { exact: true }).waitFor();
  await fixture.hideProject(); await reader.goto(base + path); await artifacts().getByRole('cell', { name: /Project unavailable/ }).first().waitFor();
  assert.equal((await notes().innerText()).includes('Release content application'), false);
  await fixture.projectPermission(true); await reader.goto(base + path);
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await readerContext.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await reader.setViewportSize({ width, height: 1000 }); await reader.goto(base + path);
    await artifacts().scrollIntoViewIfNeeded(); assert.ok(await reader.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)); await reader.screenshot({ path: resolve(reports, `artifacts-${appearance}-${width}.png`), fullPage: true });
    await notes().scrollIntoViewIfNeeded(); await reader.screenshot({ path: resolve(reports, `notes-${appearance}-${width}.png`), fullPage: true });
    await edit(); await editor().fill(draft); const box = await panel().boundingBox(); assert.ok(box && box.x >= 0 && box.x + box.width <= width + 1);
    await reader.screenshot({ path: resolve(reports, `notes-editor-${appearance}-${width}.png`), fullPage: true });
    await panel().getByRole('button', { name: 'Cancel', exact: true }).click(); await panel().waitFor({ state: 'hidden' }); await notes().locator('[data-bml-action-pending]').waitFor({ state: 'detached' });
    await reader.waitForFunction(() => document.querySelector('[data-workops-release-notes]')?.contains(document.activeElement));
  }
  assert.deepEqual(errors, []);
  console.log('Atlas release content passed actual publication/declaration states, tag evidence through an isolated draft generator, localization persistence, independent program permissions, malformed/denied/unbundled draft retries, captured editor refresh/focus, private labels and both palettes/four widths.');
} catch (error) {
  await reader.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error('Release content state', JSON.stringify(await reader.evaluate(() => ({ text: document.querySelector('[data-workops-release-overview]')?.innerText.slice(-12000), dialogs: [...document.querySelectorAll('dialog[open]')].map(node => node.innerText) })).catch(String)));
  throw error;
} finally {
  try { if (fixture) await fixture.restore(); } finally { await readerContext.close(); await context.close(); await owned.close(); }
}
