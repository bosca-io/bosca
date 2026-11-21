import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT, readerGroup = process.env.ATLAS_WORKOPS_TEST_READER_GROUP;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && fixtureProject && readerGroup, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/permissions-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage(), readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(), errors = [];
for (const tab of [page, reader]) { tab.on('pageerror', error => errors.push(error.message)); tab.on('console', message => { if (message.type() === 'error') errors.push(message.text()); }); }
const gql = async (query, variables = {}, tab = page) => {
  const result = await tab.evaluate(async ({ query, variables }) => (await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) })).json(), { query, variables }); assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const path = scope => `/workops/settings/permissions?kind=${scope.kind}&entityId=${scope.id}`;
const login = async (tab, isReader = false) => { await tab.goto(base + '/workops/settings/permissions'); await tab.locator('input[name=identifier]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER : 'admin'); await tab.locator('input[name=password]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD : process.env.ATLAS_WORKOPS_TEST_PASSWORD); await tab.locator('button[type=submit]').click(); await tab.waitForURL(url => url.pathname === '/workops/settings/permissions'); };
const read = async scope => (await gql(`query($id:UUID!){workOps{${scope.kind}s{${scope.kind}(id:$id){permissions{groupId action group{id name}}}}}}`, { id: scope.id })).workOps[scope.kind + 's'][scope.kind].permissions;
const access = async (scope, action, add, group = readerGroup) => gql(`mutation($id:UUID!,$group:UUID!){workOps{${scope.kind}s{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:${action})}}}`, { id: scope.id, group });
const row = (group, action, tab = page) => tab.locator(`[data-workops-permission="${group}-${action}"]`);
const dialog = (name, tab = page) => tab.getByRole('dialog', { name, exact: true });
const choose = async (panel, label, name) => { await panel.getByRole('combobox', { name: label, exact: true }).click(); if (label === 'Group') await panel.page().getByRole('combobox', { name: 'Search group', exact: true }).fill(name); await panel.page().getByRole('option', { name, exact: true }).click(); };
const open = async (name, action, tab = page) => { await tab.getByRole('button', { name: 'Add permission', exact: true }).click(); const panel = dialog('Add permission', tab); await panel.waitFor(); await choose(panel, 'Group', name); await choose(panel, 'Action', action); return panel; };
const settled = async panel => { await panel.page().waitForFunction(() => !document.querySelector('dialog[open] form[data-bml-action-pending]')); };
const reject = async (panel, name) => { await panel.getByRole('button', { name, exact: true }).click(); await panel.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); await settled(panel); };
const save = async panel => { await panel.getByRole('button', { name: 'Add permission', exact: true }).click(); await panel.waitFor({ state: 'hidden' }); const result = panel.page().getByRole('link', { name: 'Show the added permission', exact: true }); await result.waitFor(); assert.equal(await result.locator('xpath=ancestor::header[contains(@class,"section-heading")]').count(), 1); assert.ok(Number.parseFloat(await result.evaluate(node => getComputedStyle(node).fontSize)) <= 12); };
const cancel = async panel => { await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await panel.waitFor({ state: 'hidden' }); };
const remove = async (group, action, name, tab = page) => { await row(group, action, tab).getByRole('button', { name: `Remove ${action} from ${name}`, exact: true }).click(); const panel = dialog('Remove permission', tab); await panel.waitFor(); return panel; };
const confirm = async panel => { await panel.getByRole('checkbox', { name: 'Confirm removal', exact: true }).check(); await panel.getByRole('button', { name: 'Remove permission', exact: true }).click(); await panel.waitFor({ state: 'hidden' }); };
const geometry = async (element, modal = false) => { const r = await element.evaluate(node => { const r = node.getBoundingClientRect(); return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: innerWidth, height: innerHeight, scroll: document.documentElement.scrollWidth }; }); assert.ok(r.left >= 0 && r.right <= r.width + 1 && r.scroll <= r.width + 1, JSON.stringify(r)); if (modal) assert.ok(r.top >= 0 && r.bottom <= r.height + 1, JSON.stringify(r)); };
let principal, adminGroup, granted = false, scopes = [];
const admin = async add => { await gql(`mutation($group:UUID!,$principal:UUID!){security{${add ? 'add' : 'remove'}PrincipalGroup(groupId:$group,principalId:$principal)}}`, { group: adminGroup, principal }); granted = add; };
try {
  await login(page); await login(reader, true);
  principal = (await gql('{security{principals{current{id}}}}', {}, reader)).security.principals.current.id;
  adminGroup = (await gql('{security{groups{find(nameOrDescription:"administrators",offset:0,limit:100,type:SYSTEM){id name}}}}')).security.groups.find.find(group => group.name === 'administrators')?.id; assert.ok(adminGroup);
  const owner = (await gql('query($id:UUID!){workOps{projects{project(id:$id){ownerProfileId}}}}', { id: fixtureProject })).workOps.projects.project.ownerProfileId;
  const portfolio = (await gql('mutation($input:WorkOpsPortfolioInput!){workOps{portfolios{create(input:$input){id name}}}}', { input: { key: 'PERM', name: 'Permission portfolio', ownerProfileId: owner } })).workOps.portfolios.create;
  const program = (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id name}}}}', { input: { portfolioId: portfolio.id, key: 'PERMP', name: 'Permission program', ownerProfileId: owner } })).workOps.programs.create;
  const project = (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id name}}}}', { input: { programId: program.id, key: 'PERMT', name: 'Permission project', ownerProfileId: owner } })).workOps.projects.create;
  scopes = [{ ...portfolio, kind: 'portfolio' }, { ...program, kind: 'program' }, { ...project, kind: 'project' }];
  const groups = [];
  for (let index = 0; index < 105; index++) groups.push((await gql('mutation($name:String!){security{groups{addGroup(name:$name,description:"Permission fixture",groupType:SYSTEM){id name}}}}', { name: `Permission paging ${String(index).padStart(3, '0')}` })).security.groups.addGroup);
  const target = groups.at(-1);
  const systemGroups = (await gql('{security{groups{all(type:SYSTEM,offset:0,limit:1000){id name}}}}')).security.groups.all;
  assert.ok(systemGroups.length > 100); assert.equal(systemGroups.some(group => group.id === readerGroup), false);
  for (const scope of scopes) {
    console.log('Permission scope', scope.kind); await page.goto(base + path(scope)); let panel = await open(target.name, 'EDIT');
    assert.equal(await panel.locator('select[name=groupId] option').count(), systemGroups.length + 1); assert.equal(await panel.locator(`select[name=groupId] option[value="${readerGroup}"]`).count(), 0);
    await save(panel); assert.ok((await read(scope)).some(row => row.groupId === target.id && row.action === 'EDIT'));
    panel = await remove(target.id, 'EDIT', target.name); await cancel(panel); assert.ok((await read(scope)).some(row => row.groupId === target.id && row.action === 'EDIT'));
    panel = await remove(target.id, 'EDIT', target.name); await confirm(panel); assert.equal((await read(scope)).some(row => row.groupId === target.id && row.action === 'EDIT'), false);

    await admin(true); await reader.goto(base + path(scope)); panel = await open(target.name, 'EDIT', reader); await admin(false); await reject(panel, 'Add permission');
    assert.equal(await panel.locator('select[name=groupId]').inputValue(), target.id); assert.equal(await panel.locator('select[name=action]').inputValue(), 'EDIT');
    await panel.getByRole('button', { name: 'Retry loading groups', exact: true }).click(); await panel.getByText('Group choices are unavailable. The selected draft is retained.', { exact: true }).waitFor(); assert.equal(await panel.locator('select[name=groupId]').inputValue(), target.id);
    await admin(true); await save(panel); await admin(false); assert.ok((await read(scope)).some(row => row.groupId === target.id && row.action === 'EDIT'));
    panel = await remove(target.id, 'EDIT', target.name, reader); await panel.getByRole('checkbox', { name: 'Confirm removal', exact: true }).check(); await reject(panel, 'Remove permission');
    await access(scope, 'VIEW', true); await access(scope, 'MANAGE', true); await confirm(panel); assert.equal((await read(scope)).some(row => row.groupId === target.id && row.action === 'EDIT'), false);
    await reader.goto(base + path(scope)); await reader.getByText('Group choices are unavailable. Refresh to try again. Existing grants load separately.', { exact: true }).waitFor();
    const principalGrant = (await read(scope)).find(row => row.groupId === readerGroup && row.action === 'VIEW'); await row(readerGroup, 'VIEW', reader).getByText(principalGrant.group.name, { exact: true }).waitFor();
    assert.equal(await reader.getByRole('button', { name: 'Add permission', exact: true }).isDisabled(), true);
    await access(scope, 'MANAGE', false); await reader.getByRole('button', { name: 'Refresh permissions', exact: true }).click(); await reader.getByText('No direct grants are visible on this page. Manage access is required to view grants.', { exact: true }).waitFor(); assert.ok((await read(scope)).some(row => row.groupId === readerGroup));
    await access(scope, 'VIEW', false); await reader.getByRole('button', { name: 'Refresh permissions', exact: true }).click(); await reader.getByText(`Choose an available ${scope.kind} to view its direct grants.`, { exact: true }).waitFor(); assert.equal(await reader.locator('[data-workops-permission]').count(), 0);
    await access(scope, 'EDIT', true, target.id);
  }
  const parent = scopes[0], child = scopes[2];
  await access(parent, 'VIEW', true); await access(parent, 'MANAGE', true); await reader.goto(base + path(child)); await row(target.id, 'EDIT', reader).waitFor();
  await reader.getByText(/Program and portfolio grants can also provide access/).waitFor(); assert.equal((await read(child)).some(row => row.groupId === readerGroup), false);
  let panel = await remove(target.id, 'EDIT', target.name, reader); await confirm(panel); assert.ok((await read(parent)).some(row => row.groupId === readerGroup && row.action === 'MANAGE')); assert.equal((await read(child)).some(row => row.groupId === target.id && row.action === 'EDIT'), false);
  await access(child, 'EDIT', true, target.id); await access(parent, 'MANAGE', false); await reader.getByRole('button', { name: 'Refresh permissions', exact: true }).click(); await reader.getByText('No direct grants are visible on this page. Manage access is required to view grants.', { exact: true }).waitFor(); await access(parent, 'VIEW', false);
  await access(child, 'EXECUTE', true, target.id); await page.goto(base + path(child)); panel = await remove(target.id, 'EXECUTE', target.name); await confirm(panel); assert.equal((await read(child)).some(row => row.groupId === target.id && row.action === 'EXECUTE'), false);
  for (const group of groups.slice(0, 27)) await access(child, 'VIEW', true, group.id);
  await page.goto(base + path(child)); assert.equal(await page.locator('[data-workops-permission]').count(), 25); await page.getByRole('navigation', { name: 'Direct permission pages', exact: true }).getByRole('link', { name: 'Next', exact: true }).click(); await page.waitForURL(url => url.searchParams.get('offset') === '25'); panel = await open(target.name, 'LIST'); await save(panel);
  const result = page.getByRole('link', { name: 'Show the added permission', exact: true }); const href = new URL(await result.getAttribute('href'), base); assert.equal(href.searchParams.get('kind'), 'project'); assert.equal(href.searchParams.get('entityId'), child.id); await result.click(); await row(target.id, 'LIST').waitFor();
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 });
    for (const scope of scopes) {
      await page.goto(`${base}${path(scope)}&grant=${target.id}-EDIT`); await row(target.id, 'EDIT').waitFor(); await geometry(page.locator('[data-workops-permissions]')); await page.screenshot({ path: resolve(reports, `${scope.kind}-list-${appearance}-${width}.png`), fullPage: true });
      panel = await open(target.name, 'VIEW'); await geometry(panel, true); await page.screenshot({ path: resolve(reports, `${scope.kind}-add-${appearance}-${width}.png`), fullPage: true }); await cancel(panel);
      panel = await remove(target.id, 'EDIT', target.name); await geometry(panel, true); await page.screenshot({ path: resolve(reports, `${scope.kind}-remove-${appearance}-${width}.png`), fullPage: true }); await cancel(panel);
    }
  }
  assert.deepEqual(errors, []); console.log('Permission settings: all three scopes, actual system-group discovery beyond100, existing principal-group labels, exact grant/confirmed revocation readback, denied drafts and role/catalog retries, independent entity access, inherited Manage with direct-only rows, stored EXECUTE removal, offset results and both palettes/four widths passed.');
} catch (error) { for (const tab of [page, reader]) console.error('Permission failure state', JSON.stringify(await tab.evaluate(() => ({ path: location.pathname + location.search, dialogs: [...document.querySelectorAll('dialog[open]')].map(node => node.innerText.slice(0, 1800)) })).catch(String))); await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {}); await reader.screenshot({ path: resolve(reports, 'reader-failure.png'), fullPage: true }).catch(() => {}); throw error; }
finally { if (granted) await admin(false); for (const scope of scopes) for (const action of ['VIEW', 'MANAGE']) await access(scope, action, false); await readerContext.close(); await context.close(); await owned.close(); }
