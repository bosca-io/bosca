import assert from 'node:assert/strict';
import {chromium,firefox,webkit} from '../../../node_modules/playwright-core/index.mjs';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {join,resolve} from 'node:path';
const base=process.argv[2];
assert.ok(base && ['localhost','127.0.0.1','[::1]'].includes(new URL(base).hostname));
const reports=[],artifacts=resolve('build/reports/browser'); await mkdir(artifacts,{recursive:true});
for(const [name,engine] of [['chromium',chromium],['firefox',firefox],['webkit',webkit]]) {
  const options={headless:true};
  if(name==='chromium') options.executablePath=(await readFile('build/test-browser/executable.txt','utf8')).trim();
  const browser=await engine.launch(options);
  try {
    const context=await browser.newContext({viewport:{width:1440,height:1000},timezoneId:'America/New_York'});
    const page=await context.newPage(); const errors=[]; page.on('pageerror',error=>errors.push(error.message));
    await page.goto(base+'/calendars');
    const example=page.locator('[data-catalog-calendar=dark]'), root=example.locator('[data-bml-calendar]');
    await example.getByRole('button',{name:'day',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('[data-catalog-calendar=dark] [data-bml-calendar]')?.dataset.view==='day');
    const reminder=root.locator('[data-event=reminder]'); await reminder.scrollIntoViewIfNeeded();
    const before=await reminder.boundingBox();
    await page.mouse.move(before.x+before.width/2,before.y+5); await page.mouse.down(); await page.mouse.move(before.x+before.width/2,before.y+29,{steps:6});
    assert.equal(await reminder.evaluate(node=>node.style.top),'600px',name);
    await page.mouse.up();
    await page.waitForFunction(()=>new Date(JSON.parse(document.querySelector('[data-catalog-calendar=dark] [data-bml-calendar]').dataset.events).find(e=>e.id==='reminder').startsAt).getMinutes()===30);
    await example.locator('[data-calendar-add]').first().click();
    const dialog=example.locator('[data-calendar-create] > dialog'), form=dialog.locator('form').first();
    await dialog.waitFor({state:'visible'});
    await form.locator('[name=title]').fill('Engine test');
    await form.locator('[name=location]').fill('Room B');
    await page.screenshot({path:join(artifacts,`calendar-${name}-editor.png`)});
    await page.addScriptTag({path:new URL('../../../node_modules/axe-core/axe.min.js',import.meta.url).pathname});
    const accessibility=await page.evaluate(async()=>{const result=await axe.run(document,{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21a','wcag21aa']}});return result.violations.map(rule=>({id:rule.id,impact:rule.impact,nodes:rule.nodes.map(node=>node.target)}));});
    await writeFile(join(artifacts,`calendar-${name}-accessibility.json`),JSON.stringify(accessibility,null,2));
    assert.deepEqual(accessibility,[],`${name} editor accessibility`);
    await form.locator('[data-bml-ref=frequency]').selectOption('DAILY');
    await form.locator('[data-bml-ref=count]').fill('2');
    await form.locator('[data-bml-ref=count]').dispatchEvent('change');
    await form.locator('select[name=kind]').selectOption('all-day');
    assert.equal(await form.locator('[data-bml-ref=recurrence]').isVisible(),false);
    assert.equal(await form.locator('[name=rrule]').getAttribute('type'),'hidden');
    await form.locator('[name=startLocal]').fill('2026-11-05'); await form.locator('[name=endLocal]').fill('2026-11-06');
    await dialog.getByRole('button',{name:'Add event',exact:true}).click();
    await dialog.waitFor({state:'hidden'});
    assert.equal(await root.evaluate(node=>JSON.parse(node.dataset.events).filter(e=>e.title==='Engine test').length),1);
    await root.locator('[data-event]').filter({hasText:'Engine test'}).first().click(); await dialog.waitFor({state:'visible'});
    await page.screenshot({path:join(artifacts,`calendar-${name}-edit-footer.png`)});
    await form.locator('[name=title]').fill('Edited occurrence'); await dialog.getByRole('button',{name:'Save event',exact:true}).click(); await dialog.waitFor({state:'hidden'});
    assert.ok(await root.evaluate(node=>JSON.parse(node.dataset.events).some(e=>e.title==='Edited occurrence')));
    await page.screenshot({path:join(artifacts,`calendar-${name}.png`),fullPage:true});
    assert.deepEqual(errors,[],name);
    reports.push({engine:name,version:browser.version(),mouseDrag:true,allDay:true,recurrence:true,edit:true,errors});
    await context.close();
    if(name==='webkit' || name==='chromium') {
      const touch=await browser.newContext({viewport:{width:390,height:844},hasTouch:true,isMobile:true,timezoneId:'America/New_York'});
      const mobile=await touch.newPage(); await mobile.goto(base+'/calendars');
      const example=mobile.locator('[data-catalog-calendar=dark]');
      if(name==='chromium') {
        await example.getByRole('button',{name:'day',exact:true}).tap();
        await mobile.waitForFunction(()=>document.querySelector('[data-catalog-calendar=dark] [data-bml-calendar]')?.dataset.view==='day');
        const event=example.locator('[data-event=reminder]'); await event.scrollIntoViewIfNeeded();
        const r=await event.boundingBox(),cdp=await touch.newCDPSession(mobile);
        await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:r.x+r.width/2,y:r.y+5}]});
        await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:r.x+r.width/2,y:r.y+29}]});
        assert.equal(await event.evaluate(node=>node.style.top),'600px');
        await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
        await mobile.waitForFunction(()=>new Date(JSON.parse(document.querySelector('[data-catalog-calendar=dark] [data-bml-calendar]').dataset.events).find(e=>e.id==='reminder').startsAt).getMinutes()===30);
        reports.push({engine:name,touchDrag:true});
      }
      await example.locator('[data-calendar-add]').first().tap();
      const dialog=example.locator('[data-calendar-create] > dialog'); await dialog.waitFor({state:'visible'});
      await dialog.locator('[name=title]').fill('Touch event'); await dialog.getByRole('button',{name:'Add event',exact:true}).tap(); await dialog.waitFor({state:'hidden'});
      assert.ok(await example.locator('[data-bml-calendar]').evaluate(node=>JSON.parse(node.dataset.events).some(e=>e.title==='Touch event')));
      reports.push({engine:name,touchCreation:true}); await touch.close();
    }
  } finally {await browser.close();}
}
await writeFile(join(artifacts,'calendar-engines.json'),JSON.stringify(reports,null,2));
console.log(JSON.stringify(reports));
