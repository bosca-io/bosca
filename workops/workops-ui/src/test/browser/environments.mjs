import { compactResult } from './presentation-checks.mjs';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';
import { environmentTypes } from './environment-types.mjs';
import { environmentLayouts } from './environment-layouts.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/environments-browser', new URL(base).port); await mkdir(reports, { recursive: true });
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
const reject = async (panel, button, environment = false) => {
  expectedRejection = true; await panel.getByRole('button', { name: button, exact: true }).click();
  await (environment ? panel.locator('[data-workops-environment-form-error]') : panel.getByRole('alert')).filter({ hasText: /.+/ }).waitFor(); expectedRejection = false;
};
const cancel = async panel => { await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await panel.waitFor({ state: 'hidden' }); await panel.page().waitForFunction(() => !document.querySelector('[data-bml-action-pending]')); };
const row = (id, tab = page) => tab.locator(`[data-workops-environment="${id}"]`);
const hub = program => `/workops/releases/environments?programId=${program}`;
const read = async program => (await gql('query($program:UUID!){workOps{multiRepo{environments(programId:$program){id key name description displayOrder requiresApproval autoPromote typeId promotionSourceIds version permissions{groupId action}}}}}', { program })).workOps.multiRepo.environments;
const createApi = async input => (await gql('mutation($input:CreateWorkOpsEnvironmentInput!){workOps{multiRepo{createEnvironment(input:$input){id}}}}', { input })).workOps.multiRepo.createEnvironment.id;
const updateApi = async (value, input) => gql('mutation($id:UUID!,$version:Long!,$input:UpdateWorkOpsEnvironmentInput!){workOps{multiRepo{updateEnvironment(id:$id,input:$input,expectedVersion:$version){id}}}}', { id: value.id, version: value.version, input });
const permission = async (program, action, add) => gql(`mutation($id:UUID!,$group:UUID!){workOps{programs{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:${action})}}}`, { id: program, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
const submit = async (panel, tab = page) => {
  await panel.getByRole('button', { name: 'Save environment', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  const result = tab.getByRole('link', { name: 'Show the saved environment', exact: true }); await result.waitFor(); await compactResult(result);
    assert.equal(await result.locator('xpath=ancestor::header[contains(@class,"page-heading")]').count(), 1, 'Saved-item navigation stays in the page actions');
    assert.equal((await result.innerText()).trim(), 'Saved environment');
    assert.ok(Number.parseFloat(await result.evaluate(node => getComputedStyle(node).fontSize)) <= 12);
    return new URL(await result.getAttribute('href'), base).searchParams.get('item');
};
try {
  await login(page, '/workops/settings/environment-types');
  const type = await environmentTypes({ page, base, gql, dialog, reject });
  const alternateType = (await gql('mutation{workOps{multiRepo{createEnvironmentType(name:"Atlas replacement stage",displayOrder:4){id}}}}')).workOps.multiRepo.createEnvironmentType.id;
  const parent = (await gql('query($id:UUID!){workOps{projects{project(id:$id){ownerProfileId program{portfolio{id}}}}}}', { id: fixtureProject })).workOps.projects.project;
  const createProgram = async (key, name) => (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id version}}}}', { input: { portfolioId: parent.program.portfolio.id, ownerProfileId: parent.ownerProfileId, key, name } })).workOps.programs.create;
  const program = (await createProgram('ENVS', 'Atlas deployment program')).id, archived = await createProgram('EOLD', 'Archived deployment program');
  const readerGroupId = process.env.ATLAS_WORKOPS_TEST_READER_GROUP;
  const deploymentGroup = (await gql('mutation{security{groups{addGroup(name:"Atlas deployment approvers",description:"Isolated environment grant fixture",groupType:SYSTEM){id name}}}}')).security.groups.addGroup;
  await gql('mutation($id:UUID!,$version:Long!){workOps{programs{archive(id:$id,expectedVersion:$version){id}}}}', archived);
  await page.goto(base + hub(program)); await page.getByText('No environments in this program yet.', { exact: true }).waitFor();
  const open = async name => {
    await page.getByRole('button', { name: 'New environment', exact: true }).click(); const panel = dialog('New environment'); await panel.waitFor();
    assert.deepEqual(await panel.locator('.form-actions[data-bordered]').getByRole('button').allTextContents(), ['Cancel', 'Save environment']);
    await panel.locator('input[name=name]').fill(name); await choose(panel, 'Environment type', type.name); return panel;
  };
  assert.equal(await page.getByRole('button', { name: 'Choose program', exact: true }).count(), 0, 'Selecting a program needs no separate apply action');
  await choose(page, 'Program', 'EOLD — Archived deployment program');
  await page.waitForURL(url => url.searchParams.get('programId') === archived.id);
  let panel = await open('Archived program environment'); const archivedEnvironment = await submit(panel); assert.ok((await read(archived.id)).some(value => value.id === archivedEnvironment));
  await choose(page, 'Program', 'ENVS — Atlas deployment program');
  await page.waitForURL(url => url.searchParams.get('programId') === program);
  panel = await open('Development initial'); assert.equal(await panel.locator('input[name=key]').inputValue(), 'development-initial');
  await panel.locator('textarea[name=description]').fill('Development source'); assert.equal(await panel.getByRole('button', { name: /Retry loading|Reload/ }).count(), 0);
  await panel.locator('input[name=name]').fill('Development updated'); assert.equal(await panel.locator('input[name=key]').inputValue(), 'development-updated');
  const development = await submit(panel); let stored = (await read(program)).find(value => value.id === development);
  assert.equal(stored.key, 'development-updated'); assert.equal(stored.description, 'Development source'); assert.equal(stored.displayOrder, 0);
  panel = await open('Approval stage'); await panel.locator('input[name=key]').fill('staging-locked'); await panel.locator('input[name=name]').fill('Manual staging name');
  assert.equal(await panel.locator('input[name=key]').inputValue(), 'staging-locked'); assert.equal(await panel.getByRole('button', { name: /Retry loading|Reload/ }).count(), 0);
  await panel.locator('input[name=name]').fill('Staging approval'); assert.equal(await panel.locator('input[name=key]').inputValue(), 'staging-locked');
  const staging = await submit(panel);
  const alternateSource = await createApi({ programId: program, key: 'alternate', name: 'Alternate promotion source', typeId: type.id, displayOrder: 1 });
  stored = (await read(program)).find(value => value.id === staging);
  await updateApi(stored, { name: stored.name, description: 'Pipeline-owned policy', displayOrder: 12, typeId: type.id, promotionSourceIds: [development, alternateSource], requiresApproval: true, autoPromote: false });
  await page.goto(base + hub(program)); await row(staging).getByText('Approval required', { exact: true }).waitFor();
  await row(staging).getByRole('button', { name: 'Edit Staging approval', exact: true }).click(); panel = dialog('Edit environment'); await panel.waitFor();
  assert.equal(await panel.locator('input[name=key]').getAttribute('type'), 'hidden'); assert.equal(await panel.locator('input[name=key]').inputValue(), 'staging-locked');
  await panel.getByText(/Promotes from: .*Development updated/).waitFor(); await panel.getByText('Requires approval: Yes', { exact: true }).waitFor();
  await panel.locator('input[name=name]').fill('Unsent environment'); await panel.locator('textarea[name=description]').fill('Unsent environment notes'); await choose(panel, 'Environment type', 'Atlas replacement stage');
  await panel.getByRole('checkbox', { name: 'Auto-promote from its sources', exact: true }).check();
  const groupId = deploymentGroup.id;
  const groupName = (await gql('{security{groups{all(offset:0,limit:100){id name}}}}')).security.groups.all.find(value => value.id === groupId).name;
  await choose(panel, 'Security group', groupName); await panel.getByRole('button', { name: 'Grant execute', exact: true }).click();
  await panel.locator(`[data-workops-environment-grant="${groupId}:EXECUTE"]`).waitFor();
  assert.equal(await panel.locator('input[name=name]').inputValue(), 'Unsent environment'); assert.equal(await panel.locator('textarea[name=description]').inputValue(), 'Unsent environment notes');
  assert.equal(await panel.locator('select[name=typeId]').inputValue(), alternateType); assert.equal(await panel.getByRole('checkbox', { name: 'Auto-promote from its sources', exact: true }).isChecked(), true);
  stored = (await read(program)).find(value => value.id === staging); assert.equal(stored.name, 'Staging approval'); assert.equal(stored.typeId, type.id); assert.equal(stored.autoPromote, false);
  assert.deepEqual(stored.permissions, [{ groupId, action: 'EXECUTE' }]);
  await updateApi(stored, { name: 'Externally changed environment', description: 'Concurrent policy', typeId: type.id, promotionSourceIds: [alternateSource], requiresApproval: false, autoPromote: false });
  await reject(panel, 'Save environment', true);
  await panel.getByRole('button', { name: 'Retry loading environment data', exact: true }).click(); await panel.page().waitForFunction(() => !document.querySelector('[data-bml-action-pending]'));
  await page.waitForFunction(() => document.querySelector('dialog[open] input[name=name]')?.getAttribute('value') === 'Unsent environment');
  await panel.getByText('Requires approval: Yes', { exact: true }).waitFor(); await reject(panel, 'Save environment', true);
  assert.equal(await panel.locator('select[name=typeId]').inputValue(), alternateType); assert.equal(await panel.locator('textarea[name=description]').inputValue(), 'Unsent environment notes');
  stored = (await read(program)).find(value => value.id === staging); assert.equal(stored.name, 'Externally changed environment'); assert.equal(stored.requiresApproval, false); assert.deepEqual(stored.promotionSourceIds, [alternateSource]);
  await cancel(panel); await page.getByRole('button', { name: 'Refresh environments', exact: true }).click();
  await page.waitForFunction(() => !document.querySelector('[data-bml-action-pending]'));
  await row(staging).getByRole('button', { name: 'Edit Externally changed environment', exact: true }).click(); await panel.waitFor();
  await panel.locator('input[name=name]').fill('Saved program environment'); await panel.locator('textarea[name=description]').fill('Saved environment notes'); await choose(panel, 'Environment type', 'Atlas replacement stage');
  await panel.getByRole('checkbox', { name: 'Auto-promote from its sources', exact: true }).check(); await submit(panel);
  stored = (await read(program)).find(value => value.id === staging); assert.equal(stored.key, 'staging-locked'); assert.equal(stored.typeId, alternateType); assert.equal(stored.autoPromote, true); assert.equal(stored.requiresApproval, false); assert.deepEqual(stored.promotionSourceIds, [alternateSource]); assert.equal(stored.displayOrder, 12);

  await page.goto(base + type.path); await type.row(type.id).getByRole('button', { name: `Delete ${type.name}`, exact: true }).click(); panel = dialog('Delete environment type'); await panel.waitFor();
  await panel.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await reject(panel, 'Delete environment type'); await panel.getByRole('alert').filter({ hasText: /used by .*environment/ }).waitFor(); assert.ok((await type.read()).some(value => value.id === type.id)); await cancel(panel);

  for (const action of ['VIEW', 'MANAGE', 'EXECUTE']) await permission(program, action, true);
  const disposable = await createApi({ programId: program, key: 'disposable', name: 'Disposable program environment', typeId: alternateType });
  const readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(); observe(reader);
  try {
    await login(reader, hub(program), true); await row(staging, reader).getByRole('button', { name: 'Edit Saved program environment', exact: true }).click(); let editing = dialog('Edit environment', reader); await editing.waitFor();
    await editing.locator('[data-workops-environment-group-error]').waitFor(); assert.equal(await editing.locator('select[name=groupId]').isDisabled(), true);
    await editing.locator('input[name=name]').fill('Reader environment draft'); await editing.locator('textarea[name=description]').fill('Retained reader notes');
    await permission(program, 'MANAGE', false); await reject(editing, 'Save environment', true); assert.equal(await editing.locator('input[name=name]').inputValue(), 'Reader environment draft');
    await permission(program, 'MANAGE', true); await editing.getByRole('button', { name: 'Save environment', exact: true }).click(); await editing.waitFor({ state: 'hidden' });
    stored = (await read(program)).find(value => value.id === staging); assert.equal(stored.name, 'Reader environment draft'); assert.equal(stored.description, 'Retained reader notes');
    await row(staging, reader).getByRole('button', { name: 'Edit Reader environment draft', exact: true }).click(); await editing.waitFor(); await editing.locator('[data-workops-environment-group-error]').waitFor();
    await editing.locator('input[name=name]').fill('Unsent grant-removal draft');
    await editing.locator(`[data-workops-environment-grant="${groupId}:EXECUTE"]`).getByRole('button', { name: 'Remove Group unavailable EXECUTE', exact: true }).click();
    await editing.getByText('No explicit grants are visible. Program grants remain inherited.', { exact: true }).waitFor();
    assert.equal(await editing.locator('input[name=name]').inputValue(), 'Unsent grant-removal draft'); assert.equal((await read(program)).find(value => value.id === staging).name, 'Reader environment draft');
    const inherited = (await gql('query($id:UUID!){workOps{programs{program(id:$id){permissions{groupId action}}}}}', { id: program })).workOps.programs.program.permissions;
    assert.ok(inherited.some(value => value.groupId === readerGroupId && value.action === 'EXECUTE')); assert.ok(inherited.some(value => value.groupId === readerGroupId && value.action === 'MANAGE')); await cancel(editing);
    await row(disposable, reader).getByRole('button', { name: 'Delete Disposable program environment', exact: true }).click(); let deleting = dialog('Delete environment', reader); await deleting.waitFor(); await cancel(deleting);
    assert.ok((await read(program)).some(value => value.id === disposable));
    await row(disposable, reader).getByRole('button', { name: 'Delete Disposable program environment', exact: true }).click(); await deleting.waitFor(); await deleting.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check();
    await permission(program, 'MANAGE', false); await reject(deleting, 'Delete environment'); assert.ok((await read(program)).some(value => value.id === disposable));
    await permission(program, 'MANAGE', true); await deleting.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await deleting.getByRole('button', { name: 'Delete environment', exact: true }).click(); await deleting.waitFor({ state: 'hidden' }); assert.equal((await read(program)).some(value => value.id === disposable), false);
    await reader.goto(base + hub(archived.id)); await reader.getByText('Choose an available program.', { exact: true }).waitFor(); assert.equal(await reader.getByText('Archived deployment program', { exact: true }).count(), 0);
    await reader.goto(base + type.path); await reader.getByRole('button', { name: 'New environment type', exact: true }).click(); editing = dialog('New environment type', reader); await editing.waitFor();
    await editing.locator('input[name=name]').fill('Reader cannot change the global catalog'); await editing.locator('input[name=order]').fill('7'); await editing.locator('textarea[name=description]').fill('Retained global type draft');
    await reject(editing, 'Save environment type'); assert.equal(await editing.locator('textarea[name=description]').inputValue(), 'Retained global type draft'); assert.equal(await editing.locator('input[name=order]').inputValue(), '7'); await cancel(editing);
  } catch (error) {
    await reader.screenshot({ path: resolve(reports, 'reader-failure.png'), fullPage: true }).catch(() => {});
    console.error('Reader environment state', JSON.stringify(await reader.evaluate(() => ({ path: location.pathname + location.search, dialogs: [...document.querySelectorAll('dialog[open]')].map(node => node.innerText.slice(0, 2200)) })).catch(String)));
    throw error;
  } finally { await readerContext.close(); for (const action of ['VIEW', 'MANAGE', 'EXECUTE']) await permission(program, action, false); }

  for (let index = 0; index < 27; index++) {
    await createApi({ programId: program, key: `page-${index}`, name: `Paged environment ${index}`, typeId: alternateType, displayOrder: 20 + index });
    await gql('mutation($name:String!,$order:Int!){workOps{multiRepo{createEnvironmentType(name:$name,displayOrder:$order){id}}}}', { name: `Paged environment type ${index}`, order: 50 + index });
  }
  await page.goto(base + hub(program)); assert.equal(await page.locator('[data-workops-environment]').count(), 25);
  await page.getByRole('navigation', { name: 'Environment pages', exact: true }).getByRole('link', { name: 'Next', exact: true }).click(); await page.waitForURL(url => url.searchParams.get('offset') === '25' && url.searchParams.get('programId') === program);
  panel = await open('Created on second environment page'); const result = await submit(panel); await page.screenshot({ path: resolve(reports, 'saved-environment-secondary-actions.png') }); await page.getByRole('link', { name: 'Show the saved environment', exact: true }).click(); await row(result).waitFor(); assert.equal(new URL(page.url()).searchParams.get('programId'), program);
  await page.goto(base + type.path); assert.equal(await page.locator('[data-workops-environment-type]').count(), 25);
  await page.getByRole('navigation', { name: 'Environment type pages', exact: true }).getByRole('link', { name: 'Next', exact: true }).click(); await page.waitForURL(url => url.searchParams.get('offset') === '25');
  panel = await type.open('Created on second type page', 'Result link', 99); const typeResult = await type.submit(panel);
  await page.getByRole('link', { name: 'Show the saved environment type', exact: true }).click(); await type.row(typeResult).waitFor();
  const before = (await read(program)).length, typeCount = (await type.read()).length;
  await environmentLayouts({ page, context, base, hub: hub(program), environment: staging, environmentName: 'Reader environment draft', type, types: type, dialog, choose, reports });
  assert.equal((await read(program)).length, before); assert.equal((await type.read()).length, typeCount); assert.deepEqual(errors, []);
  console.log('Atlas environments/types: captured policy, immutable keys, shared slug input, admin/program/grant boundaries, denied/stale retries, paging and layouts passed.');
} catch (error) {
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error('Environment browser state', JSON.stringify(await page.evaluate(() => ({ path: location.pathname + location.search, dialogs: [...document.querySelectorAll('dialog[open]')].map(node => node.innerText.slice(0, 1800)), alerts: [...document.querySelectorAll('p[role=alert]')].map(node => node.innerText) })).catch(String)));
  throw error;
} finally { await context.close(); await owned.close(); }
