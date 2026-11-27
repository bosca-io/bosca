import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/specification-detail-browser', new URL(base).port); await mkdir(reports, { recursive: true });
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
const dialog = (name, tab = page) => tab.getByRole('dialog', { name, exact: true });
const spec = async id => (await gql('query($id:UUID!){workOps{specs{spec(id:$id){id key metadataId version ownerProfileId status{category}}}}}', { id })).workOps.specs.spec;
try {
  await login(page, '/workops/specs');
  const sourceProject = (await gql('query($id:UUID!){workOps{projects{project(id:$id){program{id} ownerProfileId}}}}', { id: fixtureProject })).workOps.projects.project;
  const project = (await gql('mutation($program:UUID!,$owner:UUID!){workOps{projects{create(input:{programId:$program,ownerProfileId:$owner,key:"SPB",name:"Specification browser project"}){id}}}}', { program: sourceProject.program.id, owner: sourceProject.ownerProfileId })).workOps.projects.create.id;
  const parent = (await gql('mutation($project:UUID!){workOps{specs{create(input:{projectId:$project,name:"Specification hierarchy parent"}){id key}}}}', { project: fixtureProject })).workOps.specs.create;
  const created = (await gql('mutation($project:UUID!,$parent:UUID!){workOps{specs{create(input:{projectId:$project,parentSpecId:$parent,name:"Customer export specification"}){id key metadataId}}}}', { project, parent: parent.id })).workOps.specs.create;
  const href = `/workops/specs/${created.id}`, editor = page.locator('[data-workops-document] [data-document-ready]');
  await page.goto(`${base}/workops/specs/${created.key}`); await editor.waitFor();
  assert.equal((await page.locator('body').innerText()).includes(created.key), false);
  assert.equal((await page.locator('body').innerText()).includes(parent.key), false);
  assert.equal(await page.getByRole('link', { name: 'Specification hierarchy parent', exact: true }).getAttribute('href'), `/workops/specs/${parent.id}`);
  const childHref = new URL(await page.getByRole('link', { name: 'Create child specification', exact: true }).getAttribute('href'), base);
  assert.equal(childHref.searchParams.get('parent'), created.id); assert.equal(childHref.searchParams.get('project'), project);
  await editor.locator('.ProseMirror > p').first().click(); await page.keyboard.type('The user can export a report from the specification.');
  await editor.evaluate(element => { globalThis.specEditor = element; globalThis.specSurface = element.querySelector('.ProseMirror'); });
  await page.getByRole('button', { name: 'Change owner', exact: true }).click();
  const owner = dialog('Change specification owner'); await owner.waitFor(); await owner.getByRole('button', { name: 'Save owner', exact: true }).click(); await owner.waitFor({ state: 'hidden' });
  assert.equal(await editor.evaluate(element => element === globalThis.specEditor && element.querySelector('.ProseMirror') === globalThis.specSurface), true);
  await editor.locator('.ProseMirror > p').filter({ hasText: 'The user can export a report from the specification.' }).waitFor();
  await page.getByRole('button', { name: 'Save document', exact: true }).click(); await page.getByText('Document saved.', { exact: true }).waitFor();
  await page.reload(); await editor.waitFor(); await editor.locator('.ProseMirror > p').filter({ hasText: 'The user can export a report from the specification.' }).waitFor();
  assert.equal(await editor.getAttribute('data-document-dirty'), null);
  await page.getByRole('button', { name: 'Rename specification', exact: true }).click(); const renaming = dialog('Rename specification');
  await renaming.locator('input[name=name]').fill('Renamed export specification'); await renaming.getByRole('button', { name: 'Save name', exact: true }).click(); await renaming.waitFor({ state: 'hidden' });
  await page.getByRole('heading', { name: 'Renamed export specification', exact: true }).waitFor();
  const content = (await gql('query($id:UUID!){content{metadata(id:$id){name document{title content template{id}}}}}', { id: created.metadataId })).content.metadata;
  assert.equal(content.document.title, 'Customer export specification'); assert.match(JSON.stringify(content.document.content), /The user can export a report/);
  assert.equal(content.document.template.id, 'a0000000-0000-0000-0000-000000000001');

  await page.getByRole('button', { name: 'Start work', exact: true }).click(); await page.getByText('Specification status updated.', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'Resolve', exact: true }).click(); const resolving = dialog('Resolve specification'); await resolving.waitFor();
  const resolution = await resolving.locator('select[name=resolutionId]').evaluate(select => [...select.options].find(option => option.value).textContent);
  await resolving.getByRole('combobox', { name: 'Resolution', exact: true }).click(); await page.getByRole('option', { name: resolution, exact: true }).click();
  await resolving.getByRole('button', { name: 'Resolve specification', exact: true }).click(); await resolving.waitFor({ state: 'hidden' });
  assert.equal((await spec(created.id)).status.category, 'DONE');
  await page.locator('textarea[name=content]').fill('Specification acceptance comment.'); await page.getByRole('button', { name: 'Add comment', exact: true }).click();
  await page.getByText('Specification acceptance comment.', { exact: true }).waitFor();
  const requirements = page.locator('[data-workops-spec-requirements]'); await requirements.getByRole('button', { name: 'Add requirement', exact: true }).click();
  const newRequirement = dialog('Add requirement'); await newRequirement.locator('input[name=name]').fill('Specification requirement in Atlas');
  await newRequirement.getByRole('button', { name: 'Create requirement', exact: true }).click(); await newRequirement.waitFor({ state: 'hidden' });
  const requirementLink = requirements.getByRole('link', { name: 'Specification requirement in Atlas', exact: true }); await requirementLink.waitFor();
  const requirementId = (await requirementLink.getAttribute('href')).split('/').at(-1);
  const requirementCount = page.locator('.workops-task-facts dt').filter({ hasText: /^Requirements$/ }).locator('xpath=following-sibling::dd[1]');
  await requirementCount.getByText('1', { exact: true }).waitFor();
  assert.equal(await requirements.getByRole('link', { name: 'Open created requirement', exact: true }).getAttribute('href'), await requirementLink.getAttribute('href'));
  assert.equal((await gql('query($id:UUID!){workOps{requirements{requirement(id:$id){parentType parentId}}}}', { id: requirementId })).workOps.requirements.requirement.parentId, created.id);
  await requirements.getByRole('button', { name: 'Assign Specification requirement in Atlas', exact: true }).click();
  const assigning = dialog('Assign requirement'); await assigning.waitFor();
  await assigning.getByRole('combobox', { name: 'Assignee', exact: true }).click();
  await assigning.getByRole('combobox', { name: 'Search assignee', exact: true }).fill('admin');
  await assigning.getByRole('option', { name: 'admin', exact: true }).click();
  await assigning.getByRole('button', { name: 'Save assignee', exact: true }).click(); await assigning.waitFor({ state: 'hidden' });
  assert.ok((await gql('query($id:UUID!){workOps{requirements{requirement(id:$id){assigneeProfileId}}}}', { id: requirementId })).workOps.requirements.requirement.assigneeProfileId);
  await requirements.getByRole('button', { name: 'Assign Specification requirement in Atlas', exact: true }).click(); await assigning.waitFor();
  await assigning.getByRole('combobox', { name: 'Assignee', exact: true }).click(); await assigning.getByRole('option', { name: 'Unassigned', exact: true }).click();
  await assigning.getByRole('button', { name: 'Save assignee', exact: true }).click(); await assigning.waitFor({ state: 'hidden' });
  assert.equal((await gql('query($id:UUID!){workOps{requirements{requirement(id:$id){assigneeProfileId}}}}', { id: requirementId })).workOps.requirements.requirement.assigneeProfileId, null);

  const contexts = page.locator('[data-workops-spec-contexts]');
  const addContext = async (kind, query, label) => {
    await contexts.getByRole('button', { name: 'Add context', exact: true }).first().click();
    const adding = dialog('Add specification context'); await adding.waitFor();
    const nextKind = await adding.locator('select[name=contextType]').evaluate((select, label) => [...select.options].find(option => option.textContent === label).value, kind);
    if (await adding.locator('select[name=contextType]').inputValue() !== nextKind) {
      await adding.getByRole('combobox', { name: 'Context type', exact: true }).click(); await page.getByRole('option', { name: kind, exact: true }).click();
      await adding.locator(`form[data-context-kind="${nextKind}"]`).waitFor();
    }
    if (kind === 'External link') await adding.locator('input[name=targetId]').fill(query);
    else {
      const picker = adding.locator('[data-workops-context-picker]'); await picker.waitFor();
      await picker.getByRole('combobox', { name: 'Reference', exact: true }).click();
      const search = picker.getByRole('combobox', { name: 'Search reference', exact: true });
      const option = picker.getByRole('option', { name: new RegExp(`^${query}`) }), deadline = Date.now() + 20000;
      while (true) {
        await search.fill(''); await search.fill(query);
        try { await option.waitFor({ state: 'visible', timeout: 1500 }); break; }
        catch (error) { if (Date.now() >= deadline) throw error; }
      }
      await option.click();
    }
    await adding.locator('input[name=label]').fill(label); await adding.getByRole('button', { name: 'Add context', exact: true }).click();
    await adding.waitFor({ state: 'hidden' }); await contexts.getByRole('link', { name: label, exact: true }).waitFor();
  };
  await addContext('External link', 'https://example.test/specification', 'External specification reference');
  assert.equal(await contexts.getByRole('link', { name: 'External specification reference', exact: true }).getAttribute('href'), 'https://example.test/specification');
  assert.equal(await contexts.getByRole('link', { name: 'External specification reference', exact: true }).getAttribute('target'), '_blank');
  await addContext('Specification', 'Specification hierarchy parent', 'Related specification');
  await addContext('Content item', 'Atlas mention document', 'Reference document');
  const referenceHref = new URL(await contexts.getByRole('link', { name: 'Reference document', exact: true }).getAttribute('href'), base);
  assert.equal(referenceHref.pathname, `/workops/references/metadata/${process.env.ATLAS_WORKOPS_TEST_MENTION_METADATA}`);
  assert.equal(new URL(referenceHref.searchParams.get('returnTo'), base).pathname, href);
  await addContext('Collection', 'Atlas mention collection', 'Reference collection');
  await addContext('Project', 'Specification browser project', 'Related project');
  await addContext('Task', 'Specification requirement in Atlas', 'Related lifecycle task');
  await contexts.getByRole('button', { name: 'Remove context External specification reference', exact: true }).click();
  await contexts.getByRole('link', { name: 'External specification reference', exact: true }).waitFor({ state: 'hidden' });

  const profile = (await gql('mutation{profiles{add(linkToPrincipal:false,profile:{name:"Specification private owner",visibility:USER,attributes:[]}){id}}}')).profiles.add;
  await addContext('Person', 'Specification private owner', 'Owner reference');
  await gql('mutation($id:UUID!,$profile:String!){workOps{specs{addContext(specId:$id,input:{contextType:PROFILE,targetId:$profile}){id}}}}', { id: created.id, profile: profile.id });
  let current = await spec(created.id);
  await gql('mutation($id:UUID!,$version:Long!,$owner:UUID!){workOps{specs{update(id:$id,input:{ownerProfileId:$owner,expectedVersion:$version}){id}}}}', { id: created.id, version: current.version, owner: profile.id });
  await gql('mutation($project:UUID!,$parent:UUID!){workOps{specs{create(input:{projectId:$project,parentSpecId:$parent,name:"Private child specification"}){id}}}}', { project: fixtureProject, parent: created.id });
  for (const action of ['VIEW', 'EDIT']) await gql(`mutation($id:UUID!,$group:UUID!){workOps{projects{addPermission(id:$id,groupId:$group,action:${action})}}}`, { id: project, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
  const readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(); observe(reader);
  try {
    await login(reader, href, true); await reader.getByText('Profile unavailable', { exact: true }).waitFor();
    await reader.getByText('The document is unavailable.', { exact: true }).waitFor();
    assert.equal(await reader.getByRole('button', { name: 'Save document', exact: true }).count(), 0);
    assert.equal(await reader.getByRole('button', { name: 'Rename specification', exact: true }).count(), 0);
    assert.equal((await reader.locator('body').innerText()).includes('Specification private owner'), false);
    assert.equal((await reader.locator('body').innerText()).includes('Specification hierarchy parent'), false);
    assert.equal((await reader.locator('body').innerText()).includes('Private child specification'), false);
    await reader.getByRole('button', { name: 'Change owner', exact: true }).click(); const editing = dialog('Change specification owner', reader); await editing.waitFor();
    assert.equal(await editing.locator('select[name=ownerId]').inputValue(), profile.id);
    current = await spec(created.id);
    await gql('mutation($id:UUID!,$version:Long!){workOps{specs{update(id:$id,input:{sortOrder:1,expectedVersion:$version}){id}}}}', { id: created.id, version: current.version });
    await editing.getByRole('button', { name: 'Save owner', exact: true }).click(); await editing.getByText('Internal server error', { exact: false }).waitFor();
    assert.equal(await editing.locator('select[name=ownerId]').inputValue(), profile.id);
    await editing.getByRole('button', { name: 'Cancel', exact: true }).click(); await reader.reload();
    await reader.getByRole('button', { name: 'Change owner', exact: true }).click(); await editing.waitFor();
    await editing.getByRole('button', { name: 'Save owner', exact: true }).click(); await editing.waitFor({ state: 'hidden' });
    assert.equal((await spec(created.id)).ownerProfileId, profile.id);
    await gql('mutation($id:String!,$group:String!){content{metadata{addPermission(permission:{entityId:$id,groupId:$group,action:VIEW}){action}}}}', { id: created.metadataId, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
    await reader.reload(); const readOnly = reader.locator('[data-workops-document] [data-document-ready]'); await readOnly.waitFor();
    assert.equal(await readOnly.locator('.ProseMirror').getAttribute('contenteditable'), 'false');
    assert.equal(await reader.getByRole('button', { name: 'Save document', exact: true }).count(), 0);
    await reader.screenshot({ path: resolve(reports, 'read-only.png'), fullPage: true });
  } finally { await readerContext.close(); }

  // Independent offset sections preserve each other's navigation state.
  for (let index = 0; index < 26; index++) {
    await gql('mutation($project:UUID!,$id:UUID!,$index:Int!,$name:String!){workOps{specs{create(input:{projectId:$project,parentSpecId:$id,sortOrder:$index,name:$name}){id}}}}', { project, id: created.id, index: index + 1, name: `Specification child page ${index}` });
    await gql('mutation($id:UUID!,$index:Int!,$name:String!){workOps{requirements{create(input:{parentType:SPEC,parentId:$id,sortOrder:$index,name:$name}){id}}}}', { id: created.id, index: index + 1, name: `Specification requirement page ${index}` });
    await gql('mutation($id:UUID!,$content:String!){workOps{specComments{add(specId:$id,input:{content:$content}){id}}}}', { id: created.id, content: `Paged specification comment ${index}` });
  }
  current = await spec(created.id);
  for (let index = 0; index < 26; index++) current = (await gql('mutation($id:UUID!,$version:Long!,$order:Int!){workOps{specs{update(id:$id,input:{sortOrder:$order,expectedVersion:$version}){version}}}}', { id: created.id, version: current.version, order: index + 2 })).workOps.specs.update;
  await page.goto(`${base}${href}`); await editor.waitFor();
  assert.equal(await requirements.locator('tbody tr').count(), 25);
  const children = page.locator('[data-workops-spec-children]'); assert.equal(await children.locator('tbody tr').count(), 25);
  await requirements.getByRole('link', { name: 'Next', exact: true }).click(); assert.equal(await requirements.locator('tbody tr').count(), 2);
  await children.getByRole('link', { name: 'Next', exact: true }).click(); assert.equal(await children.locator('tbody tr').count(), 2);
  assert.equal(new URL(page.url()).searchParams.get('requirementsOffset'), '25');
  await page.getByRole('navigation', { name: 'Specification activity', exact: true }).getByRole('link', { name: 'History', exact: true }).click();
  await page.getByRole('navigation', { name: 'Specification activity pages', exact: true }).getByRole('link', { name: 'Next', exact: true }).click();
  assert.ok(await page.locator('.workops-task-activity article').count() > 0);
  assert.equal(new URL(page.url()).searchParams.get('childrenOffset'), '25'); assert.equal(new URL(page.url()).searchParams.get('requirementsOffset'), '25');
  assert.equal((await page.locator('body').innerText()).includes(created.key), false);
  await page.getByRole('navigation', { name: 'Specification activity', exact: true }).getByRole('link', { name: 'Comments', exact: true }).click();
  assert.equal(await page.locator('.workops-task-activity article').count(), 25);
  await page.getByRole('navigation', { name: 'Specification activity pages', exact: true }).getByRole('link', { name: 'Next', exact: true }).click();
  await page.getByText('Specification acceptance comment.', { exact: true }).waitFor();
  assert.equal(await page.locator('.workops-task-activity article').count(), 2);
  await requirements.getByRole('button', { name: 'Add requirement', exact: true }).click(); await newRequirement.waitFor();
  await newRequirement.locator('input[name=name]').fill('Specification requirement direct result');
  await newRequirement.getByRole('button', { name: 'Create requirement', exact: true }).click(); await newRequirement.waitFor({ state: 'hidden' });
  await requirementCount.getByText('28', { exact: true }).waitFor();
  const directResult = requirements.getByRole('link', { name: 'Open created requirement', exact: true }); await directResult.waitFor();
  assert.equal(new URL(page.url()).searchParams.get('offset'), '25');
  assert.equal(new URL(page.url()).searchParams.get('childrenOffset'), '25');
  assert.equal(new URL(page.url()).searchParams.get('requirementsOffset'), '25');
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 });
    await page.goto(`${base}${href}`); await editor.waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${appearance} ${width}`);
    await page.screenshot({ path: resolve(reports, `${appearance}-${width}.png`), fullPage: true });
    if (width === 390 || width === 1920) {
      await requirements.scrollIntoViewIfNeeded();
      await page.screenshot({ path: resolve(reports, `${appearance}-${width}-requirements.png`) });
      await contexts.scrollIntoViewIfNeeded();
      await page.screenshot({ path: resolve(reports, `${appearance}-${width}-contexts.png`) });
    }
  }
  assert.deepEqual(errors, []); console.log('Specification rich bodies, independent titles, owner/version retention, workflow, Content access, requirements, hierarchy and activity paging passed.');
} catch (error) {
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error(await page.locator('body').innerText().catch(() => 'Page unavailable')); console.error(errors); throw error;
} finally { await owned.close(); }
