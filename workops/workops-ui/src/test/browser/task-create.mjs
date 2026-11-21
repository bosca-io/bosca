import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname), 'Use the isolated local Atlas fixture.');
const project = process.env.ATLAS_WORKOPS_TEST_PROJECT;
const password = process.env.ATLAS_WORKOPS_TEST_PASSWORD;
assert.ok(project && password);
const reports = resolve('build/reports/task-create-browser', new URL(base).port);
await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0);
const context = owned.connection.contexts()[0], page = context.pages()[0];
const errors = [];
const actionTrace = [];
page.on('request', request => {
  if (new URL(request.url()).pathname === '/_bml/action') {
    const body = JSON.parse(request.postData()); actionTrace.push({ method: body.method, project: body.args?.[0] });
  }
});
await context.addInitScript(() => {
  globalThis.taskFormEvents = [];
  document.addEventListener('schemaformchange', event => globalThis.taskFormEvents.push({ property: event.detail.property, value: event.detail.value }), true);
});
const gql = async (query, variables = {}) => {
  const result = await page.evaluate(async ({ query, variables }) => {
    const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) });
    return response.json();
  }, { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
page.on('pageerror', error => errors.push(error.message));
page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
try {
  await page.goto(base + '/login');
  await page.locator('input[name=identifier]').fill('admin');
  await page.locator('input[name=password]').fill(password);
  await page.locator('button[type=submit]').click();
  await page.waitForURL(url => url.pathname !== '/login');
  const originalProject = (await gql('query($id:UUID!){workOps{projects{project(id:$id){id key name ownerProfileId taskCreationFormSchemaKey program{id}}}}}', { id: project })).workOps.projects.project;
  const originalForm = (await gql('query($key:String!){formSchemas{byKey(key:$key){schema uiSchema}}}', { key: originalProject.taskCreationFormSchemaKey })).formSchemas.byKey;
  const switchedFormKey = 'atlas.project-switch-task';
  await gql('mutation($input:FormSchemaInput!){formSchemasMutation{save(input:$input){id}}}', { input: {
    key: switchedFormKey, name: 'Project switch task', type: 'WORK_OPS', configuration: { workOps: {} }, schema: originalForm.schema, uiSchema: originalForm.uiSchema,
  } });
  const alternate = (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id version taskCreationFormSchemaKey}}}}', { input: {
    programId: originalProject.program.id, key: 'SWITCH', name: 'Project switch fixture', ownerProfileId: originalProject.ownerProfileId, taskCreationFormSchemaKey: switchedFormKey,
  } })).workOps.projects.create;
  assert.equal(alternate.taskCreationFormSchemaKey, switchedFormKey, 'Project creation must persist its configured task form.');
  for (const appearance of ['dark', 'light']) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]);
    for (const width of [1920, 1024, 768, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.goto(`${base}/workops/tasks?new=1&project=${encodeURIComponent(project)}`);
      const dialog = page.getByRole('dialog', { name: 'New task', exact: true });
      await dialog.waitFor({ state: 'visible' });
      assert.equal(await page.locator('html').getAttribute('data-theme'), appearance);
      const surface = await dialog.evaluate(element => ({
        scheme: getComputedStyle(element).colorScheme,
        background: getComputedStyle(element).backgroundColor,
        text: getComputedStyle(element).color,
      }));
      assert.equal(surface.scheme, appearance);
      assert.equal(surface.background, appearance === 'light' ? 'rgb(255, 255, 255)' : 'rgb(16, 19, 28)', JSON.stringify(surface));
      assert.equal(await dialog.locator('[data-schema-property=projectId] select').inputValue(), project);
      const summary = dialog.locator('[data-schema-property=summary] input');
      const fieldColors = await summary.evaluate(element => ({ background: getComputedStyle(element).backgroundColor, foreground: getComputedStyle(element).color }));
      assert.equal(fieldColors.background, appearance === 'light' ? 'rgb(249, 250, 251)' : 'rgb(22, 26, 37)', JSON.stringify(fieldColors));
      const luminance = color => color.match(/[\d.]+/g).slice(0, 3).map(Number).map(channel => {
        const value = channel / 255; return value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4;
      }).reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0);
      const a = luminance(fieldColors.background), b = luminance(fieldColors.foreground);
      assert.ok((Math.max(a, b) + .05) / (Math.min(a, b) + .05) >= 4.5, JSON.stringify(fieldColors));
      await summary.fill('');
      await dialog.getByRole('button', { name: 'Create task', exact: true }).click();
      assert.equal(await summary.evaluate(input => document.activeElement === input), true);
      const title = `Browser task ${appearance} ${width}`;
      await summary.fill(title);
      await dialog.locator('[data-schema-property=descriptionMarkdown] textarea').fill('Created through the imported WorkOps form.');
      const switchProject = async (id, label, formKey) => {
        await dialog.locator('[data-schema-property=projectId] [data-bml-ref=trigger]').click();
        await page.getByRole('option', { name: label, exact: true }).click();
        await page.waitForFunction(({ id, formKey }) => document.querySelector('[data-workops-create-project]')?.getAttribute('data-workops-create-project') === id
          && document.querySelector('[data-workops-form-key]')?.getAttribute('data-workops-form-key') === formKey, { id, formKey });
        assert.equal(await summary.inputValue(), title);
        assert.equal(await dialog.locator('[data-schema-property=descriptionMarkdown] textarea').inputValue(), 'Created through the imported WorkOps form.');
        assert.equal(await dialog.locator('[data-schema-property=projectId] select').inputValue(), id);
      };
      await switchProject(alternate.id, 'SWITCH · Project switch fixture', switchedFormKey);
      await switchProject(project, `${originalProject.key} · ${originalProject.name}`, originalProject.taskCreationFormSchemaKey);
      const geometry = await dialog.evaluate(element => {
        const box = element.getBoundingClientRect();
        return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: innerWidth, height: innerHeight,
          overflow: document.documentElement.scrollWidth > innerWidth };
      });
      assert.ok(geometry.left >= -1 && geometry.right <= geometry.width + 1 && geometry.top >= -1 && geometry.bottom <= geometry.height + 1, JSON.stringify(geometry));
      assert.equal(geometry.overflow, false, JSON.stringify(geometry));
      await page.screenshot({ path: resolve(reports, `${appearance}-${width}.png`), fullPage: true });
      await dialog.getByRole('button', { name: 'Create task', exact: true }).click();
      await dialog.waitFor({ state: 'hidden' });
      await page.getByRole('link', { name: 'Refresh tasks', exact: true }).click();
      await page.getByRole('link', { name: title, exact: true }).waitFor();
    }
  }
  await gql('mutation($id:UUID!,$version:Long!){workOps{projects{archive(id:$id,expectedVersion:$version){id}}}}', { id: alternate.id, version: alternate.version });
  assert.deepEqual(errors, []);
  console.log('WorkOps task creation passed in both appearances at four widths.');
} catch (error) {
  console.error('Project change diagnostics', JSON.stringify({ actionTrace, state: await page.evaluate(() => ({
    events: globalThis.taskFormEvents, project: document.querySelector('[data-workops-create-project]')?.getAttribute('data-workops-create-project'),
    forms: [...document.querySelectorAll('[data-workops-form-key]')].map(form => ({ key: form.getAttribute('data-workops-form-key'), value: form.querySelector('input[type=hidden]')?.value })),
    states: [...document.querySelectorAll('script[data-bml-state-key]')].filter(script => script.dataset.bmlStateKey.includes('workops-task-create')).map(script => {
      const state = JSON.parse(script.textContent); return { project: state.projectId, form: state.definition?.key, error: state.error };
    }),
  })).catch(String) }));
  console.error(await page.locator('body').innerText().catch(() => 'Page unavailable'));
  throw error;
} finally { await owned.close(); }
