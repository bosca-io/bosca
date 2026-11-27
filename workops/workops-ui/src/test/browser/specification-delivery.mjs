import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/specification-delivery-browser', new URL(base).port); await mkdir(reports, { recursive: true });
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
  await tab.locator('button[type=submit]').click(); await tab.waitForURL(value => value.pathname === path);
};
const spec = async id => (await gql('query($id:UUID!){workOps{specs{spec(id:$id){version metadata{version document{content template{id}}} taskGenerations(limit:50){metadataVersion source generatedTaskIds}}}}}', { id })).workOps.specs.spec;
const body = (title, text) => ({ document: { type: 'doc', content: [{ type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: title }] }, { type: 'paragraph', content: [{ type: 'text', text }] }] } });
const setDocument = async (id, title, content, template) => gql('mutation($id:UUID!,$document:DocumentInput!){content{metadata{setMetadataDocument(id:$id,version:1,document:$document)}}}', { id, document: { title, content, templateMetadataId: template, templateMetadataVersion: 1 } });
try {
  await login(page, '/workops/specs');
  const source = (await gql('query($id:UUID!){workOps{projects{project(id:$id){program{id} ownerProfileId}}}}', { id: fixtureProject })).workOps.projects.project;
  const project = (await gql('mutation($program:UUID!,$owner:UUID!){workOps{projects{create(input:{programId:$program,ownerProfileId:$owner,key:"SGN",name:"Specification generation project"}){id}}}}', { program: source.program.id, owner: source.ownerProfileId })).workOps.projects.create.id;
  const repository = (await gql('mutation($owner:UUID!){git{createRepository(input:{ownerId:$owner,slug:"specification-documents",name:"Specification documents",visibility:PRIVATE,defaultBranch:"main",initializeWithReadme:false}){id}}}', { owner: source.ownerProfileId })).git.createRepository.id;
  const created = (await gql('mutation($project:UUID!,$repository:UUID!){workOps{specs{create(input:{projectId:$project,name:"Specification delivery",gitRepositoryId:$repository,gitPath:"specifications/current.md"}){id key metadataId}}}}', { project, repository })).workOps.specs.create;
  const href = `/workops/specs/${created.id}`;
  await setDocument(created.metadataId, 'Saved specification heading', body('Saved specification heading', 'Saved specification body.'), 'a0000000-0000-0000-0000-000000000001');
  const requirement = (await gql('mutation($id:UUID!){workOps{requirements{create(input:{parentType:SPEC,parentId:$id,name:"Specification generated requirement"}){id metadataId task{id descriptionMarkdown}}}}}', { id: created.id })).workOps.requirements.create;
  await setDocument(requirement.metadataId, 'Current requirement title', body('Current requirement title', 'Current requirement rich body.'), 'a0000000-0000-0000-0000-000000000002');
  await page.goto(base + href); const editor = page.locator('[data-workops-document] [data-document-ready]'); await editor.waitFor();
  const generations = page.locator('[data-workops-spec-generations]');
  await generations.getByRole('button', { name: 'Generate tasks', exact: true }).click(); await generations.getByText('Generated 1 task.', { exact: true }).waitFor();
  await generations.getByRole('link', { name: 'Current requirement title', exact: true }).waitFor();
  let current = await spec(created.id); const generation = current.taskGenerations[0];
  assert.equal(generation.source, 'MANUAL'); assert.equal(generation.metadataVersion, current.metadata.version);
  assert.equal(generation.generatedTaskIds.length, 1); assert.notEqual(generation.generatedTaskIds[0], requirement.task.id);
  const generated = (await gql('query($id:UUID!){workOps{tasks{task(id:$id){summary descriptionMarkdown}}}}', { id: generation.generatedTaskIds[0] })).workOps.tasks.task;
  assert.equal(generated.summary, 'Current requirement title'); assert.match(generated.descriptionMarkdown, /Current requirement rich body\./);
  assert.equal((await gql('query($id:UUID!){workOps{tasks{task(id:$id){descriptionMarkdown}}}}', { id: requirement.task.id })).workOps.tasks.task.descriptionMarkdown, requirement.task.descriptionMarkdown);

  await editor.locator('.ProseMirror > p').first().click(); await page.keyboard.press('End'); await page.keyboard.type(' Unsaved draft.');
  await editor.locator('.ProseMirror > p').filter({ hasText: 'Unsaved draft.' }).waitFor();
  const git = page.locator('[data-workops-spec-git]'); await git.getByRole('link', { name: 'Specification documents', exact: true }).waitFor();
  assert.equal(await git.getByRole('link', { name: 'Specification documents', exact: true }).getAttribute('href'), `/repositories/${repository}`);
  const push = async (tab = page) => {
    await tab.locator('[data-workops-spec-git]').getByRole('button', { name: 'Push saved document', exact: true }).first().click();
    const dialog = tab.getByRole('dialog', { name: 'Push specification to Git', exact: true }); await dialog.waitFor();
    await dialog.locator('input[name=authorName]').fill('Human Author'); await dialog.locator('input[name=authorEmail]').fill('human@example.test');
    await dialog.getByRole('button', { name: 'Push saved document', exact: true }).click(); return dialog;
  };
  let pushing = await push(); await pushing.waitFor({ state: 'hidden' }); await git.getByText('Saved document pushed to Git.', { exact: true }).waitFor();
  let sha = await git.locator('code').innerText(); assert.match(sha, /^[a-f0-9]{40}$/);
  const readCommit = async sha => (await gql('query($id:UUID!,$sha:String!){git{commit(repositoryId:$id,sha:$sha){message authorName authorEmail committerName committerEmail}}}', { id: repository, sha })).git.commit;
  let commit = await readCommit(sha);
  assert.equal(commit.authorName, 'Human Author'); assert.equal(commit.authorEmail, 'human@example.test');
  assert.equal(commit.committerName, 'Human Author'); assert.equal(commit.committerEmail, 'human@example.test'); assert.equal(commit.message.includes(created.key), false);
  let file = (await gql('query($id:UUID!){git{blob(repositoryId:$id,ref:"main",path:"specifications/current.md"){content}}}', { id: repository })).git.blob;
  assert.match(file.content, /Saved specification heading/); assert.match(file.content, /Saved specification body\./); assert.doesNotMatch(file.content, /Unsaved draft/);
  await editor.locator('.ProseMirror > p').filter({ hasText: 'Unsaved draft.' }).waitFor();

  // A changed binding is rejected before the saved-document operation is dispatched.
  await git.getByRole('button', { name: 'Push saved document', exact: true }).first().click(); pushing = page.getByRole('dialog', { name: 'Push specification to Git', exact: true }); await pushing.waitFor();
  current = await spec(created.id);
  await gql('mutation($id:UUID!,$version:Long!){workOps{specs{update(id:$id,input:{gitPath:"specifications/current.json",expectedVersion:$version}){id}}}}', { id: created.id, version: current.version });
  await pushing.getByRole('button', { name: 'Push saved document', exact: true }).click(); await pushing.getByText('The Git binding changed. Reload it before pushing.', { exact: true }).waitFor();
  assert.equal(await pushing.locator('input[name=authorEmail]').inputValue(), 'human@example.test');
  await pushing.getByRole('button', { name: 'Cancel', exact: true }).click(); await git.getByRole('button', { name: 'Reload Git binding', exact: true }).click();
  await git.getByText('specifications/current.json', { exact: true }).waitFor();
  pushing = await push(); await pushing.waitFor({ state: 'hidden' }); await git.getByText('Saved document pushed to Git.', { exact: true }).waitFor();
  sha = await git.locator('code').innerText();
  file = (await gql('query($id:UUID!){git{blob(repositoryId:$id,ref:"main",path:"specifications/current.json"){content}}}', { id: repository })).git.blob;
  const saved = (await spec(created.id)).metadata.document;
  assert.deepEqual(JSON.parse(file.content), saved.content); assert.doesNotMatch(file.content, /Unsaved draft/);
  assert.equal(saved.template.id, 'a0000000-0000-0000-0000-000000000001');

  // Preserve the existing specification EDIT boundary even without Content or repository VIEW.
  for (const action of ['VIEW', 'EDIT']) await gql(`mutation($id:UUID!,$group:UUID!){workOps{projects{addPermission(id:$id,groupId:$group,action:${action})}}}`, { id: project, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
  const readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(); observe(reader);
  try {
    await login(reader, href, true); const readerGit = reader.locator('[data-workops-spec-git]');
    await reader.getByText('The document is unavailable.', { exact: true }).waitFor(); await readerGit.getByRole('link', { name: 'Repository unavailable', exact: true }).waitFor();
    const readerPush = await push(reader); await readerPush.waitFor({ state: 'hidden' }); await readerGit.getByText('Saved document pushed to Git.', { exact: true }).waitFor();
    sha = await readerGit.locator('code').innerText(); assert.equal((await readCommit(sha)).authorName, 'Human Author');
    await readerGit.getByRole('button', { name: 'Push saved document', exact: true }).first().click(); await readerPush.waitFor();
    await gql('mutation($id:UUID!,$group:UUID!){workOps{projects{removePermission(id:$id,groupId:$group,action:EDIT)}}}', { id: project, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
    await readerPush.getByRole('button', { name: 'Push saved document', exact: true }).click();
    await readerPush.getByRole('alert').waitFor();
    assert.equal(await readerPush.getByRole('alert').innerText(), 'Unauthorized access');
    assert.equal(await readerPush.locator('input[name=authorEmail]').inputValue(), 'human@example.test');
    const head = (await gql('query($id:UUID!){git{commits(repositoryId:$id,ref:"main",limit:1){sha}}}', { id: repository })).git.commits[0].sha;
    assert.equal(head, sha);
    await gql('mutation($id:UUID!,$group:UUID!){workOps{projects{addPermission(id:$id,groupId:$group,action:EDIT)}}}', { id: project, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
  } catch (error) {
    console.error('Restricted Git page:', await reader.locator('body').innerText().catch(() => 'Page unavailable')); throw error;
  } finally { await readerContext.close(); }

  for (let index = 0; index < 26; index++) await gql('mutation($id:UUID!){workOps{specs{generateTasks(specId:$id,metadataVersion:1,source:MANUAL){id}}}}', { id: created.id });
  await page.goto(`${base}${href}?tab=history&offset=25&childrenOffset=25&requirementsOffset=25`); await generations.locator('article').first().waitFor();
  assert.equal(await generations.locator('article').count(), 25);
  await generations.getByRole('link', { name: 'Next', exact: true }).click(); assert.equal(await generations.locator('article').count(), 2);
  const state = new URL(page.url()); assert.equal(state.searchParams.get('tab'), 'history'); assert.equal(state.searchParams.get('offset'), '25');
  assert.equal(state.searchParams.get('childrenOffset'), '25'); assert.equal(state.searchParams.get('requirementsOffset'), '25');
  await page.getByRole('navigation', { name: 'Specification activity', exact: true }).getByRole('link', { name: 'Comments', exact: true }).click();
  assert.equal(new URL(page.url()).searchParams.get('generationsOffset'), '25');
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 });
    await page.goto(base + href); await editor.waitFor(); await git.scrollIntoViewIfNeeded();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({ path: resolve(reports, `${appearance}-${width}-git.png`) });
    await generations.scrollIntoViewIfNeeded(); await page.screenshot({ path: resolve(reports, `${appearance}-${width}-generations.png`) });
  }
  assert.deepEqual(errors, []); console.log('Specification generation, independent rich requirement bodies, version provenance, offset history, saved Markdown/JSON Git pushes and caller/author boundaries passed.');
} catch (error) {
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error(await page.locator('body').innerText().catch(() => 'Page unavailable')); console.error(errors); throw error;
} finally { await owned.close(); }
