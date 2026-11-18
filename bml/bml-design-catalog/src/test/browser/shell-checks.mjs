import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Verifies a linked consumer with caller-owned state and no Git/auth dependencies. */
export async function checkShell({ call, evaluate, until, base, mode, artifacts }) {
  const trigger='[data-shell-account] summary', popup='[data-shell-account] [data-bml-design-popover-content]';
  const click=async(selector,button='left')=>{
    const point=await evaluate(`(() => {const e=document.querySelector(${JSON.stringify(selector)});e.scrollIntoView({block:'nearest'});const r=e.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2};})()`);
    for(const type of ['mousePressed','mouseReleased']) await call('Input.dispatchMouseEvent',{type,...point,button,clickCount:1});
  };
  const key=async(key,modifiers=0)=>{
    for(const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent',{type,key,modifiers,
      windowsVirtualKeyCode:{Escape:27,Enter:13,Home:36,End:35,ArrowUp:38,F10:121}[key],...(key==='Enter'&&type==='keyDown'?{text:'\r'}:{})});
  };
  const ready=()=>until(`document.querySelector('[data-shell-appearance]')?.dataset.bmlActionBound==='true'&&document.querySelector('${trigger}')?.hasAttribute('aria-controls')`);
  const reports=[];
  for(const width of [1440,1024,768,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:width===390?844:900,deviceScaleFactor:1,mobile:width===390});
    await call('Page.navigate',{url:base+'/shell'});
    await until(`document.readyState==='complete'&&document.querySelector('[data-example-shell]')`);await ready();
    await evaluate('document.fonts.ready');
    for(const appearance of ['dark','light']) {
      if(await evaluate(`document.querySelector('[data-example-shell]').dataset.theme!==${JSON.stringify(appearance)}`)) {
        await click(trigger);await until(`document.querySelector('${popup}').matches(':popover-open')`);
        await click('[data-shell-appearance]');await ready();
        await until(`document.querySelector('[data-example-shell]').dataset.theme===${JSON.stringify(appearance)}`);
      }
      const geometry=await evaluate(`(() => {
        const shell=document.querySelector('[data-example-shell]'),header=shell.querySelector('.app-header'),nav=header.querySelector('nav');
        return {width:innerWidth,height:innerHeight,shell:shell.getBoundingClientRect().toJSON(),header:header.getBoundingClientRect().toJSON(),
          direction:getComputedStyle(nav).flexDirection,image:getComputedStyle(shell).backgroundImage,
          overflow:document.documentElement.scrollWidth>innerWidth,footer:header.querySelector('.shell-footer').getBoundingClientRect().toJSON(),
          profile:document.querySelector('[data-shell-profile]').getBoundingClientRect().toJSON()};
      })()`);
      assert.equal(geometry.shell.width,width);assert.equal(geometry.shell.height,geometry.height);assert.ok(!geometry.overflow,JSON.stringify(geometry));
      assert.equal(geometry.header.width,width<=700?width:224);assert.equal(geometry.direction,width<=700?'row':'column');
      assert.equal((geometry.image.match(/radial-gradient\(/g)??[]).length,3);assert.equal((geometry.image.match(/linear-gradient\(/g)??[]).length,1);
      assert.ok(geometry.footer.top>=geometry.header.top&&geometry.footer.bottom<=geometry.header.bottom,'The profile footer stays in the responsive header');
      await click(trigger,'right');
      await until(`document.querySelector('${popup}').matches(':popover-open')&&document.activeElement===document.querySelector('[data-shell-appearance]')`);
      const menu=await evaluate(`(() => {const p=document.querySelector('${popup}'),r=p.getBoundingClientRect();return {rect:r.toJSON(),color:getComputedStyle(p).backgroundColor,
        width:document.querySelector('${trigger}').getBoundingClientRect().height,icon:document.querySelector('${trigger} svg').getBoundingClientRect().width};})()`);
      assert.equal(menu.width,42);assert.equal(menu.icon,28);
      assert.equal(menu.color,appearance==='light'?'rgb(255, 255, 255)':'rgb(16, 19, 28)');
      assert.ok(menu.rect.left>=12&&menu.rect.right<=width-12&&menu.rect.top>=12&&menu.rect.bottom<=geometry.height-12,JSON.stringify(menu));
      await key('End');assert.ok(await evaluate(`document.activeElement.matches('${popup} a[role=menuitem]')`));
      await key('ArrowUp');assert.ok(await evaluate(`document.activeElement===document.querySelector('[data-shell-select]')`),'Arrow navigation skips disabled entries');
      await key('Escape');assert.ok(await evaluate(`document.activeElement===document.querySelector('${trigger}')`));
      await key('F10',8);await until(`document.querySelector('${popup}').matches(':popover-open')&&document.activeElement===document.querySelector('[data-shell-appearance]')`);
      const previousSequence=await evaluate(`Number(document.querySelector('[data-shell-notice]').dataset.sequence)`);
      await click('[data-shell-select]');await ready();
      await until(`Number(document.querySelector('[data-shell-notice]').dataset.sequence)===${previousSequence+1}&&document.querySelector('[data-shell-notice]').matches(':popover-open')`);
      assert.ok(await evaluate(`document.querySelector('[data-shell-notice]').textContent.includes('Profile selected.')`),'Projected actions call the consuming model');
      await evaluate(`document.querySelector('[data-shell-notice] [data-bml-ref=dismiss]').click()`);
      const name='An independent profile with a long display name '+width+' '+appearance;
      await evaluate(`document.querySelector('[name=name]').value=${JSON.stringify(name)};document.querySelector('[name=name]').form.requestSubmit()`);await ready();
      await until(`document.querySelector('[data-shell-profile] [data-profile-name]').textContent===${JSON.stringify(name)}`);
      await click(trigger);await until(`document.querySelector('${popup}').matches(':popover-open')`);
      assert.equal(await evaluate(`document.querySelector('[data-shell-account] [data-profile-name]').textContent`),name);
      await key('Escape');
      await click('[data-shell-member] summary');await until(`document.querySelector('[data-shell-member] [data-bml-design-popover-content]').matches(':popover-open')`);
      assert.equal(await evaluate(`document.querySelector('[data-shell-member] [data-profile-name]').textContent`),'Independent member');
      assert.ok(await evaluate(`document.querySelector('[data-shell-account] summary').getAttribute('aria-controls')!==document.querySelector('[data-shell-member] summary').getAttribute('aria-controls')`));
      await key('Escape');
      await writeFile(join(artifacts,`${mode}-shell-${width}-${appearance}.png`),Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
      reports.push({width,appearance,geometry,menu});
    }
  }
  await call('Emulation.setScriptExecutionDisabled',{value:true});
  try {
    await call('Page.navigate',{url:base+'/shell'});await until(`document.readyState==='complete'&&document.querySelector('${trigger}')`);
    await click(trigger);
    assert.ok(await evaluate(`document.querySelector('[data-shell-account] details').open&&!document.querySelector('${popup}').hasAttribute('popover')&&document.querySelector('${popup} a').getAttribute('href')==='/workspaces'`),'Native disclosure and links remain usable without scripts');
  } finally {await call('Emulation.setScriptExecutionDisabled',{value:false});}
  await writeFile(join(artifacts,`${mode}-shell.json`),JSON.stringify(reports,null,2));
}
