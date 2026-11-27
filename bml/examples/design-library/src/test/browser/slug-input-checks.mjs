import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Uses the compiled consumer's native fields and typed server submission across ancestor patches. */
export async function checkSlugInputs({ call, evaluate, until, base, mode, artifacts }) {
  const form = palette => `document.querySelector('[data-slug-form=${palette}]')`;
  const field = (palette, name) => `${form(palette)}.elements.namedItem('${name}')`;
  const type = async (palette, name, text) => {
    await evaluate(`${field(palette, name)}.focus();${field(palette, name)}.select()`);
    await call('Input.insertText', { text });
    assert.equal(await evaluate(`${field(palette, name)}.value`), text);
  };
  const reset = async palette => {
    const revision = await evaluate(`Number(document.querySelector('[data-slug-result=${palette}]').dataset.slugResets)`);
    await evaluate(`${form(palette)}.querySelector('[data-bml-method=resetSlug]').click()`);
    await until(`Number(document.querySelector('[data-slug-result=${palette}]').dataset.slugResets)===${revision + 1}&&${field(palette, 'title')}.value===''&&${field(palette, 'slugManual')}.value==='false'`);
  };
  for (const width of [1920, 1024, 768, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    await call('Page.navigate', { url: base + '/foundation' });
    await until(`document.readyState==='complete'&&${form('light')}?.dataset.bmlActionBound==='true'`);
    for (const palette of ['light', 'purple']) {
      const neighbor = palette === 'light' ? 'purple' : 'light';
      const untouched = await evaluate(`${field(neighbor, 'title')}.value`);
      await reset(palette);
      await type(palette, 'title', `Preview ${width}`); assert.equal(await evaluate(`${field(palette, 'slug')}.value`), `preview-${width}`);
      await type(palette, 'slug', 'explicit-key'); await type(palette, 'title', 'Changed title');
      assert.equal(await evaluate(`${field(palette, 'slug')}.value`), 'explicit-key');
      let revision = await evaluate(`Number(document.querySelector('[data-slug-result=${palette}]').dataset.slugSaves)`);
      await evaluate(`${form(palette)}.requestSubmit()`); await until(`Number(document.querySelector('[data-slug-result=${palette}]').dataset.slugSaves)===${revision + 1}`);
      assert.equal(await evaluate(`${field(palette, 'slugManual')}.value`), 'true');
      await type(palette, 'title', 'After patch'); assert.equal(await evaluate(`${field(palette, 'slug')}.value`), 'explicit-key');
      await reset(palette);
      await type(palette, 'title', 'Generated after reset'); assert.equal(await evaluate(`${field(palette, 'slug')}.value`), 'generated-after-reset');
      revision++; await evaluate(`${form(palette)}.requestSubmit()`); await until(`Number(document.querySelector('[data-slug-result=${palette}]').dataset.slugSaves)===${revision + 1}`);
      await type(palette, 'title', 'Still automatic'); assert.equal(await evaluate(`${field(palette, 'slug')}.value`), 'still-automatic');
      assert.equal(await evaluate(`${field(neighbor, 'title')}.value`), untouched);
      assert.equal(await evaluate(`document.documentElement.scrollWidth<=innerWidth`), true);
    }
    const shot = await call('Page.captureScreenshot', { format: 'png' });
    await writeFile(join(artifacts, `slug-input-${mode}-${width}.png`), Buffer.from(shot.data, 'base64'));
  }
}
