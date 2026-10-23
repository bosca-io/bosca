import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { assertContrast, inspectContrast } from './contrast-review.mjs';

/** Rechecks representative clipping, top-layer, short-text and symbol findings while visible. */
export async function checkContrastVisibility({ navigate, evaluate, until: poll, call, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`), reports = [];
  const axe = await readFile(new URL('../../../node_modules/axe-core/axe.min.js', import.meta.url), 'utf8');
  const inspect = selector => evaluate(`(${inspectContrast.toString()})(${JSON.stringify(selector)})`);
  const scan = selector => evaluate(`axe.run(document.querySelector(${JSON.stringify(selector)}),{runOnly:['color-contrast']}).then(result=>({
    violations:result.violations,incomplete:result.incomplete,passes:result.passes.map(rule=>rule.id)}))`);
  try {
    for (const width of [1440, 390]) for (const appearance of ['dark', 'light']) {
      await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
      const open = async path => {
        await call('Network.clearBrowserCookies'); await navigate(path); await evaluate('document.fonts.ready');
        if (appearance === 'light') await evaluate(`if(!document.querySelector('[data-catalog-light]').checked)document.querySelector('[data-catalog-light]').click()`);
        await evaluate(axe);
      };
      const measure = async (path, selector, state, scroll = true) => {
        await until(`document.querySelector(${JSON.stringify(selector)})`);
        const before = await inspect(selector);
        if (scroll) await evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'center',inline:'nearest'})`);
        const visible = await inspect(selector), result = await scan(selector);
        const row = { path, width, appearance, state, selector, before, visible, ...result }; reports.push(row);
        assertContrast(visible); assert.ok(visible.visibleText, `No unoccluded text: ${JSON.stringify(row)}`);
        assert.deepEqual(result.violations, [], JSON.stringify(row));
        return row;
      };
      for (const [path, selector] of [
        ['/foundation', '.catalog-footer'],
        ['/components/controls', '#ref-button .reference-api code'],
        ['/components/layout', '#ref-card dd:last-child'],
        ['/components/feedback', '#ref-copy .reference-details .cm-lineNumbers .cm-activeLineGutter'],
        ['/components/overlays', '#ref-dialog .reference-details summary'],
        ['/components/content', '#ref-source-view .reference-api code'],
      ]) {
        await open(path); await measure(path, selector, 'scrolled text');
      }
      await open('/source'); await until(`document.querySelectorAll('[data-source-ready]').length===8`);
      for (const selector of ['[data-source-long] .cm-lineNumbers .cm-activeLineGutter',
        '[data-source-medium] .cm-foldGutter span[title="Fold line"]']) {
        await measure('/source', selector, 'source gutter');
      }
      for (const id of ['dark', 'light']) {
        await open('/selection'); await until(`document.querySelector('[data-picker-native=${id}].enhanced')`);
        const root = `document.querySelector('[data-picker-native=${id}]').closest('[data-bml-design-select]')`;
        await evaluate(`${root}.querySelector('[data-bml-ref=trigger]').scrollIntoView({block:'center'});${root}.querySelector('[data-bml-ref=trigger]').click()`);
        await until(`${root}.querySelector('[data-bml-ref=popup]').matches(':popover-open')`);
        await evaluate(`${root}.querySelector('[data-bml-ref=query]').value='control';${root}.querySelector('[data-bml-ref=query]').dispatchEvent(new Event('input',{bubbles:true}))`);
        await until(`!${root}.querySelector('[data-bml-ref=popup]').hasAttribute('aria-busy')&&${root}.querySelector('[role=option][data-highlighted]')`);
        const option = await evaluate(`${root}.querySelector('[role=option][data-highlighted]').id`);
        const row = await measure('/selection', `#${option}`, `highlighted option ${id}`, false);
        assert.match(row.visible.text, /Button states/);
        await open('/feedback');
        const trigger = `document.querySelector('[data-feedback-tooltip=${id}][data-placement=right] button')`;
        await until(`${trigger}&&!${trigger}.hasAttribute('title')`);
        await evaluate(`${trigger}.scrollIntoView({block:'center'});${trigger}.focus()`);
        const tooltip = `[data-feedback-tooltip=${id}][data-placement=right] .bosca-tooltip:popover-open`;
        await until(`document.querySelector(${JSON.stringify(tooltip)})`);
        await measure('/feedback', tooltip, `tooltip ${id}`, false);
        if (appearance === 'dark') {
          const shot = await call('Page.captureScreenshot', { format: 'png' });
          await writeFile(join(artifacts, `catalog-${mode}-contrast-tooltip-${id}-${width}.png`), Buffer.from(shot.data, 'base64'));
        }
      }
      // Change only this isolated fixture, prove that the check fails, then restore its exact style.
      await open('/foundation'); const target = '.catalog-footer';
      const style = await evaluate(`document.querySelector('${target}').getAttribute('style')`);
      for (const [property, value] of [['color', 'getComputedStyle(document.body).backgroundColor'],
        ['backgroundImage', "'linear-gradient(white,black)'"], ['opacity', "'0.5'"]]) {
        try {
          await evaluate(`document.querySelector('${target}').style.${property}=${value}`);
          const negative = await inspect(target);
          if (property === 'color') {
            assert.deepEqual(negative.unsupported, []); assert.ok(negative.ratio < 4.5);
          } else assert.ok(negative.unsupported.some(item => item.property === property));
          assert.throws(() => assertContrast(negative));
          reports.push({ path: '/foundation', width, appearance, state: `negative ${property} probe`, negative });
        } finally {
          await evaluate(`document.querySelector('${target}').${style === null ? "removeAttribute('style')" : `setAttribute('style',${JSON.stringify(style)})`}`);
        }
        assertContrast(await inspect(target));
      }
    }
  } finally {
    await writeFile(join(artifacts, `catalog-${mode}-contrast-visibility.json`), JSON.stringify(reports, null, 2));
  }
  console.log(`Catalog ${mode}: ${reports.filter(row=>row.visible).length} visible contrast cases; four low-contrast and eight unsupported-paint probes rejected and restored`);
}
