import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL;
assert.ok(base && ['localhost', '127.0.0.1', '[::1]'].includes(new URL(base).hostname), 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/task-search-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage(), errors = [];
const observe = tab => { tab.on('pageerror', error => errors.push(error.message)); tab.on('console', message => { if (message.type() === 'error') errors.push(message.text()); }); };
observe(page);
const source = 'ORDER BY modified DESC';
const url = value => `${base}/workops/tasks?bql=${encodeURIComponent(value)}`;
const query = page.locator('.workops-task-search'), input = query.locator('.cm-content'), status = query.locator('[data-bml-ref=validation]');
const validate = async value => {
  const response = page.waitForResponse(response => response.url().includes('/_bml/contract/WorkOpsBql/validate') && response.request().postData() === JSON.stringify([value]));
  await input.fill(value); assert.equal((await response).status(), 200);
};
const gql = async (query, variables = {}) => {
  const result = await page.evaluate(async ({ query, variables }) => {
    const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) }); return response.json();
  }, { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
try {
  await page.goto(url(source)); await page.locator('input[name=identifier]').fill('admin');
  await page.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_PASSWORD); await page.locator('button[type=submit]').click();
  await page.locator('[data-workops-task-row]').first().waitFor(); await input.waitFor();
  await validate('status ='); await status.waitFor(); assert.match(await status.innerText(), /query range/);
  assert.equal(await input.getAttribute('aria-invalid'), 'true'); const before = page.url();
  await query.getByRole('button', { name: 'Search', exact: true }).click(); assert.equal(page.url(), before);
  assert.equal(await input.evaluate(element => element === document.activeElement), true);
  await validate(source); await status.waitFor({ state: 'hidden' });
  const rows = page.locator('[data-workops-task-row]'), links = page.locator('[data-workops-task-link]');
  await query.getByRole('button', { name: 'Search', exact: true }).focus(); await page.keyboard.press('j');
  assert.equal(await links.first().evaluate(element => element === document.activeElement), true);
  await page.keyboard.press('k'); assert.equal(await links.first().evaluate(element => element === document.activeElement), true);
  await page.keyboard.press('j'); assert.equal(await links.nth(1).evaluate(element => element === document.activeElement), true);
  const selected = await page.locator('[data-workops-task-row][data-selected] [data-workops-task-link]').getAttribute('href');
  await page.getByRole('button', { name: 'New task', exact: true }).focus(); await page.keyboard.press('Enter');
  const creating = page.getByRole('dialog', { name: 'New task', exact: true }); await creating.waitFor(); await creating.getByRole('button', { name: 'Cancel', exact: true }).focus();
  await page.keyboard.press('j'); assert.equal(await page.locator('[data-workops-task-row][data-selected] [data-workops-task-link]').getAttribute('href'), selected);
  await creating.getByRole('button', { name: 'Cancel', exact: true }).click(); await creating.waitFor({ state: 'hidden' });
  await input.fill(''); await input.press('j'); await input.press('k'); assert.equal(await input.innerText(), 'jk');
  assert.equal(await page.locator('[data-workops-task-row][data-selected] [data-workops-task-link]').getAttribute('href'), selected);
  await validate(source);
  await page.locator('[data-workops-task-row][data-selected] [data-workops-task-link]').focus(); await page.keyboard.press('Enter');
  await page.waitForURL(value => value.pathname === new URL(selected, base).pathname);
  await page.goto(url(source)); await input.waitFor(); await query.getByRole('button', { name: 'Search', exact: true }).focus(); await page.keyboard.press('/');
  assert.equal(await input.evaluate(element => element === document.activeElement), true);
  await validate('ORDER BY created ASC'); await input.press('ControlOrMeta+Enter');
  await page.waitForURL(value => value.searchParams.get('bql') === 'ORDER BY created ASC');
  assert.equal(await page.evaluate(() => localStorage.getItem('workops-tasks-bql')), 'ORDER BY created ASC');
  await query.getByRole('button', { name: 'Clear query', exact: true }).click(); await page.waitForURL(value => value.searchParams.get('bql') === '');
  assert.equal(await query.locator('textarea[name=bql]').inputValue(), '');
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 900 });
    await page.goto(url(source)); await input.waitFor(); await validate('status ='); await status.waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({ path: resolve(reports, `search-${appearance}-${width}.png`), fullPage: true });
  }
  await page.setViewportSize({ width: 1280, height: 900 });
  const profile = (await gql('mutation{profiles{add(linkToPrincipal:false,profile:{name:"Private assignee identity",visibility:USER,attributes:[]}){id}}}')).profiles.add;
  const task = (await gql('mutation($project:UUID!,$assignee:UUID!){workOps{tasks{create(input:{projectId:$project,summary:"Private assignee fixture",assigneeProfileId:$assignee}){id key}}}}', {
    project: process.env.ATLAS_WORKOPS_TEST_PROJECT, assignee: profile.id,
  })).workOps.tasks.create;
  for (const action of ['VIEW', 'EDIT']) await gql(`mutation($id:UUID!,$group:UUID!){workOps{tasks{addPermission(id:$id,groupId:$group,action:${action})}}}`, { id: task.id, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
  const restricted = await owned.connection.newContext(), reader = await restricted.newPage(); observe(reader);
  try {
    await reader.goto(url(source)); await reader.locator('input[name=identifier]').fill(process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER);
    await reader.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD); await reader.locator('button[type=submit]').click();
    const row = reader.locator('[data-workops-task-row]').filter({ hasText: 'Private assignee fixture' }); await row.getByText('Profile unavailable', { exact: true }).waitFor();
    assert.equal(await row.getByText('Private assignee identity', { exact: true }).count(), 0);
    await row.getByRole('link', { name: 'Private assignee fixture', exact: true }).click();
    const assignee = reader.locator('.workops-task-facts dt').filter({ hasText: /^Assignee$/ }).locator('xpath=following-sibling::dd[1]');
    await assignee.getByText('Profile unavailable', { exact: true }).waitFor();
    await reader.getByRole('button', { name: 'Edit task', exact: true }).click(); const dialog = reader.getByRole('dialog', { name: 'Edit task', exact: true }); await dialog.waitFor();
    assert.equal(await dialog.locator('select[name=assigneeId]').inputValue(), profile.id);
    await dialog.locator('input[name=summary]').fill('Private assignee retained'); await dialog.getByRole('button', { name: 'Save task', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' }); await reader.getByText('Task saved.', { exact: true }).waitFor();
    assert.equal((await gql('query($id:UUID!){workOps{tasks{task(id:$id){assigneeProfileId}}}}', { id: task.id })).workOps.tasks.task.assigneeProfileId, profile.id);
  } catch (error) {
    console.error('Restricted task page:', await reader.locator('body').innerText().catch(() => 'Page unavailable'));
    throw error;
  } finally { await restricted.close(); }
  assert.deepEqual(errors, []); console.log('WorkOps live BQL, task shortcuts and private-assignee retention passed.');
} catch (error) {
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error(await page.locator('body').innerText().catch(() => 'Page unavailable')); console.error(errors); throw error;
} finally { await owned.close(); }
