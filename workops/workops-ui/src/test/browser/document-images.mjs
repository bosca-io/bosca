import assert from 'node:assert/strict';
import { resolve } from 'node:path';

/** Real Content bytes and isolated scratch records exercise both imported Atlas layouts. */
export async function documentImages({ page, context, connection, base, peer, key, reports, observe }) {
  const gql = async (query, variables = {}) => {
    const response = await page.evaluate(async ({ query, variables }) => {
      const result = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json',
        Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) });
      return result.json();
    }, { query, variables });
    assert.equal(response.errors, undefined, JSON.stringify(response)); return response.data;
  };
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="128" height="72"><style>circle{fill:#4f46e5}</style><rect width="128" height="72" fill="#c7d2fe"/><circle cx="64" cy="36" r="24"/><script>document.documentElement.setAttribute("data-script-ran","yes")</script></svg>';
  const task = (await gql('mutation($project:UUID!){workOps{tasks{create(input:{projectId:$project,summary:"Image authoring acceptance"}){id key version}}}}', {
    project: process.env.ATLAS_WORKOPS_TEST_PROJECT,
  })).workOps.tasks.create;
  const documentId = (await gql('mutation($id:UUID!,$version:Long!){workOps{tasks{createDocument(id:$id,expectedVersion:$version){metadataId}}}}', {
    id: task.id, version: task.version,
  })).workOps.tasks.createDocument.metadataId;
  await gql('mutation($id:UUID!,$document:DocumentInput!){content{metadata{setMetadataDocument(id:$id,version:1,document:$document)}}}', {
    id: documentId, document: { title: 'Image authoring acceptance', content: { document: { type: 'doc', content: [
      { type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: 'Image authoring acceptance' }] },
      { type: 'paragraph', content: [{ type: 'text', text: 'Peer notes.' }] },
    ] } } },
  });
  // Create an actual second page of uploaded images through the same Content APIs as Studio.
  const library = [];
  for (let index = 0; index < 26; index++) {
    const metadata = (await gql('mutation($metadata:MetadataInput!){content{metadata{add(metadata:$metadata,setReady:false){id}}}}', {
      metadata: { name: `Atlas image library ${String(index).padStart(2, '0')}`, contentType: 'image/svg+xml', languageTag: 'und', contentLength: Buffer.byteLength(svg) },
    })).content.metadata.add;
    const status = await page.evaluate(async ({ id, svg }) => {
      const body = new FormData(); body.append('file', new File([svg], 'library.svg', { type: 'image/svg+xml' }));
      const response = await fetch(`/api/v1/content/metadata/upload?id=${id}`, { method: 'POST', headers: { Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body });
      return response.status;
    }, { id: metadata.id, svg });
    assert.equal(status, 201);
    await gql('mutation($id:UUID!){content{metadata{setMetadataReady(id:$id)}}}', { id: metadata.id }); library.push(metadata.id);
  }
  await gql('mutation{storageSystems{reindex(storageName:"Admin Search Index",deleteFirst:false,metadata:true,collections:false,profiles:false)}}');
  await page.goto(`${base}/workops/tasks/${task.key}`);
  const editor = page.locator('[data-workops-document] [data-document-ready]'); await editor.waitFor();
  const picker = editor.locator('[data-image-picker-ready]'); await picker.waitFor({ state: 'attached' });
  const panel = picker.getByRole('dialog', { name: 'Choose image', exact: true });
  const imageNodes = () => editor.locator('textarea').inputValue().then(JSON.parse).then(body => body.content.filter(node => node.type === 'image'));
  const startParagraph = async tab => {
    const surface = tab.locator('[data-workops-document] .ProseMirror');
    await surface.focus(); await surface.locator(':scope > p').last().click({ position: { x: 8, y: 8 } });
    await tab.waitForFunction(() => {
      const surface = document.querySelector('[data-workops-document] .ProseMirror'), selection = getSelection();
      if (!surface || !selection?.anchorNode || !selection.focusNode || !surface.contains(selection.anchorNode)) return false;
      const view = surface.editor.view;
      return view.state.selection.anchor === view.posAtDOM(selection.anchorNode, selection.anchorOffset)
        && view.state.selection.head === view.posAtDOM(selection.focusNode, selection.focusOffset);
    });
    await tab.keyboard.press(await tab.evaluate(() => /Mac|iPhone|iPad/.test(navigator.platform) ? 'Control+a' : 'Home'));
    await tab.waitForFunction(() => {
      const selection = document.querySelector('[data-workops-document] .ProseMirror')?.editor?.view.state.selection;
      return selection?.$from.parent.type.name === 'paragraph' && selection.$from.parentOffset === 0;
    });
  };
  const open = async () => { await editor.getByRole('button', { name: 'Image', exact: true }).click(); await panel.waitFor(); };
  await startParagraph(page);
  await open();
  await panel.locator('input[type=file]').setInputFiles({ name: 'atlas-upload.svg', mimeType: 'image/svg+xml', buffer: Buffer.from(svg) });
  await panel.getByRole('button', { name: 'Upload image', exact: true }).click();
  await panel.getByText('Image uploaded.', { exact: true }).waitFor();
  assert.equal((await imageNodes()).length, 0, 'Uploading alone does not insert into the document');
  await panel.getByRole('textbox', { name: 'Image description', exact: true }).fill('A violet circle in a light rectangle');
  await panel.getByRole('textbox', { name: 'Image title', exact: true }).fill('Uploaded illustration');
  const peerPage = await context.newPage(); observe(peerPage);
  try {
    await peerPage.goto(`${peer}/workops/tasks/${task.key}`); await peerPage.locator('[data-document-ready]').waitFor();
    await startParagraph(peerPage); await peerPage.keyboard.insertText('Collaborator update ');
    await editor.locator('.ProseMirror > p').filter({ hasText: 'Collaborator update ' }).waitFor({ timeout: 5000 });
    assert.equal(await panel.getByRole('textbox', { name: 'Image description', exact: true }).inputValue(), 'A violet circle in a light rectangle');
    await panel.getByRole('button', { name: 'Insert image', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
    await peerPage.locator('[data-document-image] img').waitFor({ timeout: 5000 });
    await page.waitForFunction(() => document.querySelector('[data-workops-document] [data-document-image] img')?.naturalWidth === 128);
    const uploaded = (await imageNodes())[0]; assert.ok(uploaded.attrs.metadataId);
    assert.equal(uploaded.attrs.src, `/content/image/${uploaded.attrs.metadataId}`); assert.equal(uploaded.attrs.title, 'Uploaded illustration');
    assert.equal(uploaded.attrs.alt, 'A violet circle in a light rectangle');
    const body = JSON.parse(await editor.locator('textarea').inputValue());
    const imageIndex = body.content.findIndex(node => node.type === 'image');
    assert.ok(body.content.slice(0, imageIndex).some(node => node.type === 'paragraph' && node.content?.some(text => text.text?.includes('Collaborator update '))), 'The insertion follows the peer text added at the saved anchor');
    assert.ok(body.content.slice(imageIndex + 1).some(node => node.type === 'paragraph' && node.content?.some(text => text.text?.includes('Peer notes.'))), 'The insertion keeps its original position before the existing paragraph text');
    assert.equal(await page.evaluate(async src => (await fetch(src)).text(), uploaded.attrs.src), svg, 'The same-origin image route returns the uploaded bytes');
    const rawImage = await context.newPage();
    try {
      const response = await rawImage.goto(base + uploaded.attrs.src);
      assert.match(response.headers()['content-security-policy'], /sandbox/);
      assert.equal(response.headers()['x-content-type-options'], 'nosniff');
      assert.equal(await rawImage.locator('svg').getAttribute('data-script-ran'), null, 'Opening a raw SVG does not execute its script on the Atlas origin');
      assert.equal(await rawImage.locator('circle').evaluate(element => getComputedStyle(element).fill), 'rgb(79, 70, 229)', 'SVG inline presentation remains available');
    } finally { await rawImage.close(); }
    await editor.getByRole('button', { name: 'Undo', exact: true }).click(); assert.equal((await imageNodes()).length, 0);
    assert.ok((await editor.locator('.ProseMirror').innerText()).includes('Collaborator update '), 'Undo leaves the peer edit intact');
    await editor.getByRole('button', { name: 'Redo', exact: true }).click();
    await editor.locator('[data-document-image] img').click(); await open();
    assert.equal(await panel.getByRole('textbox', { name: 'Image description', exact: true }).inputValue(), uploaded.attrs.alt);
    assert.equal(await panel.getByRole('textbox', { name: 'Image title', exact: true }).inputValue(), uploaded.attrs.title);
    await panel.getByRole('textbox', { name: 'Image title', exact: true }).fill('Updated illustration');
    await panel.getByRole('button', { name: 'Insert image', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
    assert.equal((await imageNodes()).length, 1); assert.equal((await imageNodes())[0].attrs.metadataId, uploaded.attrs.metadataId);
    assert.equal((await imageNodes())[0].attrs.title, 'Updated illustration');
    await startParagraph(page);
    await open(); await panel.getByRole('combobox', { name: 'Content library', exact: true }).click();
    await panel.getByRole('combobox', { name: 'Search content library', exact: true }).fill('Atlas image library');
    await panel.getByRole('option', { name: /^Atlas image library/ }).first().waitFor();
    await panel.getByRole('button', { name: 'Next', exact: true }).click();
    await panel.getByRole('option', { name: /^Atlas image library/ }).first().waitFor();
    assert.equal(await panel.getByRole('option', { name: /^Atlas image library/ }).count(), 1);
    await panel.getByRole('option', { name: /^Atlas image library/ }).first().click();
    await panel.getByRole('button', { name: 'Insert image', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
    const storedImages = await imageNodes(); assert.equal(storedImages.length, 2); assert.ok(library.includes(storedImages[1].attrs.metadataId) || library.includes(storedImages[0].attrs.metadataId));
    await page.getByRole('button', { name: 'Save document', exact: true }).click();
    await page.locator('[data-workops-document]').getByText('Document saved.', { exact: true }).waitFor();
    const saved = (await gql('query($id:UUID!){content{metadata(id:$id){document{content}}}}', { id: documentId })).content.metadata.document.content.document;
    assert.deepEqual(saved.content.filter(node => node.type === 'image'), storedImages.map(image => {
      const attrs = { ...image.attrs }; if (attrs.class === null) delete attrs.class;
      return { ...image, attrs };
    }), 'Content omits the default null class while preserving every supplied image attribute');
    await page.reload(); await editor.waitFor();
    await page.waitForFunction(() => [...document.querySelectorAll('[data-workops-document] [data-document-image] img')].filter(image => image.naturalWidth === 128).length === 2);
    for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
      await page.setViewportSize({ width, height: 900 });
      await page.evaluate(value => { document.documentElement.dataset.appearance = value; document.body.dataset.theme = value; }, appearance);
      await open(); await panel.getByRole('combobox', { name: 'Content library', exact: true }).click();
      await panel.getByRole('option', { name: /^Atlas image library/ }).first().waitFor();
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      await panel.screenshot({ path: resolve(reports, `images-${appearance}-${width}.png`) });
      await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
      assert.deepEqual(await imageNodes(), storedImages, 'Cancelling retains the document');
    }
    await page.setViewportSize({ width: 1280, height: 900 });
    await editor.locator('[data-document-image] img').first().click(); await open();
    const removedId = storedImages[0].attrs.metadataId;
    await peerPage.locator(`[data-document-image] img[src="/content/image/${removedId}"]`).click();
    await peerPage.keyboard.press('Backspace');
    await page.waitForFunction(() => document.querySelectorAll('[data-workops-document] [data-document-image]').length === 1);
    await panel.getByRole('button', { name: 'Insert image', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
    await editor.getByText('The selected image was removed or replaced. Choose an insertion point and try again.', { exact: true }).waitFor();
    assert.equal((await imageNodes()).length, 1); assert.notEqual((await imageNodes())[0].attrs.metadataId, removedId);
    await peerPage.locator('[data-document-ready]').getByRole('button', { name: 'Undo', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('[data-workops-document] [data-document-image]').length === 2);
    assert.deepEqual(await imageNodes(), storedImages, 'The delayed action did not overwrite the surviving image');
    const group = process.env.ATLAS_WORKOPS_TEST_READER_GROUP;
    await gql('mutation($id:UUID!,$group:UUID!){workOps{tasks{addPermission(id:$id,groupId:$group,action:VIEW)}}}', { id: task.id, group });
    const permission = async (id, action) => gql(`mutation($id:String!,$group:String!){content{metadata{${action}Permission(permission:{entityId:$id,groupId:$group,action:VIEW}){action}}}}`, { id, group });
    await permission(documentId, 'add');
    const readerContext = await connection.newContext(), reader = await readerContext.newPage();
    try {
      await reader.goto(`${base}/workops/tasks/${task.key}`);
      await reader.locator('input[name=identifier]').fill(process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER);
      await reader.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD); await reader.locator('button[type=submit]').click();
      const readonly = reader.locator('[data-workops-document] [data-document-ready]'); await readonly.waitFor();
      await readonly.getByText('Image unavailable.', { exact: true }).first().waitFor();
      assert.equal(await readonly.locator('[data-document-image] img[src]').count(), 0, 'Private images do not request a stored stale URL');
      assert.equal(await readonly.getByRole('button', { name: 'Image', exact: true }).count(), 0);
      for (const image of storedImages) await permission(image.attrs.metadataId, 'add');
      await reader.reload(); await readonly.waitFor();
      await reader.waitForFunction(() => [...document.querySelectorAll('[data-document-image] img')].filter(image => image.naturalWidth === 128).length === 2);
      assert.equal(await readonly.locator('.ProseMirror').getAttribute('contenteditable'), 'false');
      for (const image of storedImages) await permission(image.attrs.metadataId, 'delete');
      await reader.reload(); await readonly.getByText('Image unavailable.', { exact: true }).first().waitFor();
      assert.equal(await readonly.locator('[data-document-image] img[src]').count(), 0);
      const missing = '00000000-0000-4000-8000-000000000999';
      const reference = (await gql('mutation($metadata:MetadataInput!){content{metadata{add(metadata:$metadata,setReady:false){id}}}}', {
        metadata: { name: 'Missing image reference', contentType: 'bosca/v-document', languageTag: 'en', document: {
          title: 'Missing image reference', content: { document: { type: 'doc', content: [{ type: 'image', attrs: {
            metadataId: missing, src: '/a-stale-stored-image', alt: 'Retained description', title: 'Missing image',
          } }] } },
        } },
      })).content.metadata.add;
      await permission(reference.id, 'add');
      await reader.goto(`${base}/workops/references/metadata/${reference.id}?returnTo=${encodeURIComponent('/workops/tasks/' + task.key)}`);
      await reader.locator('[data-document-ready]').getByText('Image unavailable.', { exact: true }).waitFor();
      assert.equal(await reader.locator('[data-document-image] img[src]').count(), 0);
      const retained = JSON.parse(await reader.locator('[data-document-ready] textarea').inputValue());
      assert.equal(retained.content[0].attrs.metadataId, missing); assert.equal(retained.content[0].attrs.alt, 'Retained description');
    } finally { await readerContext.close(); }
  } finally { await peerPage.close(); }
  await page.goto(`${base}/workops/tasks/${key}`); await page.locator('[data-workops-document] [data-document-ready]').waitFor();
}
