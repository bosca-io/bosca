import { compactResult } from './presentation-checks.mjs';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/milestones-browser', new URL(base).port); await mkdir(reports, { recursive: true });
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
const dialog = (name, tab = page) => tab.getByRole('dialog', { name, exact: true });
const permission = async (id, action, add = true) => gql(`mutation($id:UUID!,$group:UUID!){workOps{programs{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:${action})}}}`, { id, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
try {
  await login(page, '/workops/milestones');
  const source = (await gql('query($id:UUID!){workOps{projects{project(id:$id){ownerProfileId program{portfolio{id}}}}}}', { id: fixtureProject })).workOps.projects.project;
  const program = async (key, name) => (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id}}}}', {
    input: { portfolioId: source.program.portfolio.id, ownerProfileId: source.ownerProfileId, key, name },
  })).workOps.programs.create.id;
  const primary = await program('MSTONE', 'Milestone browser program'), alternate = await program('MSALT', 'Alternate milestone program');
  for (const action of ['VIEW', 'MANAGE']) await permission(primary, action);
  const seed = async (programId, name, date = null) => (await gql('mutation($input:CreateWorkOpsMilestoneInput!){workOps{milestones{create(input:$input){id version}}}}', {
    input: { programId, name, description: 'Seeded through the existing milestone API.', targetDate: date },
  })).workOps.milestones.create;
  await seed(alternate, 'Private milestone commitment');
  for (let index = 0; index < 26; index++) await seed(primary, `Milestone page ${String(index).padStart(2, '0')}`, index === 0 ? '2020-01-01T00:00:00Z' : null);
  const href = `/workops/milestones?programId=${primary}`;
  await page.goto(base + href); const section = page.locator('[data-workops-milestones]'); await section.waitFor();
  assert.equal(await section.locator('tbody tr').count(), 25);
  assert.equal(await section.locator('table').evaluate(node => !!node.closest('.ui-card')), true);
  assert.equal(await section.locator('.workops-content > p').count(), 0, 'Context and result navigation do not create standalone rows');
  const closeAction = section.locator('tbody tr').first().getByRole('button', { name: /^Close / });
  assert.equal((await closeAction.innerText()).trim(), '');
  assert.equal(await closeAction.locator('svg').count(), 1);
  await section.getByText(/days overdue/).waitFor();
  await section.getByRole('button', { name: 'New milestone', exact: true }).click(); const creating = dialog('New milestone'); await creating.waitFor();
  await creating.locator('input[name=name]').fill('Z Milestone created in Atlas');
  await creating.locator('textarea[name=description]').fill('A dated cross-project commitment.');
  await creating.locator('input[name=targetDate]').fill('2027-02-03');
  await creating.getByRole('button', { name: 'Create milestone', exact: true }).click(); await creating.waitFor({ state: 'hidden' });
  const result = section.getByRole('link', { name: 'Show created milestone', exact: true }); await result.waitFor(); await compactResult(result);
  assert.equal(new URL(await result.getAttribute('href'), base).searchParams.get('offset'), '25');
  await result.click(); await page.waitForURL(value => value.searchParams.get('offset') === '25');
  const row = section.locator('tbody tr').filter({ hasText: 'Z Milestone created in Atlas' }); await row.waitFor();
  const id = (await row.getAttribute('id')).replace('workops-milestone-', '');
  const milestones = async () => (await gql('query($id:UUID!){workOps{milestones{byProgram(programId:$id){id name description targetDate state version}}}}', { id: primary })).workOps.milestones.byProgram;
  const created = (await milestones()).find(value => value.id === id);
  assert.equal(created.description, 'A dated cross-project commitment.'); assert.equal(new Date(created.targetDate).toISOString(), '2027-02-03T00:00:00.000Z');
  await row.getByRole('button', { name: 'Close Z Milestone created in Atlas', exact: true }).click(); const closing = dialog('Close milestone'); await closing.waitFor();
  await closing.getByRole('button', { name: 'Cancel', exact: true }).click(); await closing.waitFor({ state: 'hidden' });
  assert.equal((await milestones()).find(value => value.id === id).state, 'OPEN');
  await row.getByRole('button', { name: 'Close Z Milestone created in Atlas', exact: true }).click(); await closing.waitFor();
  await gql('mutation($id:UUID!,$version:Long!){workOps{milestones{close(id:$id,expectedVersion:$version){id}}}}', { id, version: created.version });
  await closing.getByRole('checkbox', { name: 'Confirm closing this milestone', exact: true }).check();
  await closing.getByRole('button', { name: 'Close milestone', exact: true }).click(); await closing.getByText('Internal server error', { exact: true }).waitFor();
  assert.equal(await closing.isVisible(), true);
  await closing.getByText('Close Z Milestone created in Atlas?', { exact: true }).waitFor();
  await closing.getByRole('button', { name: 'Cancel', exact: true }).click(); await closing.waitFor({ state: 'hidden' });
  await section.getByRole('button', { name: 'Refresh milestones', exact: true }).click();
  await row.getByText('Closed', { exact: true }).first().waitFor();
  await section.getByRole('button', { name: 'Close Milestone page 25', exact: true }).click(); await closing.waitFor();
  await closing.getByRole('checkbox', { name: 'Confirm closing this milestone', exact: true }).check(); await closing.getByRole('button', { name: 'Close milestone', exact: true }).click(); await closing.waitFor({ state: 'hidden' });
  assert.equal((await milestones()).find(value => value.name === 'Milestone page 25').state, 'CLOSED');
  const previous = page.getByRole('navigation', { name: 'Milestone pages', exact: true }).getByRole('link', { name: 'Previous', exact: true });
  assert.equal(new URL(await previous.getAttribute('href'), base).searchParams.get('programId'), primary);
  await previous.click(); await page.waitForURL(value => value.searchParams.get('offset') === '0');
  await page.goto(`${base}/workops/settings/milestones?programId=${primary}`); await section.waitFor();
  await section.getByRole('combobox', { name: 'Program', exact: true }).click(); await page.getByRole('option', { name: 'MSALT · Alternate milestone program', exact: true }).click();
  await page.waitForURL(value => value.pathname === '/workops/settings/milestones' && value.searchParams.get('programId') === alternate);
  await section.getByText('Private milestone commitment', { exact: true }).waitFor();

  const readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(); observe(reader);
  try {
    await login(reader, href, true); const readerSection = reader.locator('[data-workops-milestones]'); await readerSection.waitFor();
    assert.equal(await readerSection.locator('select[name=programId] option').filter({ hasText: 'Alternate milestone program' }).count(), 0);
    await readerSection.getByRole('button', { name: 'New milestone', exact: true }).click(); const readerForm = dialog('New milestone', reader); await readerForm.waitFor();
    await readerForm.locator('input[name=name]').fill('Reader commitment'); await readerForm.getByRole('button', { name: 'Create milestone', exact: true }).click(); await readerForm.waitFor({ state: 'hidden' });
    await readerSection.getByText('Milestone created.', { exact: true }).waitFor();
    assert.ok((await milestones()).some(value => value.name === 'Reader commitment'));
    await permission(primary, 'MANAGE', false);
    await readerSection.getByRole('button', { name: 'New milestone', exact: true }).click(); await readerForm.waitFor();
    await readerForm.locator('input[name=name]').fill('Retained denied milestone'); await readerForm.locator('textarea[name=description]').fill('Keep this description.');
    await readerForm.locator('input[name=targetDate]').fill('2027-03-04'); await readerForm.getByRole('button', { name: 'Create milestone', exact: true }).click();
    await readerForm.getByText('Unauthorized access', { exact: true }).waitFor();
    assert.equal(await readerForm.locator('input[name=name]').inputValue(), 'Retained denied milestone');
    assert.equal(await readerForm.locator('textarea[name=description]').inputValue(), 'Keep this description.');
    assert.equal(await readerForm.locator('input[name=targetDate]').inputValue(), '2027-03-04');
    assert.equal((await milestones()).some(value => value.name === 'Retained denied milestone'), false);
    await permission(primary, 'MANAGE'); await readerForm.getByRole('button', { name: 'Create milestone', exact: true }).click(); await readerForm.waitFor({ state: 'hidden' });
    assert.ok((await milestones()).some(value => value.name === 'Retained denied milestone'));
    await reader.goto(`${base}/workops/milestones?programId=${alternate}`);
    await readerSection.getByRole('alert').filter({ hasText: 'The selected program is unavailable. Choose another program.' }).waitFor();
    assert.equal((await reader.locator('body').innerText()).includes('Private milestone commitment'), false);
  } finally { await readerContext.close(); }

  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await page.goto(base + href); await section.waitFor(); await page.setViewportSize({ width, height: 1000 });
    await page.evaluate(value => { document.documentElement.dataset.appearance = value; document.body.dataset.theme = value; }, appearance);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({ path: resolve(reports, `${appearance}-${width}.png`) });
    await section.getByRole('button', { name: 'New milestone', exact: true }).click(); await creating.waitFor();
    await creating.locator('input[name=name]').fill('Visual draft'); await creating.locator('textarea[name=description]').fill('A retained description across a rejected action.');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await creating.screenshot({ path: resolve(reports, `create-${appearance}-${width}.png`) });
    await creating.getByRole('button', { name: 'Cancel', exact: true }).click(); await creating.waitFor({ state: 'hidden' });
  }
  assert.deepEqual(errors, []); console.log('Milestone program selection, both routes, date/create, offset pages, stale close, ordinary permissions and retained drafts passed.');
} catch (error) {
  console.error(await page.locator('body').innerText().catch(String)); console.error(errors);
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {}); throw error;
} finally { await context.close(); await owned.close(); }
