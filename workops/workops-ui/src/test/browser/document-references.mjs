import assert from 'node:assert/strict';
import { resolve } from 'node:path';

/** Reference authoring uses separate scratch records and the same APIs available to the caller. */
export async function documentReferences({ page, context, connection, base, peer, key, reports, observe }) {
  const gql = async (query, variables = {}) => {
    const response = await page.evaluate(async ({ query, variables }) => {
      const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json',
        Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) });
      return response.json();
    }, { query, variables });
    assert.equal(response.errors, undefined, JSON.stringify(response)); return response.data;
  };
  const add = async metadata => (await gql('mutation($metadata:MetadataInput!){content{metadata{add(metadata:$metadata,setReady:false){id version}}}}', { metadata })).content.metadata.add;
  const metadata = await add({ name: 'Atlas reference resource', contentType: 'bosca/v-document', languageTag: 'en',
    document: { title: 'Atlas reference resource', content: { document: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Referenced resource body.' }] }] } } } });
  const bible = await add({ name: 'Atlas reference Bible', contentType: 'bosca/v-bible', languageTag: 'en' });
  const verses = [1, 2].flatMap(verse => [
    { _: 'vs', reference: { usfm: `GEN.1.${verse}` } },
    { _: 'cc', type: 'DIV', style: { _: 'sr', id: 'it' }, components: [{ _: 't', text: `Atlas fixture verse ${verse}.`, style: null }] },
    { _: 've' },
  ]);
  assert.equal((await gql('mutation($id:UUID!,$bible:BibleInput!){content{metadata{setMetadataBible(id:$id,version:1,bible:$bible)}}}', {
    id: bible.id, bible: { systemId: 'atlas-reference-fixture', variant: 'reader', defaultVariant: true,
      name: 'Atlas reference Bible', nameLocal: 'Atlas reference Bible', abbreviation: 'AR', abbreviationLocal: 'AR', description: 'Synthetic local acceptance fixture',
      languages: [{ iso: 'eng', name: 'English', nameLocal: 'English', script: 'Latin', scriptCode: 'Latn', scriptDirection: 'LTR' }],
      styles: [{ id: 'it', reference: false, fontWeight: 'normal' }],
      books: [{ nameLong: 'Genesis', nameShort: 'Genesis', abbreviation: 'Gen', reference: { usfm: 'GEN' },
        chapters: [{ reference: { usfm: 'GEN.1' }, component: { _: 'cc', type: 'PARAGRAPH', style: null, components: verses } }] }],
    },
  })).content.metadata.setMetadataBible, true);
  const template = await add({ name: 'Atlas reference template', contentType: 'application/json', languageTag: 'en',
    documentTemplate: { attributes: [], schema: 'heading block+', content: { document: { type: 'doc' } }, containers: [
      { id: 'resource', name: 'Related content', description: 'Reference resource', containerType: 'METADATA', workflows: [], filters: ['_type != "profile"'] },
      { id: 'scripture', name: 'Scripture', description: 'Reference passages', containerType: 'BIBLE', workflows: [], filters: [] },
    ] } });
  const task = (await gql('mutation($project:UUID!){workOps{tasks{create(input:{projectId:$project,summary:"Reference authoring acceptance"}){id key version}}}}', {
    project: process.env.ATLAS_WORKOPS_TEST_PROJECT,
  })).workOps.tasks.create;
  const document = (await gql('mutation($id:UUID!,$version:Long!){workOps{tasks{createDocument(id:$id,expectedVersion:$version){metadataId}}}}', {
    id: task.id, version: task.version,
  })).workOps.tasks.createDocument;
  const documentId = document.metadataId;
  await gql('mutation($id:UUID!,$template:UUID!){content{metadata{setTemplate(id:$id,version:1,templateId:$template,templateVersion:1){id}}}}', { id: documentId, template: template.id });
  const body = { type: 'doc', content: [
    { type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: 'Reference authoring acceptance' }] },
    { type: 'container', attrs: { name: 'resource', metadataId: metadata.id, version: 1, renderer: 'card' } },
    { type: 'container', attrs: { name: 'scripture', metadataId: bible.id, references: ['GEN.1.1', ''] } },
    { type: 'bible', attrs: { metadataIds: [bible.id], usfm: ['GEN.1.2'], metadataId: bible.id, references: ['GEN.1.1'] } },
    { type: 'paragraph', content: [{ type: 'text', text: 'Peer notes.' }] },
  ] };
  await gql('mutation($id:UUID!,$document:DocumentInput!){content{metadata{setMetadataDocument(id:$id,version:1,document:$document)}}}', {
    id: documentId, document: { title: 'Reference authoring acceptance', content: { document: body } },
  });
  await gql('mutation{storageSystems{reindex(storageName:"Admin Search Index",deleteFirst:false,metadata:true,collections:false,profiles:false)}}');
  await page.goto(`${base}/workops/tasks/${task.key}`);
  const editor = page.locator('[data-workops-document] [data-document-ready]'); await editor.waitFor();
  const resource = editor.locator('[data-document-container=resource]'), scripture = editor.locator('[data-document-container=scripture]');
  await resource.getByRole('button', { name: 'Atlas reference resource', exact: true }).waitFor();
  await scripture.getByText('Atlas fixture verse 1.', { exact: true }).waitFor();
  assert.equal(await scripture.getByText('Atlas fixture verse 2.', { exact: true }).count(), 0);
  await editor.locator('[data-document-bible]').getByText('Atlas fixture verse 2.', { exact: true }).waitFor();
  assert.equal(await scripture.locator('.document-scripture-attribution').count(), 0, 'Local imports supply no attribution; the UI does not invent it');
  assert.equal(await scripture.locator('.scripture-inline').evaluate(element => getComputedStyle(element).fontStyle), 'italic');
  const value = () => editor.locator('textarea').inputValue().then(JSON.parse);
  assert.deepEqual((await value()).content.find(node => node.attrs?.name === 'scripture').attrs.references, ['GEN.1.1']);
  await resource.getByRole('button', { name: 'Clear metadata reference', exact: true }).click();
  assert.equal((await value()).content.find(node => node.attrs?.name === 'resource').attrs.metadataId, null);
  await editor.getByRole('button', { name: 'Undo', exact: true }).click();
  await resource.getByRole('button', { name: 'Atlas reference resource', exact: true }).waitFor();
  assert.equal((await value()).content.find(node => node.attrs?.name === 'resource').attrs.renderer, 'card');
  await editor.getByRole('button', { name: 'Redo', exact: true }).click();
  await resource.getByRole('searchbox', { name: 'Find metadata', exact: true }).fill('Atlas reference resource');
  await resource.getByRole('combobox', { name: 'Reference results', exact: true }).locator(`option[value="${metadata.id}"]`).waitFor({ state: 'attached' });
  await resource.getByRole('combobox', { name: 'Reference results', exact: true }).selectOption(metadata.id);
  await resource.getByRole('button', { name: 'Atlas reference resource', exact: true }).waitFor();
  const peerPage = await context.newPage(); observe(peerPage);
  try {
    await peerPage.goto(`${peer}/workops/tasks/${task.key}`); await peerPage.locator('[data-document-ready]').waitFor();
    await scripture.getByRole('searchbox', { name: 'Find scripture', exact: true }).fill('Genesis 1:2');
    await scripture.getByRole('combobox', { name: 'Reference results', exact: true }).locator('option[value="GEN.1.2"]').waitFor({ state: 'attached' });
    const peerParagraph = peerPage.locator('[data-workops-document] .ProseMirror > p').last();
    await peerParagraph.click({ position: { x: 8, y: 8 } });
    await peerPage.keyboard.insertText('Peer update ');
    await editor.locator('.ProseMirror > p').filter({ hasText: 'Peer update ' }).waitFor({ timeout: 5000 });
    assert.equal(await scripture.getByRole('searchbox', { name: 'Find scripture', exact: true }).inputValue(), 'Genesis 1:2');
    await scripture.getByRole('combobox', { name: 'Reference results', exact: true }).selectOption('GEN.1.2');
    await peerPage.locator('[data-document-container=scripture] .document-scripture-reference').nth(1).waitFor({ timeout: 5000 });
    await scripture.getByRole('button', { name: 'Remove GEN.1.1 reference', exact: true }).click();
    assert.deepEqual((await value()).content.find(node => node.attrs?.name === 'scripture').attrs.references, ['GEN.1.2']);
    await editor.getByRole('button', { name: 'Undo', exact: true }).click();
    await scripture.getByText('Atlas fixture verse 1.', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Save document', exact: true }).click();
    await page.locator('[data-workops-document]').getByText('Document saved.', { exact: true }).waitFor();
    const stored = (await gql('query($id:UUID!){content{metadata(id:$id){document{content template{id version}}}}}', { id: documentId })).content.metadata.document;
    assert.deepEqual(stored.template, template);
    const nodes = stored.content.document.content;
    assert.deepEqual(nodes.find(node => node.attrs?.name === 'scripture').attrs.references, ['GEN.1.1', 'GEN.1.2']);
    assert.equal(nodes.find(node => node.attrs?.name === 'resource').attrs.version, 1);
    assert.deepEqual(nodes.find(node => node.type === 'bible').attrs.usfm, ['GEN.1.2']);
    assert.deepEqual(nodes.find(node => node.type === 'bible').attrs.references, ['GEN.1.1']);
    await page.reload(); await editor.waitFor(); await scripture.getByText('Atlas fixture verse 1.', { exact: true }).waitFor();
    for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
      await page.setViewportSize({ width, height: 900 });
      await page.evaluate(value => { document.documentElement.dataset.appearance = value; document.body.dataset.theme = value; }, appearance);
      await scripture.scrollIntoViewIfNeeded();
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      await scripture.screenshot({ path: resolve(reports, `references-${appearance}-${width}.png`) });
    }
  } finally { await peerPage.close(); }
  await page.setViewportSize({ width: 1280, height: 900 });
  const group = process.env.ATLAS_WORKOPS_TEST_READER_GROUP;
  await gql('mutation($id:UUID!,$group:UUID!){workOps{tasks{addPermission(id:$id,groupId:$group,action:VIEW)}}}', { id: task.id, group });
  const permission = async (action, id) => gql(`mutation($id:String!,$group:String!){content{metadata{${action}Permission(permission:{entityId:$id,groupId:$group,action:VIEW}){action}}}}`, { id, group });
  await permission('add', documentId); await permission('add', template.id);
  const readerContext = await connection.newContext(), reader = await readerContext.newPage();
  try {
    await reader.goto(`${base}/workops/tasks/${task.key}`);
    await reader.locator('input[name=identifier]').fill(process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER);
    await reader.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD);
    await reader.locator('button[type=submit]').click();
    const readonly = reader.locator('[data-workops-document] [data-document-ready]'); await readonly.waitFor();
    await readonly.getByText('This reference is unavailable or you do not have access to it.', { exact: true }).waitFor();
    await readonly.locator('[data-document-container=scripture]').getByText('Could not load scripture. The stored references are retained. Reload to retry.', { exact: true }).waitFor();
    assert.equal(await readonly.locator('.document-reference-controls:visible').count(), 0);
    await permission('add', metadata.id); await permission('add', bible.id); await reader.reload();
    await readonly.getByRole('button', { name: 'Atlas reference resource', exact: true }).waitFor();
    await readonly.locator('[data-document-container=scripture]').getByText('Atlas fixture verse 1.', { exact: true }).waitFor();
    assert.equal(await readonly.locator('[data-reference-edit]:visible').count(), 0);
    assert.equal(await readonly.locator('.ProseMirror').getAttribute('contenteditable'), 'false');
    await readonly.getByRole('button', { name: 'Atlas reference resource', exact: true }).click();
    await reader.waitForURL(url => url.pathname === `/workops/references/metadata/${metadata.id}`);
    await reader.getByText('Referenced resource body.', { exact: true }).waitFor();
    assert.ok(new URL(reader.url()).searchParams.get('returnTo').includes(task.key));
  } finally { await readerContext.close(); }
  await page.goto(`${base}/workops/tasks/${key}`); await page.locator('[data-workops-document] [data-document-ready]').waitFor();
}
