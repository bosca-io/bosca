import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/planning-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage(), errors = [];
const observe = tab => { tab.on('pageerror', error => errors.push(error.message)); tab.on('console', message => { if (message.type() === 'error') errors.push(message.text()); }); }; observe(page);
const gql = async (query, variables = {}) => {
  const result = await page.evaluate(async ({ query, variables }) => {
    const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) }); return response.json();
  }, { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const login = async (tab, path, reader = false) => {
  await tab.goto(base + path); await tab.locator('input[name=identifier]').fill(reader ? process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER : 'admin');
  await tab.locator('input[name=password]').fill(reader ? process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD : process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await tab.locator('button[type=submit]').click(); await tab.waitForURL(value => value.pathname === path.split('?')[0]);
};
const path = kind => `/workops/${kind}s`, cap = kind => kind[0].toUpperCase() + kind.slice(1);
const dialog = (name, tab = page) => tab.getByRole('dialog', { name, exact: true });
const row = (id, tab = page) => tab.locator(`#workops-planning-${id}`);
const iconAction = async (item, name) => {
  const button = item.getByRole('button', { name, exact: true });
  assert.equal(await button.evaluate(node => node.classList.contains('icon-only')), true);
  assert.equal(await button.locator('.button-content').innerText(), '', 'Row actions show the icon without a text label');
  assert.equal(await button.locator('.button-icon svg').count(), 1);
  assert.equal(await button.getAttribute('data-tooltip-content'), name, 'The shared tooltip describes the affected item');
  return button;
};
const read = async (kind, id) => (await gql(`query($id:UUID!){workOps{${kind}s{${kind}(id:$id){id key name description ownerProfileId archivedAt version ${kind === 'program' ? 'portfolio{id} startDate targetDate' : kind === 'project' ? 'program{id} defaultTaskTypeSchemeId taskCreationFormSchemaKey' : ''}}}}}`, { id })).workOps[`${kind}s`][kind];
const update = async (kind, id, changes) => {
  const value = await read(kind, id), input = { key: value.key, name: value.name, description: value.description, ownerProfileId: value.ownerProfileId,
    ...(kind === 'program' ? { portfolioId: value.portfolio.id, startDate: value.startDate, targetDate: value.targetDate } : kind === 'project' ? { programId: value.program.id, defaultTaskTypeSchemeId: value.defaultTaskTypeSchemeId, taskCreationFormSchemaKey: value.taskCreationFormSchemaKey } : {}), ...changes };
  await gql(`mutation($id:UUID!,$input:WorkOps${cap(kind)}Input!,$version:Long!){workOps{${kind}s{update(id:$id,input:$input,expectedVersion:$version){id}}}}`, { id, input, version: value.version });
};
const permission = async (kind, id, action, add = true) => gql(`mutation($id:UUID!,$group:UUID!){workOps{${kind}s{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:${action})}}}`, { id, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
const chooseOwner = async (form, name, tab = page) => {
  await form.getByRole('combobox', { name: 'Owner', exact: true }).click();
  await form.getByRole('combobox', { name: 'Search owner', exact: true }).fill(name);
  await tab.getByRole('option', { name, exact: true }).click();
};
const create = async (kind, key, name, parentLabel = '') => {
  await page.goto(`${base}${path(kind)}?new=1`); const form = dialog(`New ${kind}`); await form.waitFor();
  await form.locator('input[name=key]').fill(key.toLowerCase()); await form.locator('input[name=name]').fill(name);
  await form.locator('textarea[name=description]').fill(`Description for ${name}.`); await chooseOwner(form, 'admin');
  if (parentLabel) {
    await form.getByRole('combobox', { name: kind === 'program' ? 'Portfolio' : 'Program', exact: true }).click();
    await page.getByRole('option', { name: parentLabel, exact: true }).click();
  }
  assert.equal(await form.getByRole('button', { name: /Retry loading|Reload/ }).count(), 0);
  await form.getByRole('combobox', { name: 'Owner', exact: true }).getByText('admin', { exact: true }).waitFor();
  assert.equal(await form.locator('input[name=name]').inputValue(), name);
  assert.equal(await form.locator('textarea[name=description]').inputValue(), `Description for ${name}.`);
  await form.getByRole('button', { name: `Save ${kind}`, exact: true }).click(); await form.waitFor({ state: 'hidden' });
  const item = page.locator('[data-workops-planning] tbody tr').filter({ hasText: name }); await item.waitFor();
  await iconAction(item, `Edit ${name}`); await iconAction(item, `Archive ${name}`);
  const id = (await item.getAttribute('id')).replace('workops-planning-', ''); assert.equal((await read(kind, id)).key, key); return id;
};
try {
  await login(page, '/workops/portfolios');
  const portfolio = await create('portfolio', 'PORT', 'Atlas planning portfolio');
  const program = await create('program', 'WORK', 'Atlas planning program', 'PORT · Atlas planning portfolio');
  const project = await create('project', 'MIG', 'Atlas planning project', 'WORK · Atlas planning program');
  assert.equal(await row(project).getByRole('link', { name: 'Atlas planning project', exact: true }).getAttribute('href'), `/workops/projects/${project}`);
  const owner = (await read('project', project)).ownerProfileId;
  const alternate = (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id}}}}', {
    input: { portfolioId: portfolio, key: 'OTHER', name: 'Other planning program', ownerProfileId: owner },
  })).workOps.programs.create.id;
  const task = (await gql('mutation($id:UUID!){workOps{tasks{create(input:{projectId:$id,summary:"Project move keeps this task"}){id key}}}}', { id: project })).workOps.tasks.create;
  const originalProject = await read('project', project);
  await row(project).getByRole('button', { name: 'Edit Atlas planning project', exact: true }).click(); const editing = dialog('Edit project'); await editing.waitFor();
  assert.equal(await editing.locator('input[name=key]').evaluate(input => input.readOnly), true);
  await editing.locator('input[name=name]').fill('Retained stale project draft'); await editing.locator('textarea[name=description]').fill('Retained complete description.');
  await update('project', project, { name: 'Newer project name' });
  await editing.getByRole('button', { name: 'Save project', exact: true }).click(); await editing.getByText('Internal server error', { exact: true }).waitFor();
  assert.equal(await editing.locator('input[name=name]').inputValue(), 'Retained stale project draft');
  assert.equal(await editing.locator('textarea[name=description]').inputValue(), 'Retained complete description.');
  await editing.getByRole('button', { name: 'Cancel', exact: true }).click(); await editing.waitFor({ state: 'hidden' });
  assert.equal(await page.evaluate(() => document.activeElement?.id), `workops-planning-edit-${project}`, 'Closing an edit restores focus to its icon action');
  const privateOwner = (await gql('mutation{profiles{add(linkToPrincipal:false,profile:{name:"Planning hidden owner",visibility:USER,attributes:[]}){id}}}')).profiles.add.id;
  await update('portfolio', portfolio, { ownerProfileId: privateOwner });
  await update('program', program, { ownerProfileId: privateOwner, startDate: '2026-12-01T09:30:00Z', targetDate: '2027-02-01T17:00:00Z' });
  await update('project', project, { ownerProfileId: privateOwner });
  for (const action of ['VIEW', 'EDIT']) await permission('project', project, action);
  const readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(); observe(reader);
  try {
    await login(reader, path('project'), true); await row(project, reader).waitFor();
    await row(project, reader).getByText('Program unavailable', { exact: true }).waitFor();
    assert.equal((await reader.locator('body').innerText()).includes('Planning hidden owner'), false);
    assert.equal((await reader.locator('body').innerText()).includes('Atlas planning program'), false);
    await row(project, reader).getByRole('button', { name: 'Edit Newer project name', exact: true }).click(); const readerEdit = dialog('Edit project', reader); await readerEdit.waitFor();
    assert.equal(await readerEdit.locator('select[name=ownerId]').inputValue(), privateOwner);
    await readerEdit.locator('input[name=name]').fill('Reader project name'); await readerEdit.locator('textarea[name=description]').fill('Reader retained description.');
    await permission('project', project, 'EDIT', false);
    await readerEdit.getByRole('button', { name: 'Save project', exact: true }).click(); await readerEdit.getByText('Unauthorized access', { exact: true }).waitFor();
    assert.equal(await readerEdit.locator('input[name=name]').inputValue(), 'Reader project name');
    assert.equal(await readerEdit.locator('select[name=ownerId]').inputValue(), privateOwner);
    await permission('project', project, 'EDIT'); await readerEdit.getByRole('button', { name: 'Save project', exact: true }).click(); await readerEdit.waitFor({ state: 'hidden' });
    const savedProject = await read('project', project); assert.equal(savedProject.ownerProfileId, privateOwner);
    assert.equal(savedProject.program.id, program); assert.equal(savedProject.defaultTaskTypeSchemeId, originalProject.defaultTaskTypeSchemeId);
    assert.equal(savedProject.taskCreationFormSchemaKey, originalProject.taskCreationFormSchemaKey);
    for (const action of ['VIEW', 'EDIT']) await permission('program', program, action);
    await reader.goto(base + path('program')); await row(program, reader).waitFor(); await row(program, reader).getByText('Portfolio unavailable', { exact: true }).waitFor();
    assert.equal((await reader.locator('body').innerText()).includes('Atlas planning portfolio'), false);
    await row(program, reader).getByRole('button', { name: 'Edit Atlas planning program', exact: true }).click(); const programEdit = dialog('Edit program', reader); await programEdit.waitFor();
    await programEdit.locator('input[name=name]').fill('Reader program name'); await programEdit.getByRole('button', { name: 'Save program', exact: true }).click(); await programEdit.waitFor({ state: 'hidden' });
    const savedProgram = await read('program', program); assert.equal(new Date(savedProgram.startDate).toISOString(), '2026-12-01T09:30:00.000Z');
    assert.equal(new Date(savedProgram.targetDate).toISOString(), '2027-02-01T17:00:00.000Z'); assert.equal(savedProgram.portfolio.id, portfolio);
    for (const action of ['VIEW', 'EDIT']) await permission('portfolio', portfolio, action);
    await reader.goto(base + path('portfolio')); await row(portfolio, reader).waitFor();
    await row(portfolio, reader).getByRole('button', { name: 'Edit Atlas planning portfolio', exact: true }).click(); const portfolioEdit = dialog('Edit portfolio', reader); await portfolioEdit.waitFor();
    await portfolioEdit.locator('textarea[name=description]').fill('Updated portfolio description.'); await portfolioEdit.getByRole('button', { name: 'Save portfolio', exact: true }).click(); await portfolioEdit.waitFor({ state: 'hidden' });
    assert.equal((await read('portfolio', portfolio)).ownerProfileId, privateOwner);
    assert.equal((await reader.locator('body').innerText()).includes('Planning hidden owner'), false);
    await reader.getByRole('button', { name: 'New portfolio', exact: true }).click(); const newPortfolio = dialog('New portfolio', reader); await newPortfolio.waitFor();
    await newPortfolio.locator('input[name=key]').fill('DENIED'); await newPortfolio.locator('input[name=name]').fill('Denied portfolio draft'); await chooseOwner(newPortfolio, 'WorkOps Reader', reader);
    await newPortfolio.getByRole('button', { name: 'Save portfolio', exact: true }).click(); await newPortfolio.getByText('Unauthorized access', { exact: true }).waitFor();
    assert.equal(await newPortfolio.locator('input[name=name]').inputValue(), 'Denied portfolio draft');
    await newPortfolio.getByRole('button', { name: 'Cancel', exact: true }).click(); await newPortfolio.waitFor({ state: 'hidden' });
    await permission('project', project, 'MANAGE');
    await reader.goto(base + path('project')); await row(project, reader).getByRole('button', { name: 'Move Reader project name', exact: true }).click(); const moving = dialog('Move project', reader); await moving.waitFor();
    await moving.getByRole('combobox', { name: 'Destination program', exact: true }).click(); await reader.getByRole('option', { name: 'OTHER · Other planning program', exact: true }).click();
    await moving.getByRole('button', { name: 'Move project', exact: true }).click(); await moving.getByText('Unauthorized access', { exact: true }).waitFor();
    assert.equal(await moving.locator('select[name=programId]').inputValue(), alternate);
    await permission('program', alternate, 'MANAGE'); await moving.getByRole('button', { name: 'Move project', exact: true }).click(); await moving.waitFor({ state: 'hidden' });
    assert.equal((await read('project', project)).program.id, alternate);
    const afterMove = (await gql('query($id:UUID!){workOps{tasks{task(id:$id){key project{id}}}}}', { id: task.id })).workOps.tasks.task;
    assert.equal(afterMove.key, task.key); assert.equal(afterMove.project.id, project);
    await row(project, reader).getByText('Other planning program', { exact: true }).waitFor();
  } finally { await readerContext.close(); }

  for (const [kind, id] of [['project', project], ['program', program], ['portfolio', portfolio]]) {
    const value = await read(kind, id); await page.goto(base + path(kind)); await row(id).waitFor();
    await row(id).getByRole('button', { name: `Archive ${value.name}`, exact: true }).click(); const archiving = dialog(`Archive ${kind}`); await archiving.waitFor();
    await archiving.getByRole('button', { name: 'Cancel', exact: true }).click(); await archiving.waitFor({ state: 'hidden' }); assert.equal((await read(kind, id)).archivedAt, null);
    await row(id).getByRole('button', { name: `Archive ${value.name}`, exact: true }).click(); await archiving.waitFor();
    await archiving.getByRole('checkbox', { name: 'Confirm this change', exact: true }).check(); await archiving.getByRole('button', { name: `Archive ${kind}`, exact: true }).click(); await archiving.waitFor({ state: 'hidden' });
    assert.ok((await read(kind, id)).archivedAt); assert.equal(await row(id).count(), 0);
    await page.getByRole('navigation', { name: `${cap(kind)}s archive views`, exact: true }).getByRole('link', { name: 'Archived', exact: true }).click(); await row(id).waitFor();
    await iconAction(row(id), `Unarchive ${value.name}`);
    await row(id).getByRole('button', { name: `Unarchive ${value.name}`, exact: true }).click(); const unarchiving = dialog(`Unarchive ${kind}`); await unarchiving.waitFor();
    await unarchiving.getByRole('checkbox', { name: 'Confirm this change', exact: true }).check(); await unarchiving.getByRole('button', { name: `Unarchive ${kind}`, exact: true }).click(); await unarchiving.waitFor({ state: 'hidden' });
    assert.equal((await read(kind, id)).archivedAt, null);
  }
  for (let index = 0; index < 26; index++) {
    const suffix = String(index).padStart(2, '0');
    for (const [kind, input] of [
      ['portfolio', { key: `PG${suffix}`, name: `Portfolio page ${suffix}`, ownerProfileId: owner }],
      ['program', { portfolioId: portfolio, key: `Y${suffix}`, name: `Program page ${suffix}`, ownerProfileId: owner }],
      ['project', { programId: program, key: `PGL${suffix}`, name: `Project page ${suffix}`, ownerProfileId: owner }],
    ]) await gql(`mutation($input:WorkOps${cap(kind)}Input!){workOps{${kind}s{create(input:$input){id}}}}`, { input });
  }
  for (const kind of ['portfolio', 'program', 'project']) {
    await page.goto(`${base}${path(kind)}?tab=all`); const section = page.locator('[data-workops-planning]'); await section.waitFor();
    assert.equal(await section.locator('tbody tr').count(), 25);
    await page.getByRole('navigation', { name: `${cap(kind)}s pages`, exact: true }).getByRole('link', { name: 'Next', exact: true }).click(); await page.waitForURL(value => value.searchParams.get('offset') === '25');
    assert.ok(await section.locator('tbody tr').count());
    assert.equal(new URL(await page.getByRole('navigation', { name: `${cap(kind)}s pages`, exact: true }).getByRole('link', { name: 'Previous', exact: true }).getAttribute('href'), base).searchParams.get('tab'), 'all');
    for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
      await page.waitForFunction(() => !document.querySelector('[data-bml-action-pending]'));
      await page.setViewportSize({ width, height: 1000 }); await page.evaluate(value => { document.documentElement.dataset.appearance = value; document.body.dataset.theme = value; }, appearance);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      const firstRow = section.locator('tbody tr').first(), editAction = firstRow.getByRole('button', { name: /^Edit / });
      const editName = await editAction.getAttribute('aria-label');
      await iconAction(firstRow, editName);
      await editAction.scrollIntoViewIfNeeded(); await page.mouse.move(0, 0);
      await editAction.hover(); await page.getByRole('tooltip', { name: editName, exact: true }).waitFor();
      await page.screenshot({ path: resolve(reports, `${kind}-${appearance}-${width}.png`) });
      await section.getByRole('button', { name: `New ${kind}`, exact: true }).click(); const form = dialog(`New ${kind}`); await form.waitFor();
      await form.locator('input[name=name]').fill('Visual draft'); await form.locator('textarea[name=description]').fill('A retained planning description.');
      await form.screenshot({ path: resolve(reports, `${kind}-create-${appearance}-${width}.png`) });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      await form.getByRole('button', { name: 'Cancel', exact: true }).click(); await form.waitFor({ state: 'hidden' });
      await page.waitForFunction(() => !document.querySelector('[data-bml-action-pending]'));
    }
  }
  assert.deepEqual(errors, []); console.log('Hierarchy create/edit, immutable keys/parents, private identities, preserved settings/dates, denied/stale drafts, project move, archive views and offset pages passed.');
} catch (error) {
  console.error(await page.locator('body').innerText().catch(String)); console.error(errors);
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {}); throw error;
} finally { await context.close(); await owned.close(); }
