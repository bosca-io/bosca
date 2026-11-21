import { compactResult } from './presentation-checks.mjs';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && process.env.ATLAS_WORKOPS_TEST_PROJECT, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/global-catalogs-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage();
const readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(), errors = [];
for (const tab of [page, reader]) { tab.on('pageerror', error => errors.push(error.message)); tab.on('console', message => { if (message.type() === 'error') errors.push(message.text()); }); }
const gql = async (query, variables = {}, tab = page) => {
  const result = await tab.evaluate(async ({ query, variables }) => (await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) })).json(), { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const catalogs = [
  { kind: 'task-types', label: 'task type', namespace: 'taskTypes', type: 'TaskType', root: 'tasks', field: 'taskTypes', fields: 'description colorHex iconKey hierarchyLevel', category: 'Hierarchy level', choice: 'Epic', categoryKey: 'hierarchyLevel', categoryValue: 'EPIC' },
  { kind: 'statuses', label: 'status', namespace: 'statuses', type: 'Status', root: 'statuses', field: 'all', fields: 'description colorHex category', category: 'Category', choice: 'Cancelled', categoryKey: 'category', categoryValue: 'CANCELLED' },
  { kind: 'priorities', label: 'priority', namespace: 'priorities', type: 'Priority', root: 'tasks', field: 'priorities', fields: 'description colorHex iconKey displayOrder' },
  { kind: 'labels', label: 'label', namespace: 'labels', type: 'Label', root: 'labels', field: 'global', fields: 'colorHex scope' },
  { kind: 'link-types', label: 'link type', namespace: 'linkTypes', type: 'TaskLinkType', root: 'links', field: 'linkTypes', fields: 'inwardLabel outwardLabel category', category: 'Category', choice: 'Causes', categoryKey: 'category', categoryValue: 'CAUSES' },
  { kind: 'resolutions', label: 'resolution', namespace: 'resolutions', type: 'Resolution', root: 'tasks', field: 'resolutions', fields: 'description displayOrder' },
];
const path = c => `/workops/settings/${c.kind}`;
const row = (id, tab = page) => tab.locator(`[data-workops-catalog-row="${id}"]`);
const dialog = (title, tab = page) => tab.getByRole('dialog', { name: title, exact: true });
const read = async c => (await gql(`{workOps{${c.root}{${c.field}{id name version ${c.fields}}}}}`)).workOps[c.root][c.field];
const login = async (tab, isReader = false) => {
  await tab.goto(base + path(catalogs[0])); await tab.locator('input[name=identifier]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER : 'admin');
  await tab.locator('input[name=password]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD : process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await tab.locator('button[type=submit]').click(); await tab.waitForURL(url => url.pathname === path(catalogs[0]));
};
const choose = async (panel, label, choice) => { await panel.getByRole('combobox', { name: label, exact: true }).click(); await panel.page().getByRole('option', { name: choice, exact: true }).click(); };
const inputName = (c, name) => c.kind === 'labels' ? name.replaceAll(' ', '-') : name;
const fill = async (c, panel, name) => {
  await panel.locator('input[name=name]').fill(inputName(c, name));
  if (c.fields.includes('description')) await panel.locator('textarea[name=description]').fill(' Catalog notes ');
  if (c.fields.includes('colorHex')) await panel.locator('input[name=color]').fill(c.kind === 'labels' ? '' : '#1230');
  if (c.kind === 'task-types') await panel.locator('input[name=icon]').fill('custom-task-icon');
  if (c.kind === 'priorities') await choose(panel, 'Icon', 'High (↑)');
  if (c.fields.includes('displayOrder')) await panel.locator('input[name=order]').fill('3');
  if (c.category) await choose(panel, c.category, c.choice);
  if (c.kind === 'link-types') {
    await panel.locator('input[name=outward]').fill(' causes <change> '); await panel.locator('input[name=inward]').fill(' follows & depends ');
    assert.equal((await panel.locator('[data-workops-link-outward]').innerText()).trim(), 'causes <change>'); assert.equal((await panel.locator('[data-workops-link-inward]').innerText()).trim(), 'follows & depends');
    assert.equal(await panel.locator('[data-workops-link-outward] change').count(), 0);
  }
};
const open = async (c, name, tab = page) => { await tab.getByRole('button', { name: `New ${c.label}`, exact: true }).click(); const panel = dialog(`New ${c.label}`, tab); await panel.waitFor(); await fill(c, panel, name); return panel; };
const save = async (c, panel) => {
  await panel.getByRole('button', { name: `Save ${c.label}`, exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  const link = panel.page().getByRole('link', { name: `Show the saved ${c.label}`, exact: true }); await link.waitFor(); await compactResult(link); return new URL(await link.getAttribute('href'), base).searchParams.get('item');
};
const cancel = async panel => { await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await panel.waitFor({ state: 'hidden' }); };
const reject = async (panel, name) => { await panel.getByRole('button', { name, exact: true }).click(); await panel.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); };
const verify = (c, stored, name) => {
  assert.equal(stored.name, c.kind === 'labels' ? name.trim().toLowerCase().replaceAll(' ', '-') : name.trim());
  if (c.fields.includes('description')) assert.equal(stored.description, 'Catalog notes');
  if (c.fields.includes('colorHex')) assert.equal(stored.colorHex, c.kind === 'labels' ? null : '#1230');
  if (c.kind === 'task-types') assert.equal(stored.iconKey, 'custom-task-icon');
  if (c.kind === 'priorities') assert.equal(stored.iconKey, 'arrow-up');
  if (c.fields.includes('displayOrder')) assert.equal(stored.displayOrder, 3);
  if (c.categoryKey) assert.equal(stored[c.categoryKey], c.categoryValue);
  if (c.kind === 'labels') assert.equal(stored.scope, 'GLOBAL');
  if (c.kind === 'link-types') { assert.equal(stored.inwardLabel, 'follows & depends'); assert.equal(stored.outwardLabel, 'causes <change>'); }
};
const update = async (c, stored) => {
  const { id, name, version, ...fields } = stored;
  if (c.kind === 'labels') return gql('mutation($id:UUID!,$version:Long!){workOps{labels{update(id:$id,name:"external-label",colorHex:"#334455",expectedVersion:$version){id}}}}', { id, version });
  return gql(`mutation($id:UUID!,$input:UpdateWorkOps${c.type}Input!){workOps{${c.namespace}{update(id:$id,input:$input){id}}}}`, { id, input: { ...fields, name: 'External catalog update', expectedVersion: version } });
};
const geometry = async (element, isDialog = false) => {
  const rect = await element.evaluate(node => { const r = node.getBoundingClientRect(); return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: innerWidth, height: innerHeight, scroll: document.documentElement.scrollWidth }; });
  assert.ok(rect.left >= 0 && rect.right <= rect.width + 1 && rect.scroll <= rect.width + 1, JSON.stringify(rect));
  if (isDialog) assert.ok(rect.top >= 0 && rect.bottom <= rect.height + 1, JSON.stringify(rect));
};
let readerId, adminGroup, granted = false;
const permission = async add => { await gql(`mutation($group:UUID!,$principal:UUID!){security{${add ? 'add' : 'remove'}PrincipalGroup(groupId:$group,principalId:$principal)}}`, { group: adminGroup, principal: readerId }); granted = add; };
try {
  await login(page); await login(reader, true);
  readerId = (await gql('{security{principals{current{id}}}}', {}, reader)).security.principals.current.id;
  adminGroup = (await gql('{security{groups{find(nameOrDescription:"administrators",offset:0,limit:100,type:SYSTEM){id name}}}}')).security.groups.find.find(group => group.name === 'administrators')?.id; assert.ok(adminGroup);
  for (const c of catalogs) {
    console.log('Catalog behavior', c.kind); await page.goto(base + path(c));
    let panel = await open(c, `Atlas ${c.label}`); c.id = await save(c, panel); let stored = (await read(c)).find(value => value.id === c.id); verify(c, stored, `Atlas ${c.label}`);
    await row(c.id).getByRole('button', { name: `Edit ${stored.name}`, exact: true }).click(); panel = dialog(`Edit ${c.label}`); await panel.waitFor();
    await fill(c, panel, `Retained ${c.label}`); await update(c, stored);
    await reject(panel, `Save ${c.label}`);
    await panel.getByRole('button', { name: 'Retry loading catalog', exact: true }).click(); await panel.page().waitForFunction(() => !document.querySelector('[data-bml-action-pending]')); await page.waitForFunction(name => document.querySelector('dialog[open] input[name=name]')?.getAttribute('value') === name, inputName(c, `Retained ${c.label}`));
    await reject(panel, `Save ${c.label}`); assert.equal(await panel.locator('input[name=name]').inputValue(), inputName(c, `Retained ${c.label}`));
    assert.equal((await read(c)).find(value => value.id === c.id).name, c.kind === 'labels' ? 'external-label' : 'External catalog update'); await cancel(panel);
    await page.getByRole('button', { name: 'Refresh catalog', exact: true }).click(); stored = (await read(c)).find(value => value.id === c.id);
    await row(c.id).getByRole('button', { name: `Edit ${stored.name}`, exact: true }).click(); panel = dialog(`Edit ${c.label}`); await panel.waitFor(); await fill(c, panel, `Atlas ${c.label}`); await save(c, panel);
    stored = (await read(c)).find(value => value.id === c.id); verify(c, stored, `Atlas ${c.label}`); c.name = stored.name;
    if (c.fields.includes('colorHex') && c.kind !== 'labels') assert.ok(await row(c.id).locator('.catalog-name > span:not([aria-hidden])').evaluate(node => getComputedStyle(node).color !== 'rgba(0, 0, 0, 0)'));

    await reader.goto(base + path(c)); panel = await open(c, `Reader ${c.label}`, reader); await reject(panel, `Save ${c.label}`);
    assert.equal(await panel.locator('input[name=name]').inputValue(), inputName(c, `Reader ${c.label}`));
    if (c.fields.includes('colorHex')) assert.equal(await panel.locator('input[name=color]').inputValue(), c.kind === 'labels' ? '' : '#1230');
    await permission(true); const disposable = await save(c, panel); await permission(false);
    const created = (await read(c)).find(value => value.id === disposable); verify(c, created, `Reader ${c.label}`);
    await row(disposable, reader).getByRole('button', { name: `Delete ${created.name}`, exact: true }).click(); panel = dialog(`Delete ${c.label}`, reader); await panel.waitFor(); await cancel(panel); assert.ok((await read(c)).some(value => value.id === disposable));
    await row(disposable, reader).getByRole('button', { name: `Delete ${created.name}`, exact: true }).click(); await panel.waitFor(); await panel.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await reject(panel, `Delete ${c.label}`);
    assert.ok((await read(c)).some(value => value.id === disposable)); await permission(true); await panel.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await panel.getByRole('button', { name: `Delete ${c.label}`, exact: true }).click(); await panel.waitFor({ state: 'hidden' }); await permission(false);
    assert.equal((await read(c)).some(value => value.id === disposable), false);
  }

  const status = (await gql('query($key:String!){workOps{tasks{taskByKey(key:$key){status{id name}}}}}', { key: process.env.ATLAS_WORKOPS_TEST_TASK_KEY })).workOps.tasks.taskByKey.status;
  const statuses = catalogs.find(c => c.kind === 'statuses');
  await page.goto(`${base}${path(statuses)}?item=${status.id}`); await row(status.id).getByRole('button', { name: `Delete ${status.name}`, exact: true }).click();
  let deleting = dialog('Delete status'); await deleting.waitFor(); await deleting.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await reject(deleting, 'Delete status');
  assert.ok((await read(statuses)).some(value => value.id === status.id)); await deleting.getByText(`Delete ${status.name}?`, { exact: true }).waitFor(); await cancel(deleting);

  const resolutions = catalogs.find(c => c.kind === 'resolutions');
  for (let index = 0; index < 27; index++) await gql('mutation($input:CreateWorkOpsResolutionInput!){workOps{resolutions{create(input:$input){id}}}}', { input: { name: `Paged resolution ${index}`, displayOrder: 40 + index } });
  await page.goto(base + path(resolutions)); assert.equal(await page.locator('[data-workops-catalog-row]').count(), 25);
  await page.getByRole('navigation', { name: 'Resolutions pages', exact: true }).getByRole('link', { name: 'Next', exact: true }).click(); await page.waitForURL(url => url.searchParams.get('offset') === '25');
  let panel = await open(resolutions, 'Created on second page'); const paged = await save(resolutions, panel);
  await page.getByRole('link', { name: 'Show the saved resolution', exact: true }).click(); await row(paged).waitFor();

  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 });
    for (const c of catalogs) {
      await page.goto(`${base}${path(c)}?item=${c.id}`); await row(c.id).waitFor(); await geometry(page.locator('[data-workops-global-catalog]'));
      await page.screenshot({ path: resolve(reports, `${c.kind}-list-${appearance}-${width}.png`), fullPage: true });
      await row(c.id).getByRole('button', { name: `Edit ${c.name}`, exact: true }).click(); panel = dialog(`Edit ${c.label}`); await panel.waitFor();
      await panel.getByRole('button', { name: `Save ${c.label}`, exact: true }).scrollIntoViewIfNeeded(); await geometry(panel, true);
      await page.screenshot({ path: resolve(reports, `${c.kind}-edit-${appearance}-${width}.png`), fullPage: true }); await cancel(panel);
      await row(c.id).getByRole('button', { name: `Delete ${c.name}`, exact: true }).click(); panel = dialog(`Delete ${c.label}`); await panel.waitFor(); await geometry(panel, true); await cancel(panel);
    }
  }
  assert.deepEqual(errors, []); console.log('All six global catalogs: actual CRUD/readback, nullable/alpha colors, captured stale versions, administrator changes and retries, escaped link previews, deletion confirmation, offset pages and both palettes/four widths passed.');
} catch (error) {
  for (const tab of [page, reader]) console.error('Catalog failure state', JSON.stringify(await tab.evaluate(() => ({ path: location.pathname + location.search, dialogs: [...document.querySelectorAll('dialog[open]')].map(node => node.innerText.slice(0, 2400)) })).catch(String)));
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {}); await reader.screenshot({ path: resolve(reports, 'reader-failure.png'), fullPage: true }).catch(() => {}); throw error;
} finally { if (granted) await permission(false); await readerContext.close(); await context.close(); await owned.close(); }
