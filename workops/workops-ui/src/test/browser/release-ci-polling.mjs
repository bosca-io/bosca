import assert from 'node:assert/strict';
import { until } from './release-ci-fixture.mjs';

export async function releaseCiPolling({ reader, base, path, scope, button, dialog, control }) {
  let reads = 0;
  let key = await scope(reader).evaluate(node => node.closest('[data-bml-island]').getAttribute('data-bml-state-key'));
  const observe = response => {
    const request = response.request();
    if (new URL(request.url()).pathname !== '/_bml/action') return;
    const body = JSON.parse(request.postData());
    if (body.stateKey === key && body.method === 'refresh') reads++;
  };
  reader.on('response', observe);
  try {
    await until('Active native CI polls', () => reads);
    await button(reader, 'Cancel run').click(); let panel = dialog('Cancel run', reader); await panel.waitFor();
    const dialogReads = reads; await reader.waitForTimeout(4100); assert.equal(reads, dialogReads, 'An open action dialog pauses polling.');
    await panel.getByRole('button', { name: 'Keep current state', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
    await until('Polling resumes after closing the dialog', () => reads, count => count > dialogReads);
    // Control the browser visibility boundary without changing the production island.
    await reader.evaluate(() => Object.defineProperty(document, 'hidden', { configurable: true, value: true }));
    const hiddenReads = reads; await reader.waitForTimeout(4100); assert.equal(reads, hiddenReads, 'A hidden document pauses polling.');
    await reader.evaluate(() => { delete document.hidden; });
    await reader.bringToFront();
    await reader.waitForFunction(() => !document.hidden, null, { timeout: 5000 });
    await until('Polling resumes when visible', () => reads, count => count > hiddenReads);
    await scope(reader).evaluate(node => node.closest('[data-bml-island]').remove());
    const removedReads = reads; await reader.waitForTimeout(4100); assert.equal(reads, removedReads, 'Removing the CI island disposes its timer.');
    await reader.goto(base + path); key = await scope(reader).evaluate(node => node.closest('[data-bml-island]').getAttribute('data-bml-state-key'));
    await control(reader, 'Cancel run');
    await until('Terminal CI stops requesting refresh', () => scope(reader).getAttribute('data-ci-active'), value => value === null);
    const terminalReads = reads; await reader.waitForTimeout(4100); assert.equal(reads, terminalReads, 'A terminal release does not keep polling.');
  } catch (error) {
    console.error('CI polling state', JSON.stringify(await scope(reader).evaluate(node => ({ hidden: document.hidden, active: node.hasAttribute('data-ci-active'), pending: node.querySelectorAll('[data-bml-action-pending]').length, dialogs: node.querySelectorAll('dialog[open]').length, text: node.innerText.slice(0, 500) })).catch(String)));
    throw error;
  } finally { reader.off('response', observe); await reader.evaluate(() => { delete document.hidden; }).catch(() => {}); }
}
