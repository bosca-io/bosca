import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Exercises the exported color field through a separately compiled application's typed actions. */
export async function checkColorInputs({ call, evaluate, until, base, mode, artifacts }) {
  const form = palette => `document.querySelector('[data-color-form=${palette}]')`;
  const field = palette => `${form(palette)}.elements.namedItem('color')`;
  const result = palette => `document.querySelector('[data-color-result=${palette}]')`;
  const type = async (palette, value) => { await evaluate(`${field(palette)}.focus();${field(palette)}.select()`); await call('Input.insertText', { text: value }); };
  const save = async palette => {
    const revision = await evaluate(`Number(${result(palette)}.dataset.colorSaves)`);
    await evaluate(`${form(palette)}.requestSubmit()`); await until(`Number(${result(palette)}.dataset.colorSaves)===${revision + 1}`);
  };
  for (const width of [1920, 1024, 768, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    await call('Page.navigate', { url: base + '/foundation' }); await until(`document.readyState==='complete'&&${form('light')}?.dataset.bmlActionBound==='true'`);
    for (const palette of ['light', 'purple']) {
      console.log(`Color input ${mode}: ${width}px ${palette}`);
      const neighbor = palette === 'light' ? 'purple' : 'light', untouched = await evaluate(`${field(neighbor)}.value`);
      await until(`${form(palette)}.querySelector('[data-color-current]').style.backgroundColor!==''`);
      assert.ok(await evaluate(`${field(palette)}.labels.length===1&&${field(palette)}.labels[0].textContent.includes('Accent color')`));
      await type(palette, '#12345678'); await save(palette); assert.equal(await evaluate(`${field(palette)}.value`), '#12345678');
      assert.ok(await evaluate(`${form(palette)}.querySelector('[data-color-current]').style.backgroundColor.startsWith('rgba(')`));
      const preset = `${form(palette)}.querySelector('[data-color-preset="#4ade80"]')`;
      await evaluate(`${preset}.focus()`);
      for (const type of ['keyDown', 'keyUp']) await call('Input.dispatchKeyEvent', { type, key: ' ', code: 'Space', windowsVirtualKeyCode: 32 });
      await until(`${field(palette)}.value==='#4ade80'`); assert.ok(await evaluate(`document.activeElement===${field(palette)}&&${preset}.getAttribute('aria-pressed')==='true'`));
      await save(palette); assert.equal(await evaluate(`${field(palette)}.value`), '#4ade80');
      await type(palette, 'custom'); await save(palette); assert.equal(await evaluate(`${field(palette)}.value`), 'custom'); assert.equal(await evaluate(`${form(palette)}.querySelector('[data-color-current]').style.backgroundColor`), '');
      const resets = await evaluate(`Number(${result(palette)}.dataset.colorResets)`);
      await evaluate(`${form(palette)}.querySelector('[data-bml-method=resetColor]').click()`); await until(`Number(${result(palette)}.dataset.colorResets)===${resets + 1}&&${field(palette)}.value===''`); await save(palette);
      assert.equal(await evaluate(`new FormData(${form(palette)}).get('color')`), '');
      assert.deepEqual(await evaluate(`(() => {const form=${form(palette)};return [form.elements.readonlyColor.value,form.elements.readonlyColor.readOnly,new FormData(form).get('disabledColor')]})()`), ['red', true, null]);
      assert.ok(await evaluate(`Array.from(${form(palette)}.querySelectorAll('input[readonly],input[disabled]'),field=>field.closest('[data-bml-color-input]').querySelector('[data-color-preset]').disabled).every(Boolean)`));
      await type(palette, 'red'); await evaluate(`${form(palette)}.reset()`); await until(`${field(palette)}.value===''`);
      assert.equal(await evaluate(`${field(neighbor)}.value`), untouched);
      await type(palette, '#91a6f680'); await save(palette);
      await evaluate(`${form(palette)}.scrollIntoView({block:'center'})`); assert.ok(await evaluate(`document.documentElement.scrollWidth<=innerWidth`));
      const shot = await call('Page.captureScreenshot', { format: 'png' }); await writeFile(join(artifacts, `color-input-${mode}-${palette}-${width}.png`), Buffer.from(shot.data, 'base64'));
      console.log(`Color input ${mode}: ${width}px ${palette} complete`);
    }
  }
}
