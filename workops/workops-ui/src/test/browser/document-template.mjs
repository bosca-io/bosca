import assert from 'node:assert/strict';
import { resolve } from 'node:path';

/** Exercise a real Content template through the public shared editor in both Atlas layouts. */
export async function documentTemplate({ page, context, peer, key, reports, observe }) {
  const gql = async (query, variables = {}) => {
    const response = await page.evaluate(async ({ query, variables }) => {
      const result = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json',
        Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) });
      return result.json();
    }, { query, variables });
    assert.equal(response.errors, undefined, JSON.stringify(response));
    return response.data;
  };
  const editor = page.locator('[data-workops-document] [data-document-ready]');
  const room = await editor.getAttribute('data-collaboration-room');
  const id = room.split('.')[0], version = Number(room.split('.')[1]);
  const created = await gql('mutation($metadata:MetadataInput!){content{metadata{add(metadata:$metadata,setReady:false){id version}}}}', {
    metadata: { name: 'Atlas editor template', contentType: 'application/json', languageTag: 'en',
      documentTemplate: { attributes: [], schema: 'heading block+', content: { document: { type: 'doc', content: [] } },
        containers: [
          { id: 'main', name: 'Main content', description: 'A reusable template region', containerType: 'STANDARD', workflows: [], filters: [] },
          { id: 'notes', name: 'Notes', description: 'Supporting notes', containerType: 'STANDARD', workflows: [], filters: [] },
        ] } },
  });
  const template = created.content.metadata.add;
  const assigned = await gql('mutation($id:UUID!,$version:Int!,$templateId:UUID!,$templateVersion:Int!){content{metadata{setTemplate(id:$id,version:$version,templateId:$templateId,templateVersion:$templateVersion){document{template{id version}}}}}}', {
    id, version, templateId: template.id, templateVersion: template.version,
  });
  assert.deepEqual(assigned.content.metadata.setTemplate.document.template, template);
  await gql('mutation($id:String!,$group:String!){content{metadata{addPermission(permission:{entityId:$id,groupId:$group,action:VIEW}){action}}}}', {
    id: template.id, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP,
  });
  await page.reload(); await editor.waitFor();
  assert.deepEqual(await editor.getByRole('combobox', { name: 'Wrap in container', exact: true }).locator('option').allTextContents(), ['Wrap in container…', 'Main content', 'Notes']);
  assert.equal(await editor.locator('.document-save-status').textContent(), 'Saved');
  const surface = editor.locator('.ProseMirror');
  const end = await page.evaluate(() => /Mac|iPhone|iPad/.test(navigator.platform) ? 'Meta+ArrowDown' : 'Control+End');
  const paragraphEnd = await page.evaluate(() => /Mac|iPhone|iPad/.test(navigator.platform) ? 'Control+e' : 'Control+End');
  const settledSelection = tab => tab.waitForFunction(() => {
    const surface = document.querySelector('[data-workops-document] .ProseMirror'), selection = getSelection();
    if (!surface || !selection?.anchorNode || !selection.focusNode || !surface.contains(selection.anchorNode)) return false;
    const view = surface.editor.view;
    return view.state.selection.anchor === view.posAtDOM(selection.anchorNode, selection.anchorOffset)
      && view.state.selection.head === view.posAtDOM(selection.focusNode, selection.focusOffset);
  }, null, { timeout: 5000 });
  const endOfDocument = async () => {
    const paragraph = surface.locator(':scope > p').last();
    await surface.focus();
    await paragraph.click({ position: { x: 8, y: 8 } });
    await settledSelection(page);
    await page.keyboard.press(paragraphEnd);
  };
  await endOfDocument(); await page.keyboard.press('Enter');
  await page.keyboard.insertText('Template region body');
  await settledSelection(page);
  await editor.getByRole('combobox', { name: 'Wrap in container', exact: true }).selectOption('main');
  const container = editor.locator('[data-document-container=main]');
  await container.getByText('Main content', { exact: true }).waitFor();
  assert.equal(await container.locator('strong').getAttribute('title'), 'A reusable template region');
  const value = () => editor.locator('textarea').inputValue().then(JSON.parse);
  const wrapped = await value();
  assert.equal(wrapped.content[0].content[0].text, 'Collaborative task document');
  assert.equal(wrapped.content.find(node => node.type === 'container').attrs.name, 'main');
  await container.getByRole('button', { name: 'Collapse Main content container', exact: true }).click();
  assert.equal(await container.locator('.document-container-content').isVisible(), false);
  assert.deepEqual(await value(), wrapped, 'Collapse is local presentation');
  await container.getByRole('button', { name: 'Expand Main content container', exact: true }).click();
  await container.locator('p').click();
  await editor.getByRole('button', { name: 'Unwrap container', exact: true }).click();
  assert.equal(await editor.locator('[data-document-container-view]').count(), 0);
  await editor.getByRole('button', { name: 'Undo', exact: true }).click();
  await container.waitFor();
  await endOfDocument();
  await page.keyboard.insertText('Notes region');
  await editor.getByRole('combobox', { name: 'Wrap in container', exact: true }).selectOption('notes');
  assert.deepEqual((await value()).content.filter(node => node.type === 'container').map(node => node.attrs.name), ['main', 'notes']);
  const notes = editor.locator('[data-document-container=notes]'); await notes.waitFor();
  await notes.getByRole('button', { name: 'Remove Notes container', exact: true }).click();
  assert.equal(await notes.count(), 0);
  await editor.getByRole('button', { name: 'Undo', exact: true }).click(); await notes.waitFor();
  await surface.focus();
  await notes.locator('p').click({ position: { x: 8, y: 8 } });
  await settledSelection(page);
  await editor.getByRole('button', { name: 'Move block up', exact: true }).click();
  assert.deepEqual((await value()).content.filter(node => node.type === 'container').map(node => node.attrs.name), ['notes', 'main']);
  await editor.getByRole('button', { name: 'Move block down', exact: true }).click();
  assert.deepEqual((await value()).content.filter(node => node.type === 'container').map(node => node.attrs.name), ['main', 'notes']);
  await notes.hover({ position: { x: 12, y: 10 } });
  const handle = editor.getByRole('button', { name: 'Drag block', exact: true }); await handle.waitFor();
  const mainBounds = await container.boundingBox(), surfaceBounds = await surface.boundingBox();
  await handle.dragTo(surface, { targetPosition: { x: 6, y: mainBounds.y - surfaceBounds.y - 3 } });
  await page.waitForFunction(() => {
    const body = JSON.parse(document.querySelector('[data-workops-document] textarea').value);
    return body.content.filter(node => node.type === 'container')[0]?.attrs.name === 'notes';
  }, null, { timeout: 5000 });
  await editor.getByRole('button', { name: 'Undo', exact: true }).click();
  assert.deepEqual((await value()).content.filter(node => node.type === 'container').map(node => node.attrs.name), ['main', 'notes']);
  await endOfDocument();
  await page.keyboard.insertText('/mermaid');
  await editor.getByRole('listbox', { name: 'Document commands', exact: true }).getByRole('option', { name: 'Mermaid diagram', exact: true }).waitFor();
  await page.keyboard.press('Enter');
  const code = editor.locator('[data-document-code]');
  await code.locator('.cm-content').click();
  await page.keyboard.insertText('graph TD; Atlas-->WorkOps;');
  await code.locator('.document-diagram svg').waitFor();
  assert.equal(await editor.locator('.document-save-status').textContent(), 'Draft');
  await code.locator('.cm-content').press('ControlOrMeta+Enter');
  const sibling = await context.newPage(); observe(sibling);
  try {
    await sibling.goto(`${peer}/workops/tasks/${key}`);
    const remote = sibling.locator('[data-workops-document] [data-document-ready]');
    await remote.locator('.document-diagram svg').waitFor();
    assert.equal(await remote.getByRole('button', { name: 'Undo', exact: true }).isDisabled(), true);
    await code.locator('.cm-content').click(); await page.keyboard.press(end);
    await page.keyboard.insertText('\nWorkOps-->Content;');
    await sibling.waitForFunction(() => document.querySelector('[data-workops-document] textarea').value.includes('WorkOps-->Content;'), null, { timeout: 5000 });
    await sibling.bringToFront(); await remote.locator('.cm-content').click(); await sibling.keyboard.press('ControlOrMeta+z');
    assert.ok((await remote.locator('textarea').inputValue()).includes('WorkOps-->Content;'), 'An editor with no local history cannot undo a peer code edit');
    await remote.locator('.ProseMirror').focus();
    await remote.locator('.ProseMirror > p').last().click({ position: { x: 8, y: 8 } });
    await settledSelection(sibling); await sibling.keyboard.insertText('/bold');
    const menu = remote.getByRole('listbox', { name: 'Document commands', exact: true });
    await menu.getByRole('option', { name: 'Bold', exact: true }).waitFor();
    await page.bringToFront(); await code.locator('.cm-content').click(); await page.keyboard.press(end);
    await page.keyboard.insertText('\nContent-->Template;');
    await sibling.waitForFunction(() => document.querySelector('[data-workops-document] textarea').value.includes('Content-->Template;'), null, { timeout: 5000 });
    assert.equal(await menu.isVisible(), true, 'A peer edit retains the active slash menu and its current query');
    await sibling.bringToFront(); await remote.locator('.ProseMirror').press('Enter');
    assert.equal(await menu.isVisible(), false);
  } finally { await sibling.close(); }
  await page.bringToFront(); await code.locator('.cm-content').click(); await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.insertText('graph TD; [broken');
  await code.locator('.document-diagram-error').waitFor();
  assert.equal(await code.locator('.document-diagram svg').count(), 0);
  await code.locator('.cm-content').click(); await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.insertText('graph TD; Atlas-->WorkOps; WorkOps-->Content;');
  await code.locator('.document-diagram svg').waitFor();
  await code.locator('.cm-content').press('ControlOrMeta+Enter');
  await editor.getByRole('button', { name: 'Table', exact: true }).click();
  const table = editor.locator('table'); await table.waitFor();
  await table.locator('th').first().scrollIntoViewIfNeeded();
  const bounds = await table.locator('th').first().boundingBox(); assert.ok(bounds);
  const x = bounds.x + bounds.width - 1, y = bounds.y + bounds.height / 2;
  await page.mouse.move(x, y); await page.mouse.down(); await page.mouse.move(x + 45, y, { steps: 8 }); await page.mouse.up();
  const widths = (await value()).content.find(node => node.type === 'table').content[0].content[0].attrs.colwidth;
  assert.ok(widths?.[0] > 0, 'Dragging a table edge stores column widths');
  await page.getByRole('button', { name: 'Save document', exact: true }).click();
  await page.getByText('Document saved.', { exact: true }).waitFor();
  await page.waitForFunction(() => !document.querySelector('[data-workops-document] [data-document-dirty]'));
  const persisted = await gql('query($id:UUID!){content{metadata(id:$id){document{content template{id version}}}}}', { id });
  const document = persisted.content.metadata.document;
  assert.deepEqual(document.template, template);
  const savedTable = document.content.document.content.find(node => node.type === 'table');
  assert.deepEqual(savedTable.content[0].content[0].attrs.colwidth, widths);
  assert.deepEqual(savedTable.content[1].content[0].attrs.colwidth, widths, 'Default body cells also retain widths');
  await page.reload(); await editor.waitFor();
  await editor.locator('[data-document-container=main] strong').filter({ hasText: /^Main content$/ }).waitFor();
  await editor.locator('.document-diagram svg').waitFor();
  assert.deepEqual((await value()).content.find(node => node.type === 'table').content[0].content[0].attrs.colwidth, widths);
  await gql(`mutation($id: UUID!, $version: Int!, $containers: [DocumentTemplateContainerInput!]!) {
    content {
      metadata {
        documentTemplate(metadataId: $id, metadataVersion: $version) {
          setContainers(containers: $containers) { id }
        }
      }
    }
  }`, {
    id: template.id, version: template.version, containers: [{ id: 'notes', name: 'Notes', description: 'Supporting notes', containerType: 'STANDARD', workflows: [], filters: [] }],
  });
  await page.reload(); await editor.waitFor();
  await editor.locator('[data-document-container=main] strong').filter({ hasText: /^Content$/ }).waitFor();
  assert.ok((await value()).content.some(node => node.type === 'container' && node.attrs.name === 'main'), 'A removed template definition retains its stored region');
  await page.screenshot({ path: resolve(reports, 'template-document.png'), fullPage: true });
}
