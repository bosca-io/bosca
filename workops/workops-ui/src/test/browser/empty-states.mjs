import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname), 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/empty-states-browser', new URL(base).port);
await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0), context = await owned.connection.newContext(), page = await context.newPage(), errors = [];
page.on('pageerror', error => errors.push(error.message));
const empty = async text => {
  const message = page.getByText(text, { exact: true }).first();
  await message.waitFor();
  const state = message.locator('xpath=ancestor-or-self::*[contains(concat(" ",normalize-space(@class)," ")," empty-state ")][1]');
  assert.equal(await state.count(), 1, `${text} uses the shared empty-state component`);
  assert.equal(await state.locator('.empty-icon svg').count(), 1, 'Empty content uses the shared icon presentation');
  assert.equal(await state.evaluate(node => getComputedStyle(node).textAlign), 'center');
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
};
try {
  await page.goto(base + '/workops/programs?tab=archived');
  await page.locator('input[name=identifier]').fill('admin');
  await page.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await page.locator('button[type=submit]').click();
  await page.waitForURL(url => url.pathname !== '/login');
  const gql = async (query, variables = {}) => {
    const result = await page.evaluate(async ({ query, variables }) => {
      const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` },
        body: JSON.stringify({ query, variables }) });
      return response.json();
    }, { query, variables });
    assert.equal(result.errors, undefined, JSON.stringify(result));
    return result.data;
  };
  const source = await gql('{profiles{current{id}}workOps{projects{all{id taskCreationFormSchemaKey program{id archivedAt portfolio{id archivedAt}}}}}}');
  const owner = source.profiles.current[0].id;
  const projects = source.workOps.projects.all;
  const parent = projects.find(project => !project.program.archivedAt && !project.program.portfolio.archivedAt);
  assert.ok(parent, 'The fixture supplies an active hierarchy for an isolated empty project');
  const suffix = Math.random().toString(16).slice(2, 8).toUpperCase();
  const program = (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id}}}}', {
    input: { portfolioId: parent.program.portfolio.id, ownerProfileId: owner, key: 'ES' + suffix, name: 'Empty content verification' },
  })).workOps.programs.create;
  const related = (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id program{id}}}}}', {
    input: { programId: program.id, ownerProfileId: owner, key: 'EP' + suffix, name: 'Empty content verification', taskCreationFormSchemaKey: parent.taskCreationFormSchemaKey },
  })).workOps.projects.create;
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]);
    await page.setViewportSize({ width, height: 900 });
    for (const kind of ['portfolios', 'programs', 'projects']) {
      await page.goto(`${base}/workops/${kind}?tab=archived&offset=100000`);
      await empty(`No ${kind} visible on this page.`);
      await page.screenshot({ path: resolve(reports, `${kind}-${appearance}-${width}.png`), fullPage: true });
      await page.getByRole('link', { name: 'Active', exact: true }).click();
      await page.locator('[data-workops-planning] tbody tr').first().waitFor();
      assert.equal(await page.getByText(`No ${kind} visible on this page.`, { exact: true }).isVisible(), false, 'Populated views replace the empty state');
    }
  }
  await page.setViewportSize({ width: 1280, height: 900 });
  const project = `/workops/projects/${related.id}`;
  const sections = [
    [`/workops/boards?projectId=${related.id}`, 'No boards for this project. Create one to get started.'],
    [`/workops/sprints?projectId=${related.id}`, 'No board is selected for this project.'],
    [`/workops/milestones?programId=${related.program.id}`, 'No milestones visible on this page.'],
    [project, 'No visible tasks yet.'],
    [project + '?tab=components', 'No components yet.'], [project + '?tab=labels', 'No labels yet.'],
    [project + '?tab=versions', 'No versions yet.'],
    [project + '?tab=dependencies', 'No dependencies declared.'],
    [project + '?tab=repositories', 'No repositories attached.'],
    [project + '?tab=analytics', 'No identifiers configured.'],
    [`/workops/releases/environments?programId=${related.program.id}`, 'No environments in this program yet.'],
  ];
  for (const appearance of ['dark', 'light']) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]);
    for (const [index, [path, text]] of sections.entries()) {
      await page.goto(base + path);
      await empty(text);
      await page.screenshot({ path: resolve(reports, `section-${index}-${appearance}.png`), fullPage: true });
    }
  }
  assert.deepEqual(errors, []);
  console.log('Shared empty states, populated-view replacement and responsive planning/project sections passed.');
} finally { await context.close(); await owned.close(); }
