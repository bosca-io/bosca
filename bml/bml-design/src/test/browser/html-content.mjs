import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Runs against the public exported surface in the catalog and an independent compiled consumer. */
export async function checkHtmlContent({ call, evaluate, until, base, artifacts, mode, consumer = false }) {
  let navigation = 0;
  const navigate = async path => {
    await evaluate("document.documentElement.setAttribute('data-html-check-old','')");
    const url = new URL(base + path); url.searchParams.set('htmlCheck', String(++navigation));
    await call('Page.navigate', { url: url.href });
    await until(`!document.documentElement.hasAttribute('data-html-check-old')&&document.readyState==='complete'&&location.pathname===${JSON.stringify(path.split('#')[0])}`);
  };
  for (const width of [1920, 1024, 768, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    for (const appearance of consumer ? ['consumer'] : ['dark', 'light']) {
      if (!consumer) {
        await navigate('/components/content');
        await evaluate(`document.cookie='bosca_catalog_appearance=${appearance}; Path=/; SameSite=Lax'`);
      }
      await navigate(consumer ? '/html-content' : '/components/content#ref-html-content');
      await until(`document.readyState==='complete'&&document.querySelectorAll('[data-html-content-example] img[data-failed=true]').length===${consumer ? 2 : 1}`);
      const colors = [];
      for (const index of consumer ? [0, 1] : [0]) {
        const root = `document.querySelectorAll('[data-html-content-example]')[${index}]`;
        const facts = await evaluate(`(() => {const r=${root},p=r.getBoundingClientRect(),img=r.querySelector('img[data-failed=true]'),link=r.querySelector('a');r.scrollIntoView({block:'center'});return {strong:r.querySelector('strong')?.textContent,em:r.querySelector('em')?.textContent,code:r.querySelector('code')?.textContent,caption:r.querySelector('caption')?.textContent,cells:r.querySelectorAll('td').length,fallback:r.querySelectorAll('.image-error').length,hidden:img.hidden,alt:img.alt,imageWidth:r.querySelector('img:not([data-failed])').getBoundingClientRect().width,color:getComputedStyle(r).color,cellColor:getComputedStyle(r.querySelector('td')).color,captionColor:getComputedStyle(r.querySelector('caption')).color,cellBorder:getComputedStyle(r.querySelector('td')).borderTopStyle,codeBackground:getComputedStyle(r.querySelector('pre')).backgroundColor,codeWidth:r.querySelector('pre').clientWidth,codeScroll:r.querySelector('pre').scrollWidth,width:p.width,right:p.right,left:p.left,page:document.documentElement.scrollWidth,viewport:innerWidth,href:link.getAttribute('href')};})()`);
        assert.ok(facts.imageWidth > 0 && facts.imageWidth <= facts.width + 1, JSON.stringify(facts)); assert.equal(facts.strong, 'stored description'); assert.equal(facts.em, 'formatting'); assert.equal(facts.caption, 'Stored values'); assert.equal(facts.cells, 2);
        assert.equal(facts.code, 'Prepared code <sample> & retained whitespace'); assert.equal(facts.fallback, 1); assert.equal(facts.hidden, true); assert.equal(facts.alt, 'Unavailable illustration');
        assert.equal(facts.cellColor, facts.color, JSON.stringify(facts)); assert.equal(facts.captionColor, facts.color, JSON.stringify(facts)); assert.equal(facts.cellBorder, 'solid'); assert.notEqual(facts.codeBackground, 'rgba(0, 0, 0, 0)'); if (width === 390) assert.ok(facts.codeScroll > facts.codeWidth, JSON.stringify(facts));
        assert.ok(facts.href.startsWith('#')); assert.ok(facts.left >= 0 && facts.right <= facts.viewport + 1 && facts.page <= facts.viewport + 1, JSON.stringify(facts)); colors.push(facts.color);
        await evaluate(`${root}.querySelector('a').focus()`); assert.ok(await evaluate(`document.activeElement===${root}.querySelector('a')`));
        const capture = await call('Page.captureScreenshot', { format: 'png' }); await writeFile(join(artifacts, `html-content-${mode}-${appearance}-${width}-${index}.png`), Buffer.from(capture.data, 'base64'));
      }
      if (consumer) assert.notEqual(colors[0], colors[1], 'Independent consumer palettes remain distinct.');
    }
  }
}
