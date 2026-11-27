import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';
import { projectCatalogs } from './project-catalogs.mjs';
import { projectRelations } from './project-relations.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/projects-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage(), errors = [];
let expectedRejection = false;
const rejection = value => { expectedRejection = value; };
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
const permission = async (project, action, add) => gql(`mutation($id:UUID!,$group:UUID!){workOps{projects{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:${action})}}}`, { id: project, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
try {
  await login(page, '/workops/projects');
  const parent = (await gql('query($id:UUID!){workOps{projects{project(id:$id){ownerProfileId program{id portfolio{id}}}}}}', { id: fixtureProject })).workOps.projects.project;
  const privateOwner = (await gql('mutation{profiles{add(linkToPrincipal:false,profile:{name:"Private detail owner",visibility:USER,attributes:[]}){id}}}')).profiles.add.id;
  const createProgram = async (key, name) => (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id}}}}', { input: { portfolioId: parent.program.portfolio.id, ownerProfileId: parent.ownerProfileId, key, name } })).workOps.programs.create.id;
  const program = await createProgram('PDET', 'Project detail program'), destination = await createProgram('PNEXT', 'Project destination program');
  const project = (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id}}}}', { input: { programId: program, ownerProfileId: privateOwner, key: 'DETAIL', name: 'Project details in Atlas', description: 'A real project overview' } })).workOps.projects.create.id;
  const href = `/workops/projects/${project}`;
  const tasks = [];
  for (let index = 0; index < 9; index++) tasks.push((await gql('mutation($id:UUID!,$summary:String!){workOps{tasks{create(input:{projectId:$id,summary:$summary}){id key summary}}}}', { id: project, summary: `Detailed task ${index}` })).workOps.tasks.create);
  await page.goto(base + href); await page.getByRole('heading', { name: 'Project details in Atlas', exact: true }).waitFor();
  await page.getByText('Counts describe the 8 most recently modified visible tasks.', { exact: true }).waitFor();
  assert.equal(await page.locator('[data-workops-project-recent-task]').count(), 8);
  await page.getByText('A real project overview', { exact: true }).waitFor();
  const api = { page, context, base, project, href, gql, choose, dialog, rejection, reports };
  await projectCatalogs(api);

  const board = (await gql('mutation($id:UUID!){workOps{boards{create(input:{projectId:$id,name:"Embedded project board",type:KANBAN}){id}}}}', { id: project })).workOps.boards.create.id;
  const emptyBoard = (await gql('mutation($id:UUID!){workOps{boards{create(input:{projectId:$id,name:"Unconfigured project board",type:KANBAN}){id}}}}', { id: project })).workOps.boards.create.id;
  const statuses = (await gql('{workOps{statuses{all{id category}}}}')).workOps.statuses.all;
  const columns = {};
  for (const [name, category, order] of [['To do', 'TODO', 1], ['Working', 'IN_PROGRESS', 2], ['Done', 'DONE', 3]]) columns[name] = (await gql('mutation($input:CreateWorkOpsBoardColumnInput!){workOps{boards{addColumn(input:$input){id}}}}', { input: { boardId: board, name, displayOrder: order, statusIds: statuses.filter(status => status.category === category).map(status => status.id) } })).workOps.boards.addColumn.id;
  await page.goto(`${base}${href}?tab=board&boardId=${emptyBoard}`); await page.getByText('No columns configured', { exact: true }).waitFor();
  assert.equal(await page.getByRole('link', { name: 'Configure columns', exact: true }).getAttribute('href'), `/workops/boards/${emptyBoard}?tab=columns`);
  await choose(page, 'Board', 'Embedded project board');
  await page.waitForURL(url => url.searchParams.get('boardId') === board);
  const card = page.locator(`[data-workops-board-card="${tasks[0].id}"]`); await card.waitFor();
  assert.equal(await page.locator('[data-workops-board-view]').getAttribute('data-workops-board-instance'), `project-${project}`);
  await card.evaluate(node => { globalThis.projectRetainedCard = node; });
  await page.getByRole('button', { name: 'Refresh project details', exact: true }).click();
  await page.waitForFunction(() => !document.querySelector('[data-workops-project-details] [aria-busy=true]'));
  assert.equal(await card.evaluate(node => node === globalThis.projectRetainedCard), true);
  const move = async name => {
    await card.getByRole('button', { name: 'Move Detailed task 0', exact: true }).click(); const panel = dialog('Move Detailed task 0'); await panel.waitFor();
    await choose(panel, 'Destination', name); await panel.getByRole('button', { name: 'Move item', exact: true }).click();
  };
  await move('Working'); await page.locator(`[data-board-column="${columns.Working}"] [data-workops-board-card="${tasks[0].id}"]`).waitFor();
  assert.equal(await card.evaluate(node => node === globalThis.projectRetainedCard), true);
  await move('Done'); const resolution = dialog('Resolve task'); await resolution.waitFor();
  const resolutionName = await resolution.locator('select[name=resolutionId]').evaluate(select => [...select.options].find(option => option.value)?.textContent); assert.ok(resolutionName);
  await choose(resolution, 'Resolution', resolutionName); await resolution.getByRole('button', { name: 'Resolve task', exact: true }).click(); await resolution.waitFor({ state: 'hidden' });
  await page.locator(`[data-board-column="${columns.Done}"] [data-workops-board-card="${tasks[0].id}"]`).waitFor();
  assert.equal((await gql('query($id:UUID!){workOps{tasks{task(id:$id){status{category}}}}}', { id: tasks[0].id })).workOps.tasks.task.status.category, 'DONE');
  await page.getByRole('button', { name: 'Quick add task', exact: true }).click(); const quick = dialog('Quick add task');
  await quick.locator('input[name=summary]').fill('Embedded board quick add'); await quick.getByRole('button', { name: 'Create task', exact: true }).click(); await quick.waitFor({ state: 'hidden' });
  await page.getByRole('link', { name: 'Embedded board quick add', exact: true }).waitFor();
  const sprint = (await gql('mutation($id:UUID!){workOps{sprints{create(input:{boardId:$id,name:"Project overview iteration"}){id version}}}}', { id: board })).workOps.sprints.create;
  await gql('mutation($id:UUID!,$version:Long!){workOps{sprints{start(input:{sprintId:$id,expectedVersion:$version,committedTaskIds:[]}){id}}}}', sprint);
  await page.goto(base + href); await page.getByText('Sprints · 1 active', { exact: true }).waitFor();

  await projectRelations({ ...api, program, destination, owner: parent.ownerProfileId, owned, login, observe, permission, privateOwner });
  await page.goto(base + href); await page.getByRole('button', { name: 'Move project', exact: true }).click(); const moving = dialog('Move project'); await moving.waitFor();
  await choose(moving, 'Destination program', 'PNEXT · Project destination program');
  const snapshot = (await gql('query($id:UUID!){workOps{projects{project(id:$id){key name description ownerProfileId taskCreationFormSchemaKey defaultTaskTypeSchemeId version}}}}', { id: project })).workOps.projects.project;
  await gql('mutation($id:UUID!,$input:WorkOpsProjectInput!,$version:Long!){workOps{projects{update(id:$id,input:$input,expectedVersion:$version){id}}}}', { id: project, version: snapshot.version, input: { programId: program, key: snapshot.key, name: snapshot.name, description: 'Changed project details', ownerProfileId: snapshot.ownerProfileId, taskCreationFormSchemaKey: snapshot.taskCreationFormSchemaKey, defaultTaskTypeSchemeId: snapshot.defaultTaskTypeSchemeId } });
  rejection(true); await moving.getByRole('button', { name: 'Move project', exact: true }).click(); await moving.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); rejection(false);
  assert.equal(await moving.locator('select[name=program]').inputValue(), destination);
  await moving.getByRole('button', { name: 'Retry loading programs', exact: true }).click(); await moving.getByRole('alert').waitFor({ state: 'hidden' }); rejection(true);
  await moving.getByRole('button', { name: 'Move project', exact: true }).click(); await moving.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); rejection(false); assert.equal((await gql('query($id:UUID!){workOps{projects{project(id:$id){program{id}}}}}', { id: project })).workOps.projects.project.program.id, program);
  await moving.getByRole('button', { name: 'Cancel', exact: true }).click(); await moving.waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: 'Refresh project details', exact: true }).click(); await page.getByText('Changed project details', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'Move project', exact: true }).click(); await moving.waitFor(); await choose(moving, 'Destination program', 'PNEXT · Project destination program');
  await moving.getByRole('button', { name: 'Move project', exact: true }).click(); await moving.waitFor({ state: 'hidden' }); await page.getByText('Project moved.', { exact: true }).waitFor();
  assert.equal((await gql('query($id:UUID!){workOps{tasks{task(id:$id){project{id}}}}}', { id: tasks[0].id })).workOps.tasks.task.project.id, project);

  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 });
    for (const tab of ['overview', 'board', 'versions', 'components', 'dependencies', 'labels', 'repositories', 'analytics']) {
      await page.goto(`${base}${href}?tab=${tab}&boardId=${board}`); await page.locator('[data-workops-project-details]').waitFor();
      if (tab === 'board') {
        await choose(page, 'Sprint', 'All tasks');
        await page.locator(`[data-workops-board-card="${tasks[0].id}"]`).waitFor();
      }
      const geometry = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth })); assert.ok(geometry.scroll <= geometry.width + 1, `${tab}: ${JSON.stringify(geometry)}`);
      const clippedActions = await page.locator('[data-workops-project-catalog] .section-heading button, [data-workops-project-dependencies] .section-heading button').evaluateAll(nodes => nodes.flatMap(node => {
        const area = node.closest('.workops-content')?.getBoundingClientRect(), bounds = node.getBoundingClientRect();
        return area && (bounds.left < area.left - 1 || bounds.right > area.right + 1) ? [{ text: node.textContent, left: bounds.left, right: bounds.right, areaLeft: area.left, areaRight: area.right }] : [];
      }));
      assert.deepEqual(clippedActions, [], `${tab}: section actions must fit their scrolling content`);
      await page.screenshot({ path: resolve(reports, `${tab}-${appearance}-${width}.png`), fullPage: true });
      if (tab === 'components' || tab === 'repositories') {
        let panel;
        if (tab === 'components') {
          await page.getByRole('button', { name: 'New component', exact: true }).click(); panel = dialog('New component'); await panel.waitFor();
          await panel.locator('input[name=name]').fill('Responsive project component'); await panel.locator('textarea[name=description]').fill('Retained project component draft');
          await choose(panel, 'Assignee mode', 'Project default');
        } else {
          await page.locator('[data-workops-project-repository]').first().getByRole('button', { name: /^Create deploy config for / }).click();
          panel = dialog('Create deploy config'); await panel.waitFor(); await panel.locator('input[name=authorName]').fill('Project Human'); await panel.locator('input[name=authorEmail]').fill('project.human@example.test');
        }
        const modal = await panel.evaluate(node => ({ left: node.getBoundingClientRect().left, right: node.getBoundingClientRect().right, width: innerWidth }));
        assert.ok(modal.left >= 0 && modal.right <= modal.width + 1, `${tab}: ${JSON.stringify(modal)}`);
        await page.screenshot({ path: resolve(reports, `${tab}-dialog-${appearance}-${width}.png`), fullPage: true });
        await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
      }
    }
  }
  assert.deepEqual(errors, []);
  console.log('Project overview, embedded board, catalogs, dependencies, repositories/deploy config, analytics, human history, denied/stale recovery and eight-tab geometry passed.');
} catch (error) {
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error('Project browser state', JSON.stringify(await page.evaluate(() => ({ path: location.pathname + location.search, dialogs: [...document.querySelectorAll('dialog[open]')].map(node => node.innerText.slice(0, 1800)), alerts: [...document.querySelectorAll('p[role=alert]')].map(node => node.innerText) })).catch(String)));
  throw error;
} finally { await owned.close(); }
