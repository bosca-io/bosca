import { compactResult } from './presentation-checks.mjs';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';
import { releaseBuildLayouts } from './release-build-layouts.mjs';
import { emptyReleaseEdits } from './release-empty-checks.mjs';
import { releaseDetailEdits, releaseReaderEdits, releaseDetailLayouts } from './release-detail-checks.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/release-build-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage(), errors = [];
let expectedRejection = false;
const observe = tab => {
  tab.on('pageerror', error => errors.push(error.message));
  tab.on('console', message => { if (message.type() === 'error' && !(expectedRejection && /500|bml: action/.test(message.text()))) errors.push(message.text()); });
}; observe(page);
const gql = async (query, variables = {}, tab = page) => {
  const result = await tab.evaluate(async ({ query, variables }) => {
    const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) }); return response.json();
  }, { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const login = async (tab, path, reader = false) => {
  await tab.goto(base + path); await tab.locator('input[name=identifier]').fill(reader ? process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER : 'admin');
  await tab.locator('input[name=password]').fill(reader ? process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD : process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await tab.locator('button[type=submit]').click(); await tab.waitForURL(url => url.pathname === path.split('?')[0]);
};
const dialog = (name, tab = page) => tab.getByRole('dialog', { name, exact: true });
const waitStep = async (panel, name) => panel.locator('li[aria-current=step]').filter({ hasText: name }).waitFor();
const choose = async (scope, label, value, tab = page) => {
  await scope.getByRole('combobox', { name: label, exact: true }).click(); await tab.getByRole('option', { name: value, exact: true }).click();
  if (label === 'Project') await tab.waitForFunction(() => !document.querySelector('[data-bml-action-pending]'));
};
const createVersion = async (projectId, name) => (await gql('mutation($input:CreateWorkOpsVersionInput!){workOps{versions{create(input:$input){id version}}}}', { input: { projectId, name } })).workOps.versions.create;
const releaseId = async (tab = page, scope = tab) => { const result = scope.getByRole('link', { name: 'Open the created release', exact: true }); await result.waitFor(); await compactResult(result); return new URL(await result.getAttribute('href'), base).pathname.split('/').at(-1); };
const bundles = async id => (await gql('query($id:UUID!){workOps{crossProject{versionsForRelease(releaseId:$id){projectId versionId}}}}', { id })).workOps.crossProject.versionsForRelease;
const read = async program => (await gql('query($id:UUID!){workOps{crossProject{releases(programId:$id){id name description releaseDate releasedAt version}}}}', { id: program })).workOps.crossProject.releases;
const open = async (name, tab = page) => {
  await tab.getByRole('button', { name: 'Build release', exact: true }).click(); const panel = dialog('Build a release', tab); await panel.waitFor();
  await panel.locator('input[name=name]').fill(name); await panel.locator('textarea[name=description]').fill('Retained release notes'); await panel.locator('input[name=date]').fill('2027-01-15');
  await panel.getByRole('button', { name: 'Next', exact: true }).click(); await panel.locator('[data-workops-release-picker]').waitFor(); return panel;
};
const project = async (panel, label, version, tab = page) => {
  await choose(panel, 'Project', label, tab);
  await panel.locator(`select[name=versionId] option[value="${version}"]`).waitFor({ state: 'attached' });
};
const stage = async (panel, label, id, tab = page) => {
  await choose(panel, 'Version', label, tab); await panel.getByRole('button', { name: 'Add version', exact: true }).click(); await panel.locator(`[data-workops-release-staged="${id}"]`).waitFor();
};
const permission = async (program, action, add) => gql(`mutation($id:UUID!,$group:UUID!){workOps{programs{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:${action})}}}`, { id: program, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
try {
  await login(page, '/workops/releases');
  const parent = (await gql('query($id:UUID!){workOps{projects{project(id:$id){ownerProfileId program{id portfolio{id}}}}}}', { id: fixtureProject })).workOps.projects.project;
  const program = (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id}}}}', { input: { key: 'RBLD', name: 'Atlas release builder', portfolioId: parent.program.portfolio.id, ownerProfileId: parent.ownerProfileId } })).workOps.programs.create.id;
  const makeProject = async (key, name) => (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id}}}}', { input: { key, name, programId: program, ownerProfileId: parent.ownerProfileId } })).workOps.projects.create.id;
  const first = await makeProject('RAPP1', 'Application one'), second = await makeProject('RAPP2', 'Application two');
  const v1 = await createVersion(first, '1.0'), v2 = await createVersion(first, '2.0');
  await gql('mutation($id:UUID!,$version:Long!){workOps{versions{release(id:$id,expectedVersion:$version){id}}}}', v1);
  const path = `/workops/releases?programId=${program}`;
  await page.goto(`${base}/workops/releases?programId=${parent.program.id}&offset=25`);
  assert.equal(await page.getByRole('button', { name: 'Choose program', exact: true }).count(), 0, 'Program changes apply from the dropdown');
  const programIndex = await page.locator('.program-choice select').evaluate((node, id) => [...node.options].findIndex(option => option.value === id), program);
  assert.ok(programIndex > 0, 'The new program is available in the authorized dropdown');
  await page.getByRole('combobox', { name: 'Program', exact: true }).press('Home');
  for (let index = 0; index < programIndex; index++) await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await page.waitForURL(url => url.searchParams.get('programId') === program && !url.searchParams.has('offset'));
  await page.getByText('This program has no releases yet. Build a release to begin.', { exact: true }).waitFor();
  let panel = await open(' Built in Atlas '); await project(panel, 'RAPP1 · Application one', v1.id);
  await stage(panel, '1.0 · released', v1.id); assert.equal(await panel.locator(`select[name=versionId] option[value="${v1.id}"]`).count(), 0);
  await stage(panel, '2.0', v2.id); assert.equal(await panel.locator('[data-workops-release-staged]').count(), 2);
  await choose(panel, 'Project', 'RAPP2 · Application two');
  await page.waitForFunction(() => document.querySelector('[data-workops-release-picker] select[name=projectId]')?.value === document.querySelector('[data-workops-release-picker] select[name=projectId] option:checked')?.value && document.querySelector('[data-workops-release-picker] select[name=versionId]')?.options.length === 1);
  await panel.getByRole('button', { name: 'New version', exact: true }).click(); let version = dialog('New project version'); await version.waitFor();
  assert.equal(await version.locator('input[name=name]').inputValue(), ''); await version.locator('input[name=name]').fill('3.0'); await version.locator('textarea[name=description]').fill('Created in release picker'); await version.locator('input[name=startDate]').fill('2026-12-01'); await version.locator('input[name=releaseDate]').fill('2026-12-15');
  await version.getByRole('button', { name: 'Create version', exact: true }).click(); await version.waitFor({ state: 'hidden' });
  const createdVersion = await panel.locator('select[name=versionId]').inputValue(); assert.ok(createdVersion);
  const storedVersion = (await gql('query($id:UUID!){workOps{versions{byProject(projectId:$id){id description startDate releaseDate}}}}', { id: second })).workOps.versions.byProject.find(value => value.id === createdVersion);
  assert.equal(storedVersion.description, 'Created in release picker'); assert.equal(new Date(storedVersion.startDate).toISOString(), '2026-12-01T00:00:00.000Z'); assert.equal(new Date(storedVersion.releaseDate).toISOString(), '2026-12-15T00:00:00.000Z');
  await panel.getByRole('button', { name: 'Add version', exact: true }).click(); await panel.locator(`[data-workops-release-staged="${createdVersion}"]`).waitFor();
  await panel.getByRole('button', { name: 'Next', exact: true }).click(); await waitStep(panel, 'Review'); assert.equal(await panel.locator('[data-workops-release-review]').count(), 3);
  await panel.getByRole('button', { name: 'Back', exact: true }).click(); await waitStep(panel, 'Projects'); await panel.getByRole('button', { name: 'Back', exact: true }).click(); await waitStep(panel, 'Details');
  assert.equal(await panel.locator('input[name=name]').inputValue(), ' Built in Atlas '); assert.equal(await panel.locator('input[name=date]').inputValue(), '2027-01-15');
  await panel.getByRole('button', { name: 'Next', exact: true }).click(); await waitStep(panel, 'Projects'); await panel.getByRole('button', { name: 'Next', exact: true }).click(); await waitStep(panel, 'Review');
  await panel.getByRole('button', { name: 'Build release', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  const built = await releaseId(); let stored = (await read(program)).find(value => value.id === built);
  assert.equal(stored.name, 'Built in Atlas'); assert.equal(stored.description, 'Retained release notes'); assert.equal(new Date(stored.releaseDate).toISOString(), '2027-01-15T00:00:00.000Z');
  assert.deepEqual(new Set((await bundles(built)).map(value => value.versionId)), new Set([v1.id, v2.id, createdVersion]));
  await releaseDetailEdits({ page, base, built, first, createdVersion, gql, bundles, read, program, dialog, choose, expected: value => { expectedRejection = value; } });
  await page.goto(base + path); stored = (await read(program)).find(value => value.id === built);
  await page.getByRole('button', { name: 'Refresh releases', exact: true }).click(); await page.locator(`[data-workops-release="${built}"]`).getByText('Draft', { exact: true }).waitFor();
  assert.equal(await page.locator('[data-workops-releases]').getByText(/releases shown\./).count(), 0);
  assert.equal(await page.getByRole('navigation', { name: 'Release pages', exact: true }).count(), 0, 'Single-page releases need no count or pagination summary');
  await gql('mutation($id:UUID!,$version:Long!){workOps{crossProject{markReleased(releaseId:$id,expectedVersion:$version){id}}}}', { id: built, version: stored.version });
  await page.getByRole('button', { name: 'Refresh releases', exact: true }).click(); await page.locator(`[data-workops-release="${built}"]`).getByText('Released', { exact: true }).waitFor();

  await emptyReleaseEdits({ page, base, path, open, dialog, releaseId, read, program });

  const missing = await createVersion(first, 'Deleted after staging'), retained = await createVersion(first, 'Retained partial version');
  panel = await open('Partial bundle release'); await project(panel, 'RAPP1 · Application one', missing.id); await stage(panel, 'Deleted after staging', missing.id); await stage(panel, 'Retained partial version', retained.id);
  await gql('mutation($id:UUID!){workOps{versions{delete(id:$id)}}}', { id: missing.id });
  await panel.getByRole('button', { name: 'Next', exact: true }).click(); await waitStep(panel, 'Review'); expectedRejection = true;
  await panel.getByRole('button', { name: 'Build release', exact: true }).click(); await panel.locator('[data-workops-release-build-error]').filter({ hasText: '1 of 2 versions bundled' }).waitFor(); expectedRejection = false;
  const partial = await releaseId(page, panel); assert.deepEqual((await bundles(partial)).map(value => value.versionId), [retained.id]);
  expectedRejection = true; await panel.getByRole('button', { name: 'Retry bundles', exact: true }).click(); await panel.locator('form[data-bml-action-pending]').waitFor({ state: 'detached' }); expectedRejection = false;
  assert.equal(await releaseId(page, panel), partial); assert.equal((await read(program)).filter(value => value.name === 'Partial bundle release').length, 1); assert.equal((await bundles(partial)).length, 1);
  await panel.getByRole('button', { name: 'Close', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: 'Retry pending bundles', exact: true }).click(); await panel.waitFor(); assert.equal(await releaseId(page, panel), partial);
  await panel.getByRole('button', { name: 'Close', exact: true }).click(); await panel.waitFor({ state: 'hidden' });

  await permission(program, 'VIEW', true); const readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(); observe(reader);
  try {
    await login(reader, path, true); panel = await open('Reader release', reader); await project(panel, 'RAPP1 · Application one', v2.id, reader);
    await panel.getByRole('button', { name: 'New version', exact: true }).click(); version = dialog('New project version', reader); await version.waitFor();
    await version.locator('input[name=name]').fill('Reader inline version'); await version.locator('textarea[name=description]').fill('Retained reader version'); await version.locator('input[name=startDate]').fill('2026-12-02'); await version.locator('input[name=releaseDate]').fill('2026-12-16');
    expectedRejection = true; await version.getByRole('button', { name: 'Create version', exact: true }).click(); await version.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); expectedRejection = false;
    assert.equal(await version.locator('input[name=releaseDate]').inputValue(), '2026-12-16'); assert.equal(await version.locator('textarea[name=description]').inputValue(), 'Retained reader version');
    await permission(program, 'MANAGE', true); await version.getByRole('button', { name: 'Create version', exact: true }).click(); await version.waitFor({ state: 'hidden' });
    const readerVersion = await panel.locator('select[name=versionId]').inputValue(); await panel.getByRole('button', { name: 'Add version', exact: true }).click(); await panel.locator(`[data-workops-release-staged="${readerVersion}"]`).waitFor();
    await panel.getByRole('button', { name: 'Next', exact: true }).click(); await waitStep(panel, 'Review'); await permission(program, 'MANAGE', false); expectedRejection = true;
    await panel.getByRole('button', { name: 'Build release', exact: true }).click(); await panel.locator('[data-workops-release-build-error]').waitFor(); expectedRejection = false;
    assert.equal((await read(program)).some(value => value.name === 'Reader release'), false); assert.equal(await panel.locator('[data-workops-release-review]').count(), 1);
    await permission(program, 'MANAGE', true); await panel.getByRole('button', { name: 'Build release', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
    const readerRelease = await releaseId(reader); assert.equal((await read(program)).find(value => value.id === readerRelease).name, 'Reader release'); assert.deepEqual((await bundles(readerRelease)).map(value => value.versionId), [readerVersion]);
    await releaseReaderEdits({ reader, base, release: readerRelease, gql, read, bundles, program, permission, dialog, expected: value => { expectedRejection = value; } });
  } catch (error) {
    await reader.screenshot({ path: resolve(reports, 'reader-failure.png'), fullPage: true }).catch(() => {}); console.error('Reader release state', JSON.stringify(await reader.evaluate(() => [...document.querySelectorAll('dialog[open]')].map(node => node.innerText.slice(0, 2000))).catch(String))); throw error;
  } finally { await readerContext.close(); for (const action of ['VIEW', 'MANAGE']) await permission(program, action, false); }
  for (let index = 0; index < 26; index++) await gql('mutation($id:UUID!,$name:String!){workOps{crossProject{createRelease(input:{programId:$id,name:$name}){id}}}}', { id: program, name: `Paged release ${index}` });
  await page.goto(base + path); assert.equal(await page.locator('[data-workops-release]').count(), 25);
  await page.getByRole('navigation', { name: 'Release pages', exact: true }).getByRole('link', { name: 'Next', exact: true }).click(); await page.waitForURL(url => url.searchParams.get('offset') === '25' && url.searchParams.get('programId') === program);
  const count = (await read(program)).length;
  await releaseBuildLayouts({ page, context, base, path, projectLabel: 'RAPP1 · Application one', versionLabel: '2.0', dialog, choose, reports });
  await releaseDetailLayouts({ page, context, base, built, reports, dialog, choose });
  assert.equal((await read(program)).length, count); assert.deepEqual(errors, []);
  console.log('Atlas release build and detail editing: directory, staged/bundled versions, UTC dates, partial retries, retained and stale edits, ordinary permissions, confirmed unbundle/delete, pagination and layouts passed. Native CI controls and telemetry remain separate.');
} catch (error) {
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error('Release browser state', JSON.stringify(await page.evaluate(() => ({ path: location.pathname + location.search, dialogs: [...document.querySelectorAll('dialog[open]')].map(node => node.innerText.slice(0, 2200)), alerts: [...document.querySelectorAll('p[role=alert]')].map(node => node.innerText) })).catch(String)));
  throw error;
} finally { await context.close(); await owned.close(); }
