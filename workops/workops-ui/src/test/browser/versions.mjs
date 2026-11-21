import { compactResult } from './presentation-checks.mjs';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/versions-browser', new URL(base).port); await mkdir(reports, { recursive: true });
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
const choose = async (scope, label, value, tab = page) => {
  await scope.getByRole('combobox', { name: label, exact: true }).click(); await tab.getByRole('option', { name: value, exact: true }).click();
};
const dialog = (name, tab = page) => tab.getByRole('dialog', { name, exact: true });
const row = (id, tab = page) => tab.locator(`[data-workops-project-catalog-row="${id}"]`);
const hub = project => `/workops/releases/versions?projectId=${project}`;
const read = async project => (await gql('query($project:UUID!){workOps{versions{byProject(projectId:$project){id name description startDate releaseDate released archived sequenceNumber version}}}}', { project })).workOps.versions.byProject;
const createApi = async (projectId, name) => (await gql('mutation($input:CreateWorkOpsVersionInput!){workOps{versions{create(input:$input){id version}}}}', { input: { projectId, name } })).workOps.versions.create;
const open = async (name, description = '', start = '', release = '', tab = page) => {
  await tab.getByRole('button', { name: 'New version', exact: true }).click(); const panel = dialog('New version', tab); await panel.waitFor();
  await panel.locator('input[name=name]').fill(name); await panel.locator('textarea[name=description]').fill(description);
  await panel.locator('input[name=startDate]').fill(start); await panel.locator('input[name=releaseDate]').fill(release);
  const widths = await panel.locator('input[name=name]').evaluate(node => { const input = node.getBoundingClientRect(), body = node.closest('dialog').getBoundingClientRect(); return { input: input.width, body: body.width, left: input.left - body.left, right: body.right - input.right }; });
  assert.ok(widths.input >= widths.body - 48 && widths.left <= 24 && widths.right <= 24, JSON.stringify(widths)); return panel;
};
const submit = async (panel, tab = page) => {
  await panel.getByRole('button', { name: 'Create version', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  const result = tab.getByRole('link', { name: 'Show the created version', exact: true }); await result.waitFor(); await compactResult(result);
  return new URL(await result.getAttribute('href'), base).searchParams.get('item');
};
const cancel = async panel => { await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await panel.waitFor({ state: 'hidden' }); };
const permission = async (project, action, add) => gql(`mutation($id:UUID!,$group:UUID!){workOps{projects{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:${action})}}}`, { id: project, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
const reject = async panel => { expectedRejection = true; await panel.getByRole('button', { name: /^(Create|Release|Delete) version$/, exact: true }).click(); await panel.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); expectedRejection = false; };
try {
  await login(page, hub(fixtureProject));
  const parent = (await gql('query($id:UUID!){workOps{projects{project(id:$id){ownerProfileId program{id}}}}}', { id: fixtureProject })).workOps.projects.project;
  const createProject = async (key, name) => (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id version}}}}', { input: { programId: parent.program.id, ownerProfileId: parent.ownerProfileId, key, name } })).workOps.projects.create;
  const project = (await createProject('VERS', 'Atlas delivery versions')).id;
  const archived = await createProject('VOLD', 'Archived version project');
  await gql('mutation($id:UUID!,$version:Long!){workOps{projects{archive(id:$id,expectedVersion:$version){id}}}}', archived);
  await page.goto(base + hub(project)); await page.getByText('No versions yet.', { exact: true }).waitFor();
  await choose(page, 'Project', 'VOLD — Archived version project');
  await page.waitForURL(url => url.searchParams.get('projectId') === archived.id);
  let panel = await open('Archived project version'); const archivedProjectVersion = await submit(panel);
  assert.ok((await read(archived.id)).some(value => value.id === archivedProjectVersion));
  await choose(page, 'Project', 'VERS — Atlas delivery versions');
  await page.waitForURL(url => url.searchParams.get('projectId') === project);
  panel = await open(' 1.2.3 ', ' Delivery notes ', '2026-12-01', '2026-12-15'); const dated = await submit(panel);
  let stored = (await read(project)).find(value => value.id === dated);
  assert.equal(stored.name, '1.2.3'); assert.equal(stored.description, 'Delivery notes');
  assert.equal(new Date(stored.startDate).toISOString(), '2026-12-01T00:00:00.000Z'); assert.equal(new Date(stored.releaseDate).toISOString(), '2026-12-15T00:00:00.000Z');
  await row(dated).getByRole('button', { name: 'Release 1.2.3', exact: true }).click(); const releasing = dialog('Release version'); await releasing.waitFor();
  await gql('mutation($id:UUID!,$version:Long!){workOps{versions{release(id:$id,expectedVersion:$version){id}}}}', { id: dated, version: stored.version });
  await releasing.getByRole('checkbox', { name: 'Confirm release', exact: true }).check(); await reject(releasing);
  await releasing.getByText('Mark 1.2.3 as released?', { exact: true }).waitFor();
  assert.equal((await read(project)).find(value => value.id === dated).version, stored.version + 1);
  await cancel(releasing); await page.getByRole('button', { name: 'Refresh versions', exact: true }).click();
  await row(dated).getByText('Released', { exact: true }).waitFor(); assert.equal(await row(dated).getByRole('button', { name: 'Release 1.2.3', exact: true }).count(), 0);
  assert.equal(new Date((await read(project)).find(value => value.id === dated).releaseDate).toISOString(), '2026-12-15T00:00:00.000Z');

  panel = await open('Undated version'); const undated = await submit(panel);
  assert.equal((await read(project)).find(value => value.id === undated).startDate, null);
  await row(undated).getByRole('button', { name: 'Release Undated version', exact: true }).click(); await releasing.waitFor();
  await releasing.getByRole('checkbox', { name: 'Confirm release', exact: true }).check(); await releasing.getByRole('button', { name: 'Release version', exact: true }).click(); await releasing.waitFor({ state: 'hidden' });
  stored = (await read(project)).find(value => value.id === undated); assert.equal(stored.released, true); assert.ok(stored.releaseDate);
  panel = await open('Disposable version'); const disposable = await submit(panel);
  await row(disposable).getByRole('button', { name: 'Delete Disposable version', exact: true }).click(); const deleting = dialog('Delete version'); await deleting.waitFor();
  await cancel(deleting); assert.ok((await read(project)).some(value => value.id === disposable));
  await row(disposable).getByRole('button', { name: 'Delete Disposable version', exact: true }).click(); await deleting.waitFor();
  await deleting.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await deleting.getByRole('button', { name: 'Delete version', exact: true }).click(); await deleting.waitFor({ state: 'hidden' });
  await row(disposable).waitFor({ state: 'detached' }); assert.equal((await read(project)).some(value => value.id === disposable), false);

  const referenced = process.env.ATLAS_WORKOPS_TEST_REFERENCED_VERSION, archivedVersion = process.env.ATLAS_WORKOPS_TEST_ARCHIVED_VERSION;
  assert.ok(referenced && archivedVersion);
  await page.goto(base + hub(fixtureProject)); await row(referenced).getByRole('button', { name: 'Delete Fixture referenced version', exact: true }).click(); await deleting.waitFor();
  await deleting.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await reject(deleting);
  await deleting.getByRole('alert').filter({ hasText: /still referenced by tasks/ }).waitFor(); assert.ok((await read(fixtureProject)).some(value => value.id === referenced));
  await cancel(deleting);
  await row(archivedVersion).getByText('Archived', { exact: true }).waitFor();
  await row(archivedVersion).getByRole('button', { name: 'Release Fixture archived version', exact: true }).click(); await releasing.waitFor();
  await releasing.getByRole('checkbox', { name: 'Confirm release', exact: true }).check(); await releasing.getByRole('button', { name: 'Release version', exact: true }).click(); await releasing.waitFor({ state: 'hidden' });
  stored = (await read(fixtureProject)).find(value => value.id === archivedVersion); assert.equal(stored.released, true); assert.equal(stored.archived, true);

  for (const action of ['VIEW', 'EDIT', 'MANAGE']) await permission(project, action, true);
  const readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(); observe(reader);
  try {
    await login(reader, hub(project), true);
    panel = await open('Retained reader version', 'Reader notes', '2027-01-02', '2027-01-17', reader);
    await permission(project, 'MANAGE', false); await reject(panel);
    assert.equal(await panel.locator('input[name=name]').inputValue(), 'Retained reader version');
    assert.equal(await panel.locator('input[name=startDate]').inputValue(), '2027-01-02'); assert.equal(await panel.locator('input[name=releaseDate]').inputValue(), '2027-01-17');
    await panel.getByRole('button', { name: 'Retry loading catalog', exact: true }).click(); await panel.getByRole('alert').waitFor({ state: 'hidden' });
    assert.equal(await panel.locator('textarea[name=description]').inputValue(), 'Reader notes'); assert.equal(await panel.locator('input[name=startDate]').inputValue(), '2027-01-02');
    await permission(project, 'MANAGE', true); const readerVersion = await submit(panel, reader);
    assert.ok((await read(project)).some(value => value.id === readerVersion));
    await row(readerVersion, reader).getByRole('button', { name: 'Release Retained reader version', exact: true }).click(); panel = dialog('Release version', reader); await panel.waitFor();
    await panel.getByRole('checkbox', { name: 'Confirm release', exact: true }).check(); await permission(project, 'MANAGE', false); await reject(panel);
    assert.equal((await read(project)).find(value => value.id === readerVersion).released, false);
    await permission(project, 'MANAGE', true); await panel.getByRole('checkbox', { name: 'Confirm release', exact: true }).check(); await panel.getByRole('button', { name: 'Release version', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
    await row(readerVersion, reader).getByText('Released', { exact: true }).waitFor();
    await row(readerVersion, reader).getByRole('button', { name: 'Delete Retained reader version', exact: true }).click(); panel = dialog('Delete version', reader); await panel.waitFor();
    await panel.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await permission(project, 'MANAGE', false); await reject(panel);
    assert.ok((await read(project)).some(value => value.id === readerVersion));
    await permission(project, 'MANAGE', true); await panel.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await panel.getByRole('button', { name: 'Delete version', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
    await row(readerVersion, reader).waitFor({ state: 'detached' }); assert.equal((await read(project)).some(value => value.id === readerVersion), false);
  } finally { await readerContext.close(); for (const action of ['VIEW', 'EDIT', 'MANAGE']) await permission(project, action, false); }

  const extras = [];
  for (let index = 0; index < 27; index++) extras.push(await createApi(project, `Paged delivery version ${index}`));
  await page.goto(base + hub(project)); assert.equal(await page.locator('[data-workops-project-catalog-row]').count(), 25);
  const sorted = [...await read(project)].sort((a, b) => b.sequenceNumber - a.sequenceNumber);
  assert.deepEqual(await page.locator('[data-workops-project-catalog-row]').evaluateAll(rows => rows.map(value => value.dataset.workopsProjectCatalogRow)), sorted.slice(0, 25).map(value => value.id));
  await page.getByRole('navigation', { name: 'Versions pages', exact: true }).getByRole('link', { name: 'Next', exact: true }).click();
  await page.waitForURL(url => url.searchParams.get('offset') === '25' && url.searchParams.get('projectId') === project);
  assert.equal(await page.locator('[data-workops-project-catalog-row]').count(), 4);
  panel = await open('Created on second delivery page', 'Result context'); const resultId = await submit(panel);
  await page.getByRole('link', { name: 'Show the created version', exact: true }).click(); await row(resultId).waitFor();
  assert.equal(new URL(page.url()).pathname, '/workops/releases/versions'); assert.equal(new URL(page.url()).searchParams.get('projectId'), project);
  await page.goto(`${base}${hub(project)}&item=${dated}`); await row(dated).waitFor(); assert.equal(new URL(await page.getByRole('navigation', { name: 'Versions pages', exact: true }).getByRole('link', { name: 'Previous', exact: true }).getAttribute('href'), base).searchParams.get('projectId'), project);
  const count = (await read(project)).length;
  const geometry = async (node, label) => {
    const bounds = await node.evaluate(element => ({ left: element.getBoundingClientRect().left, right: element.getBoundingClientRect().right, width: innerWidth, scroll: document.documentElement.scrollWidth }));
    assert.ok(bounds.left >= 0 && bounds.right <= bounds.width + 1 && bounds.scroll <= bounds.width + 1, label + JSON.stringify(bounds));
  };
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 });
    await page.goto(base + hub(project)); await row(resultId).waitFor(); await geometry(page.locator('[data-workops-project-catalog]'), 'Version table');

    const catalogLayout = await page.locator('.workops-catalog-page').evaluate(node => ({ outer: getComputedStyle(node).display, inner: getComputedStyle(node.querySelector(':scope > [data-bml-island]')).display, bottom: node.getBoundingClientRect().bottom, height: innerHeight, scroll: getComputedStyle(node.querySelector('[data-workops-project-catalog]')).overflowY }));
    assert.equal(catalogLayout.outer, 'flex'); assert.equal(catalogLayout.inner, 'flex'); assert.equal(catalogLayout.scroll, 'auto'); assert.ok(catalogLayout.bottom <= catalogLayout.height + 1);
    await page.screenshot({ path: resolve(reports, `versions-${appearance}-${width}.png`), fullPage: true });
    panel = await open('Layout version draft', 'Retained dates in the shared dialog', '2027-02-03', '2027-02-18');
    await panel.getByRole('button', { name: 'Create version', exact: true }).scrollIntoViewIfNeeded(); await geometry(panel, 'Create dialog');
    await page.screenshot({ path: resolve(reports, `create-${appearance}-${width}.png`), fullPage: true }); await cancel(panel);
    await row(resultId).getByRole('button', { name: 'Release Created on second delivery page', exact: true }).click(); await releasing.waitFor(); await geometry(releasing, 'Release dialog'); await cancel(releasing);
    await row(resultId).getByRole('button', { name: 'Delete Created on second delivery page', exact: true }).click(); await deleting.waitFor(); await geometry(deleting, 'Delete dialog'); await cancel(deleting);
  }
  assert.equal((await read(project)).length, count); assert.deepEqual(errors, []);
  console.log('Atlas delivery versions: dates, sequence, archived projects, stale/denied retries, referenced deletion, pages, result context and layouts passed.');
} catch (error) {
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error('Version browser state', JSON.stringify(await page.evaluate(() => ({ path: location.pathname + location.search, dialogs: [...document.querySelectorAll('dialog[open]')].map(node => node.innerText.slice(0, 1600)), alerts: [...document.querySelectorAll('p[role=alert]')].map(node => node.innerText) })).catch(String)));
  throw error;
} finally { await context.close(); await owned.close(); }
