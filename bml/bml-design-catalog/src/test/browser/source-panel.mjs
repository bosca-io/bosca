import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Finder controls wrap with usable targets while normal and compact viewers retain their contracts. */
export async function checkSourcePanel({navigate,evaluate,until,call,key,artifacts,mode}) {
  const reports=[];
  const selectors=['[data-catalog-source=dark]','[data-catalog-source=light]','[data-source-short]',
    '[data-source-medium]','[data-source-long]','[data-source-compact-bare]'];
  try {
    for(const width of [1440,390])for(const appearance of ['dark','light'])for(const selector of selectors) {
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
      await call('Network.clearBrowserCookies');await navigate('/source');
      await until(`document.querySelectorAll('[data-source-ready]').length===8`);
      if(appearance==='light')await evaluate(`document.querySelector('[data-catalog-light]').click()`);
      await evaluate('document.fonts.ready');
      const root=`document.querySelector(${JSON.stringify(selector)})`;
      const panel=`${root}.querySelector('.cm-search')`;
      const before=await evaluate(`({width:${root}.offsetWidth,height:${root}.offsetHeight,source:${root}.querySelector('textarea').value})`);
      const open=async()=>{
        await evaluate(`${root}.scrollIntoView({block:'center'});${root}.querySelector('.cm-content').focus()`);
        for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:'f',windowsVirtualKeyCode:70,
          modifiers:await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2')});
        await until(`${panel}?.querySelector('input[name=search]')===document.activeElement`);
      };
      const geometry=()=>evaluate(`(()=>{const root=${root},panel=${panel};return {
        viewer:root.getBoundingClientRect().toJSON(),panel:panel.getBoundingClientRect().toJSON(),
        viewport:root.querySelector('.cm-scroller').clientHeight,font:getComputedStyle(panel).fontFamily,
        targets:[...panel.children].filter(el=>el.matches('input,button,label')).map(el=>({
          name:el.name||el.textContent.trim(),box:el.getBoundingClientRect().toJSON(),fontSize:parseFloat(getComputedStyle(el).fontSize)})),
        overflow:document.documentElement.scrollWidth-innerWidth};})()`);
      const check=(state,height)=>{
        assert.equal(state.targets.length,8);
        assert.ok(state.viewport>=44&&state.panel.bottom<=state.viewer.bottom+0.5&&state.overflow<=0,JSON.stringify(state));
        for(const target of state.targets) {
          const b=target.box;
          assert.ok(b.height>=height-0.5&&target.fontSize>=12,JSON.stringify(target));
          assert.ok(b.left>=state.panel.left+7.5&&b.right<=state.panel.right-7.5&&b.top>=state.panel.top+7.5&&b.bottom<=state.panel.bottom-7.5,JSON.stringify(target));
        }
        for(let i=0;i<state.targets.length;i++)for(const second of state.targets.slice(i+1)) {
          const a=state.targets[i].box,b=second.box;
          const overlapX=Math.min(a.right,b.right)-Math.max(a.left,b.left),overlapY=Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top);
          assert.ok(overlapX<=-5.5||overlapY<=-5.5,JSON.stringify([state.targets[i],second]));
        }
      };
      await open();await evaluate(`${root}.scrollIntoView({block:'center'})`);
      const normal=await geometry();reports.push({width,appearance,selector,before,normal});
      await writeFile(join(artifacts,`catalog-${mode}-source-panel.json`),JSON.stringify(reports,null,2));
      check(normal,30);assert.ok(!normal.font.includes('monospace'),normal.font);
      for(const character of 'const')for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{
        type,key:character,windowsVirtualKeyCode:character.toUpperCase().charCodeAt(0),...(type==='keyDown'?{text:character}:{})});
      await until(`${root}.querySelector('.cm-searchMatch')!==null`);
      for(const name of ['next','prev']) {
        await evaluate(`${panel}.querySelector('button[name=${name}]').click()`);
        await until(`${root}.querySelector('.cm-searchMatch-selected')?.textContent==='const'`);
      }
      await evaluate(`${panel}.querySelector('button[name=select]').click()`);
      assert.ok(await evaluate(`[...${root}.querySelectorAll('.cm-searchMatch')].every(match=>match.classList.contains('cm-searchMatch-selected'))`));
      await evaluate(`${panel}.querySelector('input[name=case]').parentElement.click()`);
      assert.equal(await evaluate(`${panel}.querySelector('input[name=case]').checked`),true);
      await evaluate(`${panel}.querySelector('input[name=case]').parentElement.click();${panel}.querySelector('input[name=search]').focus()`);
      for(const name of ['next','prev','select','case','re','word','close']) {
        await key('Tab');assert.equal(await evaluate('document.activeElement.name'),name);
      }
      if(selector.includes('catalog-source')) {
        await evaluate(`window.panelStyle=document.createElement('style');window.panelStyle.textContent='[data-panel-size] {--control-height-sm:36px;--font-sans:system-ui,sans-serif;}';document.head.append(window.panelStyle);${root}.setAttribute('data-panel-size','')`);
        const overridden=await geometry();check(overridden,36);assert.ok(overridden.font.includes('system-ui'));
        reports.at(-1).overridden=overridden;
        await evaluate(`${root}.removeAttribute('data-panel-size');window.panelStyle.remove()`);
      }
      if(selector.includes('short')||selector.includes('catalog-source')) {
        const shot=await call('Page.captureScreenshot',{format:'png'});
        await writeFile(join(artifacts,`catalog-${mode}-panel-${appearance}-${selectors.indexOf(selector)}-${width}.png`),Buffer.from(shot.data,'base64'));
      }
      await key('Enter');await until(`!${panel}`);
      assert.equal(await evaluate(`document.activeElement===${root}.querySelector('.cm-content')`),true);
      await open();await key('Escape');await until(`!${panel}`);
      assert.deepEqual(await evaluate(`({width:${root}.offsetWidth,height:${root}.offsetHeight,source:${root}.querySelector('textarea').value})`),before);
    }
  } finally {
    await writeFile(join(artifacts,`catalog-${mode}-source-panel.json`),JSON.stringify(reports,null,2));
  }
}
