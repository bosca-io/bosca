import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const [base, mode = 'fixture', consumer = 'catalog'] = process.argv.slice(2);
assert.ok(base && ['localhost', '127.0.0.1', '[::1]'].includes(new URL(base).hostname), 'Use an isolated consumer fixture.');
const reports = resolve('build/reports/boards-browser', consumer, mode); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const ids = consumer === 'catalog' ? ['dark', 'light'] : ['light', 'purple'];
const errors = [];
const observe = page => page.on('pageerror', error => errors.push(error.message));
const waitState = (page, id, attribute, present) => page.waitForFunction(({ id, attribute, present }) => document.querySelector(`[data-example-board="${id}"]`)?.hasAttribute(attribute) === present, { id, attribute, present });
try {
  for (const width of [1920, 1024, 768, 390]) for (const id of ids) {
    const context = await owned.connection.newContext({ viewport: { width, height: 1000 }, hasTouch: width === 390 });
    const page = await context.newPage(); observe(page);
    try {
      await page.goto(base + '/boards');
      const scope = page.locator(`[data-example-board="${id}"]`), board = scope.locator('[data-bml-board]');
      const card = scope.locator('[data-board-card="proposal"]'), handle = card.getByRole('button', { name: 'Move Review the proposal', exact: true });
      const sibling = page.locator(`[data-example-board="${ids.find(value => value !== id)}"] [data-board-card="proposal"]`);
      await handle.waitFor();
      assert.deepEqual(await scope.locator('[data-board-column=todo]').evaluate(column => ({
        role: column.getAttribute('role'), display: getComputedStyle(column).display,
        direction: getComputedStyle(column).flexDirection, overflow: getComputedStyle(column.querySelector('[data-board-column-items]')).overflowY,
      })), { role: 'group', display: 'flex', direction: 'column', overflow: 'auto' });
      assert.equal(await handle.evaluate(node => {
        for (let current = node; current?.closest('[data-board-card]'); current = current.parentElement) if (getComputedStyle(current).touchAction === 'none') return true;
        return false;
      }), true, 'The move handle must opt out of native panning through its rendered ancestors.');
      assert.equal(await scope.locator('[data-board-card="fixed"]').getByRole('button', { name: 'Move Read-only reference', exact: true }).count(), 0);
      const note = card.locator('input[name=note]'); await note.fill('An unsent application note');
      await card.evaluate(element => { globalThis.keptBoardCard = element; globalThis.keptBoardNote = element.querySelector('input[name=note]'); });
      await note.focus(); await note.evaluate(input => input.setSelectionRange(2, 8));
      await board.evaluate(root => { root.scrollLeft = Math.min(50, root.scrollWidth - root.clientWidth); root.querySelector('[data-board-column=todo] [data-board-column-items]').scrollTop = 70; });
      const scrollBefore = await board.evaluate(root => ({ left: root.scrollLeft, top: root.querySelector('[data-board-column=todo] [data-board-column-items]').scrollTop }));
      await scope.locator('[data-board-refresh]').evaluate(button => button.click());
      await scope.locator('[data-board-refresh-count]').getByText('Refreshes: 1', { exact: true }).waitFor();
      assert.equal(await card.evaluate(element => element === globalThis.keptBoardCard && element.querySelector('input[name=note]') === globalThis.keptBoardNote), true);
      assert.equal(await note.inputValue(), 'An unsent application note');
      assert.deepEqual(await note.evaluate(input => [input.selectionStart, input.selectionEnd, document.activeElement === input]), [2, 8, true]);
      assert.deepEqual(await board.evaluate(root => ({ left: root.scrollLeft, top: root.querySelector('[data-board-column=todo] [data-board-column-items]').scrollTop })), scrollBefore);

      const panel = card.getByRole('dialog', { name: 'Move Review the proposal', exact: true });
      const choose = async label => {
        await handle.focus(); await page.keyboard.press('Enter'); await panel.waitFor();
        await panel.getByRole('combobox', { name: 'Destination', exact: true }).focus(); await page.keyboard.press('Enter');
        await page.getByRole('option', { name: label, exact: true }).waitFor();
        const labels = (await page.getByRole('option', { disabled: false }).allTextContents()).map(value => value.trim());
        const index = labels.indexOf(label); assert.ok(index >= 0);
        await page.keyboard.press('Home');
        for (let step = 0; step < index; step++) await page.keyboard.press('ArrowDown');
        await page.keyboard.press('Enter');
      };
      const waitColumn = columnId => page.waitForFunction(({ id, columnId }) => document.querySelector(`[data-example-board="${id}"] [data-board-card="proposal"]`)?.closest('[data-board-column]')?.getAttribute('data-board-column') === columnId, { id, columnId });
      await choose('In progress'); await panel.getByRole('button', { name: 'Move item', exact: true }).click();
      await card.locator('p[role=status]').getByText('Moving…', { exact: true }).waitFor();
      await waitColumn('doing'); await panel.waitFor({ state: 'hidden' });
      assert.equal(await card.evaluate(element => element === globalThis.keptBoardCard), true);
      assert.equal(await note.inputValue(), 'An unsent application note');
      await page.waitForFunction(id => document.activeElement === document.querySelector(`[data-example-board="${id}"] [data-board-card="proposal"] button[data-bml-ref=moveHandle]`), id);
      assert.equal(await sibling.evaluate(element => element.closest('[data-board-column]').getAttribute('data-board-column')), 'todo');

      await scope.locator('input[data-board-reject]').check(); await waitState(page, id, 'data-example-reject', true);
      await choose('Review'); await panel.getByRole('button', { name: 'Move item', exact: true }).click();
      await panel.getByRole('alert').filter({ hasText: /.+/ }).waitFor();
      assert.equal(await card.evaluate(element => element.closest('[data-board-column]').getAttribute('data-board-column')), 'doing');
      assert.equal(await panel.locator('select[name=destination]').inputValue(), 'review');
      assert.equal(await note.inputValue(), 'An unsent application note');
      await page.keyboard.press('Escape'); await panel.waitFor({ state: 'hidden' });
      await scope.locator('input[data-board-reject]').uncheck(); await waitState(page, id, 'data-example-reject', false);
      await choose('Review'); await panel.getByRole('button', { name: 'Move item', exact: true }).click(); await waitColumn('review'); await panel.waitFor({ state: 'hidden' });

      if (width === 1920 || width === 390) {
        await card.scrollIntoViewIfNeeded();
        await board.evaluate(root => { const column = root.querySelector('[data-board-column=review]'); root.scrollLeft += column.getBoundingClientRect().left - root.getBoundingClientRect().left; });
        const start = await handle.boundingBox(), root = await board.boundingBox(); assert.ok(start && root);
        const end = await scope.locator('[data-board-column=done]').evaluate((column, rootBox) => {
          const box = column.getBoundingClientRect(), left = Math.max(box.left, rootBox.x), right = Math.min(box.right, rootBox.x + rootBox.width);
          return { x: right - Math.min(20, (right - left) / 2), y: box.top + 80 };
        }, root);
        if (width === 1920) {
          await page.mouse.move(start.x + start.width / 2, start.y + start.height / 2); await page.mouse.down();
          await page.mouse.move(end.x, end.y, { steps: 6 });
          await page.keyboard.press('Escape'); await page.mouse.up();
          await card.getByRole('status').getByText('Move canceled.', { exact: true }).waitFor();
          assert.equal(await card.evaluate(element => element.closest('[data-board-column]').getAttribute('data-board-column')), 'review');
          const next = await handle.boundingBox();
          await page.mouse.move(next.x + next.width / 2, next.y + next.height / 2); await page.mouse.down(); await page.mouse.move(end.x, end.y, { steps: 6 }); await page.mouse.up();
        } else {
          await page.evaluate(({ start, end }) => {
            globalThis.boardTouchDebug = { start, end, events: [], hit: document.elementFromPoint(start.x + start.width / 2, start.y + start.height / 2)?.outerHTML };
            for (const name of ['pointerdown', 'pointermove', 'pointerup', 'pointercancel', 'lostpointercapture']) document.addEventListener(name, event => {
              globalThis.boardTouchDebug.events.push({ name, type: event.pointerType, id: event.pointerId, x: event.clientX, y: event.clientY,
                target: event.target?.tagName, touchAction: event.target instanceof Element ? getComputedStyle(event.target).touchAction : null,
                drop: document.querySelector('[data-board-drop]')?.getAttribute('data-board-drop') });
            }, true);
          }, { start, end });
          const session = await context.newCDPSession(page);
          await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: start.x + start.width / 2, y: start.y + start.height / 2 }] });
          await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: end.x, y: end.y }] });
          await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] }); await session.detach();
        }
        await waitColumn('done'); assert.equal(await note.inputValue(), 'An unsent application note');
      }

      await scope.locator('input[data-board-readonly]').check(); await waitState(page, id, 'data-example-readonly', true); await handle.waitFor({ state: 'hidden' });
      await note.fill('Readonly movement keeps content editable');
      await scope.locator('input[data-board-readonly]').uncheck(); await waitState(page, id, 'data-example-readonly', false); await handle.waitFor();
      await scope.locator('input[data-board-disabled]').check(); await waitState(page, id, 'data-example-disabled', true); assert.equal(await handle.isEnabled(), false);
      await scope.locator('input[data-board-disabled]').uncheck(); await waitState(page, id, 'data-example-disabled', false);
      await scope.screenshot({ path: resolve(reports, `${id}-${width}.png`) });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      await handle.click(); await panel.waitFor(); await panel.screenshot({ path: resolve(reports, `move-${id}-${width}.png`) });
      await page.keyboard.press('Escape'); await panel.waitFor({ state: 'hidden' });
      await scope.locator('input[data-board-visible]').uncheck(); await waitState(page, id, 'data-example-visible', false);
      assert.equal(await scope.locator('[data-bml-board]').count(), 0);
      await scope.locator('input[data-board-visible]').check(); await waitState(page, id, 'data-example-visible', true); await handle.waitFor();
      assert.equal(await card.evaluate(element => element === globalThis.keptBoardCard), false);
      assert.equal(await note.inputValue(), 'Review this week');
    } catch (error) {
      console.error('Touch diagnostics', await page.evaluate(() => globalThis.boardTouchDebug ?? null).catch(String));
      console.error(await page.locator('body').innerText().catch(String));
      await page.screenshot({ path: resolve(reports, `failure-${id}-${width}.png`), fullPage: true }).catch(() => {}); throw error;
    } finally { await context.close(); }
  }
  const native = await owned.connection.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 1000 } });
  try {
    const page = await native.newPage(); await page.goto(base + '/boards');
    assert.equal(await page.getByRole('button', { name: 'Move Review the proposal', exact: true }).count(), 0);
    assert.equal(await page.getByRole('link', { name: 'Review the proposal', exact: true }).count(), 2);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  } finally { await native.close(); }
  assert.deepEqual(errors, []);
  console.log(`${consumer} ${mode}: board keyboard/pointer/touch moves, rejection/retry, native dialogs, retained drafts/focus/scroll, independent instances and readonly/disabled/unmount checks passed.`);
} finally { await owned.close(); }
