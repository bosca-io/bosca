import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Quiet clipboard announcements must stay inside the control in a separately scrolling page. */
export async function checkCopyOverflow({ navigate, evaluate, until: poll, call, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`);
  const results = [];
  for (const width of [1440, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
    await navigate('/components/feedback');
    await until(`document.querySelector('[data-export=copy] button')&&!document.querySelector('[data-export=copy] button').hasAttribute('title')`);
    await evaluate('document.fonts.ready');
    await evaluate(`Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText(){return window.copyDenied?Promise.reject(new DOMException('Denied','NotAllowedError')):Promise.resolve();}}})`);
    for (const tag of ['copy', 'copy-field']) {
      const selector = `[data-export=${tag}] .copy-control`;
      const root = `document.querySelector(${JSON.stringify(selector)})`;
      const button = `${root}.querySelector('button')`;
      const message = `${root}.querySelector('[role=status]')`;
      const done = `${root}.querySelector('[data-bml-ref=doneIcon]')`;
      const layout = () => evaluate(`(()=>{
        const root=${root},entry=root.closest('[data-export]'),main=document.querySelector('main');
        const extent=el=>[el.clientWidth,el.clientHeight,el.scrollWidth,el.scrollHeight,el.scrollTop];
        const bounds=el=>{const r=el.getBoundingClientRect();return [r.left,r.top,r.width,r.height];};
        return {document:extent(document.documentElement),body:extent(document.body),main:extent(main),
          button:bounds(root.querySelector('button')),demo:bounds(entry.querySelector('.reference-demo')),
          following:bounds(entry.querySelector('.reference-details'))};
      })()`);
      const contained = () => evaluate(`(()=>{const root=${root},status=root.querySelector('[role=status]'),r=root.getBoundingClientRect(),s=status.getBoundingClientRect();
        return s.width===1&&s.height===1&&s.top>=r.top-1&&s.bottom<=r.bottom+1&&s.left>=r.left-1&&s.right<=r.right+1;
      })()`);
      await evaluate(`${root}.scrollIntoView({block:'center'});${button}.focus({preventScroll:true});window.copyDenied=false`);
      const before = await layout();
      await evaluate(`${button}.click()`);
      await until(`!${done}.hidden&&${message}.textContent==='Copied.'`);
      const copied = await layout();
      assert.deepEqual(copied, before, `${tag} confirmation must not add a scrollbar or move content at ${width}px`);
      assert.ok(await contained(), `${tag} accessible status stays within its control at ${width}px`);
      assert.equal(await evaluate(`${button}.getAttribute('aria-label')`), 'Copied');
      const capture = await call('Page.captureScreenshot', { format: 'png' });
      await writeFile(join(artifacts, `catalog-${mode}-${tag}-confirmation-${width}.png`), Buffer.from(capture.data, 'base64'));
      await until(`${done}.hidden&&${message}.textContent===''`);
      assert.deepEqual(await layout(), before, `${tag} timeout preserves the same scroll bounds at ${width}px`);
      await evaluate(`window.copyDenied=true;${button}.click()`);
      await until(`${message}.textContent==='Select and copy the value.'`);
      assert.ok(await contained(), `${tag} denied announcement stays within its control at ${width}px`);
      assert.ok(await evaluate(`${done}.hidden`));
      if (tag === 'copy') {
        assert.deepEqual(await layout(), before, `Quiet manual-copy guidance must not expand the page at ${width}px`);
      } else {
        assert.ok(await evaluate(`(()=>{const field=${root}.closest('.copy-field'),input=field.querySelector('input');return !field.querySelector('[role=alert]').hidden&&document.activeElement===input&&input.selectionStart===0&&input.selectionEnd===input.value.length;})()`), 'Denied clipboard access retains visible manual-copy guidance and input selection');
        // The intentional visible error can grow the field; its quiet announcement cannot grow the outer page.
        const denied = await layout();
        assert.deepEqual(denied.document, before.document);
        assert.deepEqual(denied.body, before.body);
        assert.equal(denied.main[0], before.main[0]);
      }
      results.push({ width, tag, before, copied, denied: await layout() });
    }
  }
  await writeFile(join(artifacts, `catalog-${mode}-copy-overflow.json`), JSON.stringify(results, null, 2));
  console.log(`Catalog ${mode}: copy confirmation, timeout and manual-copy status preserve scroll bounds at desktop and narrow widths`);
}
