import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, project = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && project, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/specifications-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage(), errors = [];
page.on('pageerror', error => errors.push(error.message));
page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
const gql = async (query, variables = {}, tab = page) => {
  const result = await tab.evaluate(async ({ query, variables }) => {
    const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) }); return response.json();
  }, { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const source = 'ORDER BY modified DESC', url = value => `${base}/workops/specs?bql=${encodeURIComponent(value)}`;
let parentMetadata = '';
const search = page.locator('.workops-spec-search'), input = search.locator('.cm-content'), status = search.locator('[data-bml-ref=validation]');
const validate = async value => {
  const response = page.waitForResponse(response => response.url().includes('/_bml/contract/WorkOpsBql/validateSpec') && response.request().postData() === JSON.stringify([value]));
  await input.fill(value); assert.equal((await response).status(), 200);
};
try {
  await page.goto(url(source)); await page.locator('input[name=identifier]').fill('admin');
  await page.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_PASSWORD); await page.locator('button[type=submit]').click();
  await input.waitFor();
  const parentTitle = 'Specification picker parent';
  const parent = (await gql('mutation($project:UUID!,$name:String!){workOps{specs{create(input:{projectId:$project,name:$name}){id metadataId version}}}}', { project, name: parentTitle })).workOps.specs.create;
  parentMetadata = parent.metadataId;
  // Exercise an actual offset page without depending on data left by another browser script.
  const pageSeeds = Array.from({ length: 25 }, (_, i) => `s${i}:create(input:{projectId:$project,name:"Specification page ${i}"}){id}`).join('\n');
  await gql(`mutation($project:UUID!){workOps{specs{${pageSeeds}}}}`, { project });
  await page.goto(url(source)); await page.locator('[data-workops-spec-row]').first().waitFor();
  assert.equal(await page.locator('[data-workops-spec-row]').count(), 25);
  await page.getByRole('navigation', { name: 'Specification pages' }).getByRole('link', { name: /Next/ }).click();
  await page.waitForURL(value => value.searchParams.get('offset') === '25');
  await page.getByRole('navigation', { name: 'Specification pages' }).getByRole('link', { name: /Previous/ }).click();
  await page.waitForURL(value => value.searchParams.get('offset') === '0');
  await validate('assignee = currentUser()'); await status.waitFor(); assert.match(await status.innerText(), /assignee/);
  const before = page.url(); await search.getByRole('button', { name: 'Search', exact: true }).click(); assert.equal(page.url(), before);
  await validate('owner = currentUser() ORDER BY modified DESC'); await status.waitFor({ state: 'hidden' });
  await input.press('ControlOrMeta+Enter'); await page.waitForURL(value => value.searchParams.get('bql')?.startsWith('owner ='));
  assert.equal(await page.evaluate(() => localStorage.getItem('workops-specs-bql')), 'owner = currentUser() ORDER BY modified DESC');
  assert.equal(await page.evaluate(() => localStorage.getItem('workops-tasks-bql')), null);
  await page.goto(url(source)); await input.waitFor();
  assert.equal(await search.locator('textarea[name=bql]').inputValue(), source);
  await search.getByRole('button', { name: 'Search', exact: true }).focus(); await page.keyboard.press('j');
  assert.equal(await page.locator('[data-workops-spec-link]').first().evaluate(element => document.activeElement === element), true);
  await page.keyboard.press('/'); assert.equal(await input.evaluate(element => document.activeElement === element), true);

  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 });
    await page.goto(`${url(source)}&new=1&project=${project}`);
    const dialog = page.getByRole('dialog', { name: 'New specification', exact: true }); await dialog.waitFor();
    assert.equal(await dialog.locator('select[name=projectId]').inputValue(), project);
    const name = dialog.locator('input[name=name]'), title = `Browser specification ${appearance} ${width}`;
    await dialog.getByRole('button', { name: 'Create specification', exact: true }).click();
    assert.equal(await name.evaluate(element => document.activeElement === element), true);
    await name.fill(title);
    if (appearance === 'dark' && width === 1920) {
      const picker = dialog.locator('[data-workops-spec-picker]');
      await picker.getByRole('combobox', { name: 'Parent specification', exact: true }).click();
      const query = picker.getByRole('combobox', { name: 'Search parent specification', exact: true });
      const option = picker.getByRole('option', { name: new RegExp(`^${parentTitle}`) });
      const deadline = Date.now() + 20000;
      while (true) {
        await query.fill(''); await query.fill('Specification picker');
        try { await option.waitFor({ state: 'visible', timeout: 1500 }); break; }
        catch (error) { if (Date.now() >= deadline) throw error; }
      }
      await option.click(); assert.equal(await dialog.locator('select[name=parentId]').inputValue(), parent.id);
      assert.equal(await dialog.getByRole('button', { name: /Retry loading|Reload/ }).count(), 0);
      await dialog.getByRole('combobox', { name: 'Parent specification', exact: true }).filter({ hasText: parentTitle }).waitFor();
      assert.equal(await name.inputValue(), title); assert.equal(await dialog.locator('select[name=parentId]').inputValue(), parent.id);
    }
    const geometry = await dialog.evaluate(element => {
      const box = element.getBoundingClientRect(); return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: innerWidth, height: innerHeight, overflow: document.documentElement.scrollWidth > innerWidth };
    });
    assert.ok(geometry.left >= -1 && geometry.right <= geometry.width + 1 && geometry.top >= -1 && geometry.bottom <= geometry.height + 1 && !geometry.overflow, JSON.stringify(geometry));
    await page.screenshot({ path: resolve(reports, `create-${appearance}-${width}.png`), fullPage: true });
    await dialog.getByRole('button', { name: 'Create specification', exact: true }).click(); await dialog.waitFor({ state: 'hidden' });
    const link = page.getByRole('link', { name: 'Open specification', exact: true }); await link.waitFor();
    const id = (await link.getAttribute('href')).split('/').at(-1);
    const created = (await gql('query($id:UUID!){workOps{specs{spec(id:$id){parentSpecId projectId metadata{name document{title content template{id}}}}}}}', { id })).workOps.specs.spec;
    assert.equal(created.metadata.name, title); assert.equal(created.projectId, project);
    assert.equal(created.parentSpecId, appearance === 'dark' && width === 1920 ? parent.id : null);
    assert.equal(created.metadata.document.title, title);
    assert.ok(created.metadata.document.template?.id);
    await page.getByRole('link', { name: 'Refresh specifications', exact: true }).click();
    await page.getByRole('link', { name: title, exact: true }).waitFor();
    assert.equal(await page.locator('html').getAttribute('data-theme'), appearance);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  }
  // Explicit parent links resolve to titles; an unavailable parent keeps the authored draft.
  await page.goto(`${url(source)}&new=1&project=${project}&parent=${parent.id}`);
  const dialog = page.getByRole('dialog', { name: 'New specification', exact: true }); await dialog.waitFor();
  await dialog.getByRole('combobox', { name: 'Parent specification', exact: true }).filter({ hasText: parentTitle }).waitFor();
  await dialog.locator('input[name=name]').fill('Retained specification draft');
  const currentParent = (await gql('query($id:UUID!){workOps{specs{spec(id:$id){version}}}}', { id: parent.id })).workOps.specs.spec;
  await gql('mutation($id:UUID!,$version:Long!){workOps{specs{softDelete(id:$id,expectedVersion:$version){id}}}}', { id: parent.id, version: currentParent.version });
  await dialog.getByRole('button', { name: 'Create specification', exact: true }).click();
  await dialog.getByText('The parent specification is unavailable. Choose another parent.', { exact: true }).waitFor();
  assert.equal(await dialog.locator('input[name=name]').inputValue(), 'Retained specification draft');
  assert.equal(await dialog.locator('select[name=parentId]').inputValue(), parent.id);
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click(); await dialog.waitFor({ state: 'hidden' });

  const sourceProject = (await gql('query($id:UUID!){workOps{projects{project(id:$id){program{id} ownerProfileId}}}}', { id: project })).workOps.projects.project;
  const ordinaryProject = (await gql('mutation($program:UUID!,$owner:UUID!){workOps{projects{create(input:{programId:$program,ownerProfileId:$owner,key:"SPC",name:"Ordinary specification project"}){id}}}}', { program: sourceProject.program.id, owner: sourceProject.ownerProfileId })).workOps.projects.create.id;
  const privateOwner = (await gql('mutation{profiles{add(linkToPrincipal:false,profile:{name:"Specification hidden search owner",visibility:USER,attributes:[]}){id}}}')).profiles.add.id;
  const sharedSpec = (await gql('mutation($project:UUID!){workOps{specs{create(input:{projectId:$project,name:"Ordinary visible parent specification"}){id metadataId version}}}}', { project: ordinaryProject })).workOps.specs.create;
  await gql('mutation($id:UUID!,$version:Long!,$owner:UUID!){workOps{specs{update(id:$id,input:{expectedVersion:$version,ownerProfileId:$owner}){id}}}}', { id: sharedSpec.id, version: sharedSpec.version, owner: privateOwner });
  for (const action of ['VIEW', 'EDIT']) await gql(`mutation($id:UUID!,$group:UUID!){workOps{projects{addPermission(id:$id,groupId:$group,action:${action})}}}`, { id: ordinaryProject, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
  await gql('mutation($id:String!,$group:String!){content{metadata{addPermission(permission:{entityId:$id,groupId:$group,action:VIEW}){action}}}}', { id: sharedSpec.metadataId, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
  const readerContext = await owned.connection.newContext(), reader = await readerContext.newPage();
  reader.on('pageerror', error => errors.push(error.message)); reader.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  try {
    await reader.goto(url(source)); await reader.locator('input[name=identifier]').fill(process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER);
    await reader.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD); await reader.locator('button[type=submit]').click();
    const sharedRow = reader.locator('[data-workops-spec-row]').filter({ has: reader.getByRole('link', { name: 'Ordinary visible parent specification', exact: true }) });
    await sharedRow.getByText('Profile unavailable', { exact: true }).waitFor();
    assert.equal((await reader.locator('body').innerText()).includes('Specification hidden search owner'), false);
    await reader.goto(`${url(source)}&new=1&project=${ordinaryProject}&parent=${sharedSpec.id}`);
    const creating = reader.getByRole('dialog', { name: 'New specification', exact: true }); await creating.waitFor();
    await creating.getByRole('combobox', { name: 'Parent specification', exact: true }).filter({ hasText: 'Ordinary visible parent specification' }).waitFor();
    await creating.locator('input[name=name]').fill('Ordinary profile specification');
    await creating.getByRole('button', { name: 'Create specification', exact: true }).click(); await creating.waitFor({ state: 'hidden' });
    const createdId = (await reader.getByRole('link', { name: 'Open specification', exact: true }).getAttribute('href')).split('/').at(-1);
    const currentProfile = (await gql('{profiles{current{id}}}', {}, reader)).profiles.current[0].id;
    const result = (await gql('query($id:UUID!){workOps{specs{spec(id:$id){metadataId ownerProfileId parentSpecId metadata{name}}}}}', { id: createdId })).workOps.specs.spec;
    assert.equal(result.ownerProfileId, currentProfile); assert.equal(result.parentSpecId, sharedSpec.id);
    assert.equal((await gql('query($id:UUID!){workOps{specs{spec(id:$id){metadata{name}}}}}', { id: createdId }, reader)).workOps.specs.spec.metadata, null, 'WorkOps creation does not grant independent Content permissions');
    for (const action of ['VIEW', 'EDIT']) await gql(`mutation($id:String!,$group:String!){content{metadata{addPermission(permission:{entityId:$id,groupId:$group,action:${action}}){action}}}}`, { id: result.metadataId, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
    await reader.goto(url('owner = currentUser() ORDER BY modified DESC'));
    await reader.getByRole('link', { name: 'Ordinary profile specification', exact: true }).waitFor();
    assert.equal(await reader.getByRole('link', { name: 'Ordinary visible parent specification', exact: true }).count(), 0);
  } catch (error) { console.error('Ordinary specification page:', await reader.locator('body').innerText().catch(() => 'Page unavailable')); throw error; }
  finally { await readerContext.close(); }
  assert.deepEqual(errors, []); console.log('Specification search, offset pages, catalog/preferences, title choices, aggregate creation and retained drafts passed.');
} catch (error) {
  console.error('Parent search diagnostics', await gql('query($id:UUID!,$filter:[String!]!){content{metadata(id:$id){id name}} workOps{specs{specByMetadataId(metadataId:$id){id metadataId metadata{name}}}} search{search(query:{query:"Specification picker",filter:$filter,storageSystemName:"Admin Search Index",limit:26,offset:0}){documents{metadata{id name}}}}}', { id: parentMetadata, filter: ['_type = "metadata"'] }).catch(error => String(error)));
  console.error('Parent index jobs', await gql('{scheduler{history(source:EVENT,offset:0,limit:500){name status errorMessage definition}}}').then(data => ({
    parent: data.scheduler.history.filter(row => row.name === 'index-metadata' && row.definition?.id === parentMetadata),
    counts: Object.fromEntries([...new Set(data.scheduler.history.map(row => `${row.name}:${row.status}`))].map(key => [key, data.scheduler.history.filter(row => `${row.name}:${row.status}` === key).length])),
  })).catch(error => String(error)));
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error(await page.locator('body').innerText().catch(() => 'Page unavailable')); console.error(errors); throw error;
} finally { await owned.close(); }
