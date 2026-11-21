import { compactResult } from './presentation-checks.mjs';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';
import { configureBoard } from './board-configuration.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/boards-browser', new URL(base).port); await mkdir(reports, { recursive: true });
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
  await scope.getByRole('combobox', { name: label, exact: true }).click();
  await tab.getByRole('option', { name: value, exact: true }).click();
};
const dialog = (name, tab = page) => tab.getByRole('dialog', { name, exact: true });
const card = (id, tab = page) => tab.locator(`[data-workops-board-card="${id}"]`);
const inColumn = async (id, column, tab = page) => tab.locator(`[data-board-column="${column}"] [data-workops-board-card="${id}"]`).waitFor();
try {
  await login(page, `/workops/boards?projectId=${fixtureProject}`);
  const parent = (await gql('query($id:UUID!){workOps{projects{project(id:$id){key name program{id} ownerProfileId}}}}', { id: fixtureProject })).workOps.projects.project;
  const project = (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id}}}}', { input: {
    key: 'BOARD', name: 'Atlas board project', programId: parent.program.id, ownerProfileId: parent.ownerProfileId,
  } })).workOps.projects.create.id;
  const other = (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id}}}}', { input: {
    key: 'PAIR', name: 'Atlas board partner', programId: parent.program.id, ownerProfileId: parent.ownerProfileId,
  } })).workOps.projects.create.id;
  await page.goto(`${base}/workops/boards?projectId=${project}`);
  await page.getByText('No boards for this project. Create one to get started.', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'New board', exact: true }).click();
  const creating = dialog('New board'); await creating.waitFor();
  await creating.locator('input[name=name]').fill('Atlas delivery board');
  await choose(creating, 'Type', 'Scrum');
  await creating.getByRole('button', { name: 'Create board', exact: true }).click(); await creating.waitFor({ state: 'hidden' });
  const createdLink = page.getByRole('link', { name: 'Open the created board', exact: true }); await compactResult(createdLink);
  const board = new URL(await createdLink.getAttribute('href'), base).searchParams.get('boardId'); assert.ok(board);
  await createdLink.click(); await page.getByText('No columns configured', { exact: true }).waitFor();
  const statuses = (await gql('{workOps{statuses{all{id name category}}}}')).workOps.statuses.all;
  const columns = await configureBoard({ page, context, base, board, project, statuses, gql, choose, dialog, reports, observe, rejection: value => { expectedRejection = value; } });
  const path = `/workops/boards?projectId=${project}&boardId=${board}`;
  const createTask = async summary => (await gql('mutation($id:UUID!,$summary:String!){workOps{tasks{create(input:{projectId:$id,summary:$summary}){id version status{id}}}}}', { id: project, summary })).workOps.tasks.create;
  const readTask = async id => (await gql('query($id:UUID!){workOps{tasks{task(id:$id){id summary version status{id category} sprintId}}}}', { id })).workOps.tasks.task;
  const first = await createTask('Board snapshot task'); await createTask('Second board snapshot task');
  const sprint = (await gql('mutation($id:UUID!){workOps{sprints{create(input:{boardId:$id,name:"Empty active iteration"}){id version}}}}', { id: board })).workOps.sprints.create;
  await gql('mutation($id:UUID!,$version:Long!){workOps{sprints{start(input:{sprintId:$id,expectedVersion:$version,committedTaskIds:[]}){id}}}}', sprint);
  await page.goto(base + path); await page.locator('[data-workops-board-view] strong').getByText('Empty active iteration', { exact: true }).waitFor();
  assert.equal(await page.locator('[data-workops-board-card]').count(), 0, 'An empty selected sprint must not display all project tasks');
  const allTasks = async tab => { await choose(tab, 'Sprint', 'All tasks', tab); };
  await allTasks(page); await card(first.id).waitFor(); await page.getByText('WIP limit exceeded', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'Quick add task', exact: true }).click(); const quick = dialog('Quick add task');
  await quick.locator('input[name=summary]').fill('Created from board quick add');
  await quick.getByRole('button', { name: 'Create task', exact: true }).click(); await quick.waitFor({ state: 'hidden' });
  await page.getByRole('link', { name: 'Created from board quick add', exact: true }).waitFor();
  const move = async (id, title, destination, tab = page) => {
    const handle = card(id, tab).getByRole('button', { name: `Move ${title}`, exact: true });
    await handle.focus(); await handle.press('Enter');
    const panel = dialog(`Move ${title}`, tab); await panel.waitFor(); await choose(panel, 'Destination', destination, tab);
    await panel.getByRole('button', { name: 'Move item', exact: true }).click(); return panel;
  };
  const drag = async (id, target) => {
    const handle = card(id).locator('[data-board-move-handle] button');
    const column = page.locator(`[data-board-column="${target}"]`);
    await handle.scrollIntoViewIfNeeded(); const start = await handle.boundingBox(); assert.ok(start);
    const end = await column.boundingBox(); assert.ok(end);
    await page.mouse.move(start.x + start.width / 2, start.y + start.height / 2); await page.mouse.down();
    await page.mouse.move(end.x + end.width / 2, end.y + 100, { steps: 14 }); await page.mouse.up();
  };
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 });
    const title = `Board ${appearance} ${width}`; const task = await createTask(title);
    await page.goto(base + path); await allTasks(page); await card(task.id).waitFor();
    await card(task.id).evaluate(node => { globalThis.retainedBoardCard = node; });
    if (width === 1920) await drag(task.id, columns.Working);
    else await move(task.id, title, 'Working');
    await inColumn(task.id, columns.Working);
    assert.equal((await readTask(task.id)).status.category, 'IN_PROGRESS');
    assert.equal(await card(task.id).evaluate(node => node === globalThis.retainedBoardCard), true);
    await move(task.id, title, 'Done'); const resolving = dialog('Resolve task'); await resolving.waitFor();
    await resolving.getByRole('button', { name: 'Cancel', exact: true }).click(); await resolving.waitFor({ state: 'hidden' });
    await card(task.id).locator(':scope > p[role=alert]').getByText('Closing move canceled. The task has not moved.', { exact: true }).waitFor();
    assert.equal((await readTask(task.id)).status.category, 'IN_PROGRESS');
    const retryPanel = dialog(`Move ${title}`); if (await retryPanel.isVisible()) await retryPanel.getByRole('button', { name: 'Cancel', exact: true }).click();
    await move(task.id, title, 'Done'); await resolving.waitFor();
    const resolution = await resolving.locator('select[name=resolutionId]').evaluate(select => [...select.options].find(option => option.value)?.textContent); assert.ok(resolution);
    await choose(resolving, 'Resolution', resolution); await resolving.getByRole('button', { name: 'Resolve task', exact: true }).click();
    await resolving.waitFor({ state: 'hidden' }); await inColumn(task.id, columns.Done);
    assert.equal((await readTask(task.id)).status.category, 'DONE');
    const geometry = await page.evaluate(() => ({ viewport: innerWidth, width: document.documentElement.scrollWidth }));
    assert.ok(geometry.width <= geometry.viewport + 1, JSON.stringify(geometry));
    await page.locator('[data-workops-board-directory]').evaluate(node => { for (let parent = node.parentElement; parent; parent = parent.parentElement) parent.scrollTop = 0; });
    await page.screenshot({ path: resolve(reports, `${appearance}-${width}.png`), fullPage: true });
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  const staleTask = await createTask('Retained stale board card'); await page.goto(base + path); await allTasks(page);
  await gql('mutation($id:UUID!,$version:Long!){workOps{tasks{update(id:$id,input:{summary:"Newer board task",expectedVersion:$version}){id}}}}', { id: staleTask.id, version: staleTask.version });
  expectedRejection = true; const stalePanel = await move(staleTask.id, 'Retained stale board card', 'Working');
  await stalePanel.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); expectedRejection = false;
  await inColumn(staleTask.id, columns['To do']); assert.equal(await stalePanel.locator('select[name=destination]').inputValue(), columns.Working);
  await stalePanel.getByRole('button', { name: 'Cancel', exact: true }).click();
  await page.getByRole('button', { name: 'Refresh board', exact: true }).click(); await page.getByRole('link', { name: 'Newer board task', exact: true }).waitFor();
  await move(staleTask.id, 'Newer board task', 'Working'); await inColumn(staleTask.id, columns.Working);

  const deniedTask = await createTask('Board permission retry');
  const permission = async (action, add) => gql(`mutation($id:UUID!,$group:UUID!){workOps{projects{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:${action})}}}`, { id: project, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
  await permission('VIEW', true); await permission('EDIT', true);
  const readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(); observe(reader);
  try {
    await login(reader, path, true); await allTasks(reader); await card(deniedTask.id, reader).waitFor();
    await card(deniedTask.id, reader).getByRole('button', { name: 'Move Board permission retry', exact: true }).click();
    const panel = dialog('Move Board permission retry', reader); await choose(panel, 'Destination', 'Working', reader);
    await permission('EDIT', false); expectedRejection = true;
    await panel.getByRole('button', { name: 'Move item', exact: true }).click();
    await panel.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); expectedRejection = false;
    await inColumn(deniedTask.id, columns['To do'], reader);
    assert.equal(await panel.locator('select[name=destination]').inputValue(), columns.Working);
    await permission('EDIT', true); await panel.getByRole('button', { name: 'Move item', exact: true }).click();
    await inColumn(deniedTask.id, columns.Working, reader);
    assert.equal((await readTask(deniedTask.id)).status.category, 'IN_PROGRESS');
  } finally { await readerContext.close(); await permission('EDIT', false); await permission('VIEW', false); }

  await page.getByRole('button', { name: 'New board', exact: true }).click(); await creating.waitFor();
  await creating.locator('input[name=name]').fill('Atlas cross-project board'); await choose(creating, 'Scope', 'Multiple projects');
  await creating.getByRole('combobox', { name: 'Projects', exact: true }).click();
  await page.getByRole('option', { name: 'BOARD · Atlas board project', exact: true }).click();
  await page.getByRole('option', { name: 'PAIR · Atlas board partner', exact: true }).click();
  await page.getByRole('option', { name: `${parent.key} · ${parent.name}`, exact: true }).click(); await page.keyboard.press('Escape');
  await choose(creating, 'Swimlane strategy', 'Project');
  await creating.getByRole('button', { name: 'Create board', exact: true }).click(); await creating.waitFor({ state: 'hidden' });
  const multi = new URL(await createdLink.getAttribute('href'), base).searchParams.get('boardId');
  assert.deepEqual(new Set((await gql('query($id:UUID!){workOps{boards{board(id:$id){projectId projects{id}}}}}', { id: multi })).workOps.boards.board.projects.map(item => item.id)), new Set([fixtureProject, other]));
  const resultProject = new URL(await createdLink.getAttribute('href'), base).searchParams.get('projectId');
  assert.ok([fixtureProject, other].includes(resultProject));
  await createdLink.click(); await page.getByRole('heading', { name: 'Atlas cross-project board', exact: true }).waitFor();
  await page.getByText('No columns configured', { exact: true }).waitFor();
  await page.getByRole('link', { name: 'Configure', exact: true }).click();
  const multiSettings = page.locator('[data-board-tab="settings"] form').first();
  await multiSettings.getByRole('combobox', { name: 'Projects', exact: true }).click();
  await page.getByRole('option', { name: 'PAIR · Atlas board partner', exact: true }).click();
  await page.getByRole('option', { name: 'BOARD · Atlas board project', exact: true }).click(); await page.keyboard.press('Escape');
  await multiSettings.getByRole('button', { name: 'Save board settings', exact: true }).click();
  await page.getByText('Board settings saved.', { exact: true }).waitFor();
  assert.deepEqual(new Set((await gql('query($id:UUID!){workOps{boards{board(id:$id){projects{id}}}}}', { id: multi })).workOps.boards.board.projects.map(item => item.id)), new Set([fixtureProject, project]));

  const multiVersion = (await gql('query($id:UUID!){workOps{boards{board(id:$id){name type swimlaneStrategy version}}}}', { id: multi })).workOps.boards.board;
  await gql('mutation($id:UUID!,$input:UpdateWorkOpsBoardInput!){workOps{boards{update(id:$id,input:$input){id}}}}', {
    id: multi, input: { name: multiVersion.name, type: multiVersion.type, swimlaneStrategy: multiVersion.swimlaneStrategy, expectedVersion: multiVersion.version, addProjectIds: [other] },
  });
  await gql('mutation($input:CreateWorkOpsBoardColumnInput!){workOps{boards{addColumn(input:$input){id}}}}', {
    input: { boardId: multi, name: 'Shared backlog', displayOrder: 1, statusIds: statuses.filter(status => status.category === 'TODO').map(status => status.id) },
  });
  const privateTask = (await gql('mutation($id:UUID!){workOps{tasks{create(input:{projectId:$id,summary:"Task with explicit view access"}){id}}}}', { id: other })).workOps.tasks.create;
  const privateDeniedTask = (await gql('mutation($id:UUID!){workOps{tasks{create(input:{projectId:$id,summary:"Private board task without access"}){id}}}}', { id: other })).workOps.tasks.create;
  const readerGroup = process.env.ATLAS_WORKOPS_TEST_READER_GROUP;
  await gql('mutation($id:UUID!,$group:UUID!){workOps{projects{addPermission(id:$id,groupId:$group,action:VIEW)}}}', { id: project, group: readerGroup });
  await gql('mutation($id:UUID!,$group:UUID!){workOps{tasks{addPermission(id:$id,groupId:$group,action:VIEW)}}}', { id: privateTask.id, group: readerGroup });
  const privateSprint = (await gql('mutation($id:UUID!){workOps{sprints{create(input:{boardId:$id,name:"Task grant iteration"}){id version}}}}', { id: multi })).workOps.sprints.create;
  await gql('mutation($id:UUID!,$version:Long!,$task:UUID!){workOps{sprints{start(input:{sprintId:$id,expectedVersion:$version,committedTaskIds:[$task]}){id}}}}', { ...privateSprint, task: privateTask.id });
  const partialContext = await owned.connection.newContext(), partial = await partialContext.newPage(); observe(partial);
  try {
    await login(partial, `/workops/boards?projectId=${project}&boardId=${multi}`, true);
    const access = (await gql('query($project:UUID!,$task:UUID!){workOps{projects{project(id:$project){id}} tasks{task(id:$task){id}}}}', { project: other, task: privateTask.id }, partial)).workOps;
    assert.equal(access.projects.project, null); assert.equal(access.tasks.task.id, privateTask.id);
    await card(privateTask.id, partial).waitFor(); assert.equal(await card(privateDeniedTask.id, partial).count(), 0);
    await allTasks(partial); await card(privateTask.id, partial).waitFor(); assert.equal(await card(privateDeniedTask.id, partial).count(), 0);
    assert.equal((await partial.locator('body').innerText()).includes('Atlas board partner'), false);
  } finally {
    await partialContext.close();
    await gql('mutation($id:UUID!,$group:UUID!){workOps{tasks{removePermission(id:$id,groupId:$group,action:VIEW)}}}', { id: privateTask.id, group: readerGroup });
    await gql('mutation($id:UUID!,$group:UUID!){workOps{projects{removePermission(id:$id,groupId:$group,action:VIEW)}}}', { id: project, group: readerGroup });
  }
  assert.deepEqual(errors, []);
  console.log('WorkOps board creation, authorized scope, empty sprint, quick add, real workflow moves/resolutions, stale rejection and retained cards passed in both palettes at four widths.');
} catch (error) {
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error('Board browser state', JSON.stringify(await page.evaluate(() => ({ path: location.pathname + location.search, settingsName: document.querySelector('[data-board-tab="settings"] input[name="name"]')?.value, dialogs: [...document.querySelectorAll('dialog[open]')].map(node => ({ text: node.innerText.slice(0, 900) })), boards: [...document.querySelectorAll('[data-workops-board-view]')].map(node => ({ instance: node.dataset.workopsBoardInstance, result: node.dataset.workopsMoveResult, sequence: node.dataset.workopsMoveSequence })) })).catch(String)));
  throw error;
} finally { await owned.close(); }
