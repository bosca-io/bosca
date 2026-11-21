import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, project = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && project, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/task-labels-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage(), errors = [];
page.on('pageerror', error => errors.push(error.message)); page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
const gql = async (query, variables = {}) => {
  const result = await page.evaluate(async ({ query, variables }) => (await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) })).json(), { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const task = id => gql('query($id:UUID!){workOps{tasks{task(id:$id){id key summary descriptionMarkdown dueDate version}}}}', { id }).then(data => data.workOps.tasks.task);
const setSummary = async (id, summary, descriptionMarkdown) => {
  const current = await task(id);
  await gql('mutation($id:UUID!,$input:UpdateWorkOpsTaskInput!){workOps{tasks{update(id:$id,input:$input){id}}}}', { id, input: { summary, descriptionMarkdown, expectedVersion: current.version } });
};
const edit = async () => { await page.getByRole('button', { name: 'Edit task', exact: true }).click(); const form = page.getByRole('dialog', { name: 'Edit task', exact: true }); await form.waitFor(); return form; };
try {
  await page.goto(base + '/workops/tasks'); await page.locator('input[name=identifier]').fill('admin'); await page.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_PASSWORD); await page.locator('button[type=submit]').click(); await page.waitForURL(url => url.pathname === '/workops/tasks');
  const parent = (await gql('mutation($project:UUID!){workOps{tasks{create(input:{projectId:$project,summary:"Task label acceptance parent"}){id}}}}', { project })).workOps.tasks.create;
  const requirement = (await gql('mutation($parent:UUID!){workOps{requirements{create(input:{parentType:TASK,parentId:$parent,name:"Authored requirement heading"}){id key metadataId task{id}}}}}', { parent: parent.id })).workOps.requirements.create;
  const id = requirement.task.id, legacy = `REQ ${requirement.key}`, description = `An authored note referring to ${requirement.key}.`;
  await setSummary(id, legacy, description);
  const body = async () => (await gql('query($id:UUID!){content{metadata(id:$id){document{title content}}}}', { id: requirement.metadataId })).content.metadata.document;
  const original = await body();
  const linkType = (await gql('{workOps{links{linkTypes{id}}}}')).workOps.links.linkTypes[0]; assert.ok(linkType);
  await gql('mutation($input:WorkOpsTaskLinkInput!){workOps{links{link(input:$input){id}}}}', { input: { sourceTaskId: parent.id, targetTaskId: id, linkTypeId: linkType.id } });
  await page.goto(`${base}/workops/tasks/${id}`); await page.getByRole('heading', { name: 'Requirement', exact: true }).waitFor();
  let form = await edit(); assert.equal(await form.locator('input[name=summary]').inputValue(), 'Requirement');
  await form.locator('input[name=dueDate]').fill('2026-12-14'); await form.getByRole('button', { name: 'Save task', exact: true }).click(); await form.waitFor({ state: 'hidden' });
  let saved = await task(id); assert.equal(saved.summary, legacy); assert.equal(saved.descriptionMarkdown, description); assert.match(saved.dueDate, /^2026-12-14/); assert.deepEqual(await body(), original);
  form = await edit(); await form.locator('input[name=summary]').fill('Explicitly renamed requirement task'); await form.getByRole('button', { name: 'Save task', exact: true }).click(); await form.waitFor({ state: 'hidden' });
  assert.equal((await task(id)).summary, 'Explicitly renamed requirement task'); assert.deepEqual(await body(), original); await setSummary(id, legacy, description);
  const literal = (await gql('mutation($project:UUID!){workOps{tasks{create(input:{projectId:$project,summary:"Task"}){id}}}}', { project })).workOps.tasks.create;
  await page.goto(`${base}/workops/tasks/${literal.id}`); await page.getByRole('heading', { name: 'Task', exact: true }).waitFor(); form = await edit(); assert.equal(await form.locator('input[name=summary]').inputValue(), 'Task'); await form.getByRole('button', { name: 'Save task', exact: true }).click(); await form.waitFor({ state: 'hidden' }); assert.equal((await task(literal.id)).summary, 'Task');
  await page.goto(`${base}/workops/requirements/${requirement.id}`); await page.locator('[data-workops-document] .ProseMirror > h1').filter({ hasText: 'Authored requirement heading' }).waitFor(); assert.deepEqual(await body(), original);
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 });
    await page.goto(base + '/workops/tasks?bql=' + encodeURIComponent('ORDER BY modified DESC'));
    const row = page.locator('[data-workops-task-row]').filter({ has: page.locator(`[data-workops-task-link][href="/workops/tasks/${id}"]`) });
    await row.waitFor(); assert.equal(await row.locator('td').nth(1).innerText(), 'Requirement'); await row.scrollIntoViewIfNeeded(); await page.screenshot({ path: resolve(reports, `search-${appearance}-${width}.png`), fullPage: true });
    await page.goto(`${base}/workops/tasks/${parent.id}`); const link = page.locator('.workops-task-relations').getByRole('link', { name: `${saved.key} · Requirement`, exact: true }); await link.waitFor(); assert.ok(!(await link.innerText()).includes(requirement.key)); await link.scrollIntoViewIfNeeded(); await page.screenshot({ path: resolve(reports, `linked-${appearance}-${width}.png`), fullPage: true });
    await page.goto(`${base}/workops/tasks/${id}`); await page.getByRole('heading', { name: 'Requirement', exact: true }).waitFor(); form = await edit(); assert.equal(await form.locator('input[name=summary]').inputValue(), 'Requirement'); assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)); await page.screenshot({ path: resolve(reports, `edit-${appearance}-${width}.png`), fullPage: true }); await form.getByRole('button', { name: 'Cancel', exact: true }).click();
  }
  assert.deepEqual(errors, []); console.log('Generated requirement labels stay hidden in task search, detail/edit and task relations; unrelated saves retain the actual summary and authored description/document, explicit renames work, authored Task names remain intact and both palettes/four widths pass.');
} catch (error) { await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {}); throw error; }
finally { await context.close(); await owned.close(); }
