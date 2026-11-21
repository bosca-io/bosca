import { compactResult } from './presentation-checks.mjs';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && process.env.ATLAS_WORKOPS_TEST_PROJECT, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/workflows-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage(), readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(), errors = [];
for (const tab of [page, reader]) { tab.on('pageerror', error => errors.push(error.message)); tab.on('console', message => { if (message.type() === 'error') errors.push(message.text()); }); }
const gql = async (query, variables = {}, tab = page) => {
  const result = await tab.evaluate(async ({ query, variables }) => (await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) })).json(), { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const path = kind => `/workops/settings/workflows?tab=${kind}`;
const row = (kind, id, tab = page) => tab.locator(`[data-workops-${kind === 'schemes' ? 'workflow-scheme' : 'workflow'}="${id}"]`);
const dialog = (title, tab = page) => tab.getByRole('dialog', { name: title, exact: true });
const label = kind => kind === 'schemes' ? 'workflow scheme' : 'workflow';
const login = async (tab, isReader = false) => {
  await tab.goto(base + path('workflows')); await tab.locator('input[name=identifier]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER : 'admin');
  await tab.locator('input[name=password]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD : process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await tab.locator('button[type=submit]').click(); await tab.waitForURL(url => url.pathname === '/workops/settings/workflows');
};
const read = async () => (await gql('{workOps{workflows{all{id name description initialStateId version states{id displayOrder status{name category colorHex}} transitions{id name description fromStateIds toStateId conditions validators postFunctions screenId}} schemes{id name description defaultWorkflowId perTaskTypeWorkflowIds version}}}}')).workOps.workflows;
const choose = async (panel, name) => { await panel.getByRole('combobox', { name: 'Default workflow', exact: true }).click(); await panel.page().getByRole('option', { name, exact: true }).click(); };
const open = async (kind, tab = page) => { await tab.getByRole('button', { name: `New ${label(kind)}`, exact: true }).click(); const panel = dialog(`New ${label(kind)}`, tab); await panel.waitFor(); return panel; };
const edit = async (kind, id, name, tab = page) => { await row(kind, id, tab).getByRole('button', { name: `Edit ${name}`, exact: true }).click(); const panel = dialog(`Edit ${label(kind)}`, tab); await panel.waitFor(); return panel; };
const save = async (kind, panel) => {
  await panel.getByRole('button', { name: `Save ${label(kind)}`, exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  const link = panel.page().getByRole('link', { name: `Show the saved ${label(kind)}`, exact: true }); await link.waitFor(); await compactResult(link); return new URL(await link.getAttribute('href'), base).searchParams.get('item');
};
const fill = async (panel, name) => { await panel.locator('input[name=name]').fill(name); await panel.locator('textarea[name=description]').fill(' Workflow <notes> & details '); };
const cancel = async panel => { await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await panel.waitFor({ state: 'hidden' }); };
const reject = async (panel, button) => { await panel.getByRole('button', { name: button, exact: true }).click(); await panel.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); };
const external = async (kind, value, name) => gql(`mutation($id:UUID!,$input:UpdateWorkOpsWorkflow${kind === 'schemes' ? 'Scheme' : ''}Input!){workOps{workflows{${kind === 'schemes' ? 'updateScheme' : 'update'}(id:$id,input:$input){id}}}}`, { id: value.id, input: { name, description: value.description, ...(kind === 'schemes' ? { defaultWorkflowId: value.defaultWorkflowId } : {}), expectedVersion: value.version } });
const geometry = async (element, modal = false) => {
  const r = await element.evaluate(node => { const r = node.getBoundingClientRect(); return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: innerWidth, height: innerHeight, scroll: document.documentElement.scrollWidth }; });
  assert.ok(r.left >= 0 && r.right <= r.width + 1 && r.scroll <= r.width + 1, JSON.stringify(r));
  if (modal) assert.ok(r.top >= 0 && r.bottom <= r.height + 1, JSON.stringify(r));
};
let readerId, adminGroup, granted = false, originalGraph;
const permission = async add => { await gql(`mutation($group:UUID!,$principal:UUID!){security{${add ? 'add' : 'remove'}PrincipalGroup(groupId:$group,principalId:$principal)}}`, { group: adminGroup, principal: readerId }); granted = add; };
const ids = {}, names = { workflows: 'Atlas workflow', schemes: 'Atlas workflow scheme' };
try {
  await login(page); await login(reader, true);
  readerId = (await gql('{security{principals{current{id}}}}', {}, reader)).security.principals.current.id;
  adminGroup = (await gql('{security{groups{find(nameOrDescription:"administrators",offset:0,limit:100,type:SYSTEM){id name}}}}')).security.groups.find.find(value => value.name === 'administrators')?.id; assert.ok(adminGroup);
  originalGraph = (await read()).all.find(flow => flow.states.length && flow.transitions.some(transition => transition.fromStateIds.includes('*'))); assert.ok(originalGraph);
  const alternate = (await gql('mutation{workOps{workflows{create(input:{name:"Alternate workflow"}){id}}}}')).workOps.workflows.create.id;
  for (const kind of ['workflows', 'schemes']) {
    await page.goto(base + path(kind)); let panel = await open(kind); await fill(panel, ` ${names[kind]} `);
    if (kind === 'schemes') await choose(panel, names.workflows);
    ids[kind] = await save(kind, panel);
    let stored = (await read())[kind === 'schemes' ? 'schemes' : 'all'].find(value => value.id === ids[kind]);
    assert.equal(stored.name, names[kind]); assert.equal(stored.description, 'Workflow <notes> & details');
    if (kind === 'workflows') { assert.equal(stored.initialStateId, null); assert.deepEqual(stored.states, []); assert.deepEqual(stored.transitions, []); }
    else { assert.equal(stored.defaultWorkflowId, ids.workflows); assert.deepEqual(stored.perTaskTypeWorkflowIds, {}); }
    panel = await edit(kind, stored.id, stored.name); await fill(panel, `Retained ${label(kind)}`);
    if (kind === 'schemes') await choose(panel, 'Alternate workflow');
    await external(kind, stored, 'External header update');
    await reject(panel, `Save ${label(kind)}`);
    await panel.getByRole('button', { name: 'Retry loading workflows', exact: true }).click(); await panel.page().waitForFunction(() => !document.querySelector('[data-bml-action-pending]')); await page.waitForFunction(name => document.querySelector('dialog[open] input[name=name]')?.getAttribute('value') === name, `Retained ${label(kind)}`);
    await reject(panel, `Save ${label(kind)}`); assert.equal(await panel.locator('input[name=name]').inputValue(), `Retained ${label(kind)}`);
    if (kind === 'schemes') assert.equal(await panel.locator('select[name=defaultId]').inputValue(), alternate);
    assert.equal((await read())[kind === 'schemes' ? 'schemes' : 'all'].find(value => value.id === stored.id).name, 'External header update'); await cancel(panel);
    await page.getByRole('button', { name: 'Refresh workflows', exact: true }).click(); panel = await edit(kind, stored.id, 'External header update'); await fill(panel, names[kind]);
    if (kind === 'schemes') await choose(panel, names.workflows);
    await save(kind, panel);

    await reader.goto(base + path(kind)); panel = await open(kind, reader); await fill(panel, `Reader ${label(kind)}`);
    if (kind === 'schemes') await choose(panel, names.workflows);
    await reject(panel, `Save ${label(kind)}`); assert.equal(await panel.locator('input[name=name]').inputValue(), `Reader ${label(kind)}`);
    await permission(true); const disposable = await save(kind, panel); await permission(false);
    assert.equal((await read())[kind === 'schemes' ? 'schemes' : 'all'].find(value => value.id === disposable).name, `Reader ${label(kind)}`);
    await row(kind, disposable, reader).getByRole('button', { name: `Delete Reader ${label(kind)}`, exact: true }).click(); panel = dialog(`Delete ${label(kind)}`, reader); await panel.waitFor(); await cancel(panel);
    assert.ok((await read())[kind === 'schemes' ? 'schemes' : 'all'].some(value => value.id === disposable));
    await row(kind, disposable, reader).getByRole('button', { name: `Delete Reader ${label(kind)}`, exact: true }).click(); await panel.waitFor(); await panel.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await reject(panel, `Delete ${label(kind)}`);
    await permission(true); await panel.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await panel.getByRole('button', { name: `Delete ${label(kind)}`, exact: true }).click(); await panel.waitFor({ state: 'hidden' }); await permission(false);
    assert.equal((await read())[kind === 'schemes' ? 'schemes' : 'all'].some(value => value.id === disposable), false);
  }
  await page.goto(`${base}${path('schemes')}&item=${ids.schemes}`);
  let panel = await edit('schemes', ids.schemes, names.schemes); await choose(panel, 'Alternate workflow'); await save('schemes', panel);
  assert.equal((await read()).schemes.find(value => value.id === ids.schemes).defaultWorkflowId, alternate);
  panel = await edit('schemes', ids.schemes, names.schemes); await choose(panel, names.workflows); await save('schemes', panel);
  assert.equal((await read()).schemes.find(value => value.id === ids.schemes).defaultWorkflowId, ids.workflows);
  // API readback includes opaque engine configuration, so a header edit cannot silently erase it.
  await page.goto(`${base}${path('workflows')}&item=${originalGraph.id}`); panel = await edit('workflows', originalGraph.id, originalGraph.name);
  assert.equal(await panel.locator('[data-workops-workflow-graph] h3').count(), 0, 'Table captions own each graph list title');
        assert.deepEqual(await panel.locator('[data-workops-workflow-graph]').evaluate(node => [getComputedStyle(node).display, getComputedStyle(node).rowGap]), ['grid', '12px'], 'The domain container separates the two shared table cards');
        assert.equal(await panel.locator('[data-workops-workflow-graph] table').evaluateAll(tables => tables.every(table => !!table.closest('.ui-card'))), true);
        assert.equal(await panel.locator('[data-workops-workflow-state]').count(), originalGraph.states.length); assert.equal(await panel.locator('[data-workops-workflow-transition]').count(), originalGraph.transitions.length);
  await fill(panel, 'Graph header edit'); await save('workflows', panel);
  let graph = (await read()).all.find(value => value.id === originalGraph.id);
  assert.equal(graph.initialStateId, originalGraph.initialStateId); assert.deepEqual(graph.states, originalGraph.states); assert.deepEqual(graph.transitions, originalGraph.transitions);
  await gql('mutation($id:UUID!,$input:UpdateWorkOpsWorkflowInput!){workOps{workflows{update(id:$id,input:$input){id}}}}', { id: graph.id, input: { name: originalGraph.name, description: originalGraph.description, expectedVersion: graph.version } });

  await page.goto(`${base}${path('workflows')}&item=${ids.workflows}`); await row('workflows', ids.workflows).getByRole('button', { name: `Delete ${names.workflows}`, exact: true }).click(); panel = dialog('Delete workflow'); await panel.waitFor(); await panel.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await reject(panel, 'Delete workflow'); assert.ok((await read()).all.some(value => value.id === ids.workflows)); await cancel(panel);
  const referencedScheme = (await gql('query($id:UUID!){workOps{projects{project(id:$id){defaultWorkflowSchemeId}}}}', { id: process.env.ATLAS_WORKOPS_TEST_PROJECT })).workOps.projects.project.defaultWorkflowSchemeId;
  const scheme = (await read()).schemes.find(value => value.id === referencedScheme); assert.ok(scheme);
  await page.goto(`${base}${path('schemes')}&item=${scheme.id}`); await row('schemes', scheme.id).getByRole('button', { name: `Delete ${scheme.name}`, exact: true }).click(); panel = dialog('Delete workflow scheme'); await panel.waitFor(); await panel.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await reject(panel, 'Delete workflow scheme'); assert.ok((await read()).schemes.some(value => value.id === scheme.id)); await cancel(panel);

  for (const kind of ['workflows', 'schemes']) {
    for (let index = 0; index < 27; index++) await gql(`mutation($input:CreateWorkOpsWorkflow${kind === 'schemes' ? 'Scheme' : ''}Input!){workOps{workflows{${kind === 'schemes' ? 'createScheme' : 'create'}(input:$input){id}}}}`, { input: { name: `Paged ${label(kind)} ${index}`, ...(kind === 'schemes' ? { defaultWorkflowId: alternate } : {}) } });
    await page.goto(base + path(kind)); assert.equal(await page.locator(`[data-workops-${kind === 'schemes' ? 'workflow-scheme' : 'workflow'}]`).count(), 25);
    await page.getByRole('navigation', { name: `${kind === 'schemes' ? 'Workflow schemes' : 'Workflows'} pages`, exact: true }).getByRole('link', { name: 'Next', exact: true }).click(); await page.waitForURL(url => url.searchParams.get('offset') === '25');
    panel = await open(kind); await fill(panel, `Created on second ${kind} page`); const id = await save(kind, panel);
    await page.getByRole('link', { name: `Show the saved ${label(kind)}`, exact: true }).click(); await row(kind, id).waitFor(); assert.equal(new URL(page.url()).searchParams.get('tab'), kind);
  }
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 });
    for (const kind of ['workflows', 'schemes']) {
      const id = kind === 'workflows' ? originalGraph.id : ids.schemes, name = kind === 'workflows' ? originalGraph.name : names.schemes;
      await page.goto(`${base}${path(kind)}&item=${id}`); await row(kind, id).waitFor(); await geometry(page.locator('[data-workops-workflows]')); await page.screenshot({ path: resolve(reports, `${kind}-list-${appearance}-${width}.png`), fullPage: true });
      panel = await edit(kind, id, name); await panel.getByRole('button', { name: `Save ${label(kind)}`, exact: true }).scrollIntoViewIfNeeded(); await geometry(panel, true); await page.screenshot({ path: resolve(reports, `${kind}-edit-${appearance}-${width}.png`), fullPage: true }); await cancel(panel);
      if (kind === 'workflows') {
        await row(kind, id).getByRole('button', { name: `View ${name}`, exact: true }).click(); panel = dialog('Workflow detail'); await panel.waitFor(); await geometry(panel, true);
        assert.equal(await panel.locator('[data-workops-workflow-graph] h3').count(), 0, 'Table captions own each graph list title');
        assert.deepEqual(await panel.locator('[data-workops-workflow-graph]').evaluate(node => [getComputedStyle(node).display, getComputedStyle(node).rowGap]), ['grid', '12px'], 'The domain container separates the two shared table cards');
        assert.equal(await panel.locator('[data-workops-workflow-graph] table').evaluateAll(tables => tables.every(table => !!table.closest('.ui-card'))), true);
        assert.equal(await panel.locator('[data-workops-workflow-state]').count(), originalGraph.states.length); assert.equal(await panel.locator('[data-workops-workflow-transition]').count(), originalGraph.transitions.length);
        await panel.getByText('Any state', { exact: true }).waitFor(); assert.equal(await panel.getByText('Initial', { exact: true }).count(), 2);
        const ordered = [...originalGraph.states].sort((a, b) => a.displayOrder - b.displayOrder).map(value => value.id); assert.deepEqual(await panel.locator('[data-workops-workflow-state]').evaluateAll(nodes => nodes.map(node => node.dataset.workopsWorkflowState)), ordered);
        await page.screenshot({ path: resolve(reports, `workflow-detail-${appearance}-${width}.png`), fullPage: true }); await panel.getByRole('button', { name: 'Edit workflow', exact: true }).click(); panel = dialog('Edit workflow'); await panel.waitFor(); await cancel(panel);
      }
    }
  }
  assert.deepEqual(errors, []); console.log('Workflows and schemes: API CRUD/readback, empty creation, graph preservation, captured stale revisions, default selection, administrator retries, referenced-delete failures, offset results, ordered read-only states/wildcard transitions and both palettes/four widths passed.');
} catch (error) {
  for (const tab of [page, reader]) console.error('Workflow failure state', JSON.stringify(await tab.evaluate(() => ({ path: location.pathname + location.search, dialogs: [...document.querySelectorAll('dialog[open]')].map(node => node.innerText.slice(0, 2400)) })).catch(String)));
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {}); await reader.screenshot({ path: resolve(reports, 'reader-failure.png'), fullPage: true }).catch(() => {}); throw error;
} finally { if (granted) await permission(false); await readerContext.close(); await context.close(); await owned.close(); }
