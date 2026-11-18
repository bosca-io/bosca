import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Keyboard exit while a tab's server response is still pending. */
export async function checkTabFocus({navigate,evaluate,until:poll,call,artifacts,mode}) {
  const until=expression=>poll(`Boolean(${expression})`);
  const key=async(value,shift=false)=>{
    for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{
      type,key:value,windowsVirtualKeyCode:{ArrowRight:39,Tab:9}[value],modifiers:shift?8:0,
    });
  };
  const holdResponse=()=>evaluate(`(()=>{
    const original=window.fetch.bind(window);
    window.releaseTabResponse=undefined;
    window.fetch=async(...args)=>{
      const response=await original(...args);
      if(String(args[0]).includes('/_bml/action')){
        window.fetch=original;
        await new Promise(resolve=>window.releaseTabResponse=resolve);
      }
      return response;
    };
  })()`);
  const reports=[];
  for(const width of [1440,390])for(const palette of ['dark','light']) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
    await call('Network.clearBrowserCookies');
    await navigate('/views');
    const tabs=`document.querySelector('[data-views-tabs=${palette}]')`;
    const panel=view=>`document.querySelector('[data-views-panel=${palette}][data-view=${view}]')`;
    await until(`${tabs}.querySelector('[role=tab]').hasAttribute('aria-controls')`);
    await holdResponse();
    await evaluate(`${tabs}.querySelector('[data-tab=Preview]').focus()`);
    await key('ArrowRight');
    await until(`typeof window.releaseTabResponse==='function'&&!${panel('Details')}.hidden`);
    await key('Tab');
    assert.equal(await evaluate(`document.activeElement===${panel('Details')}`),true,'Tab enters the locally selected panel before its response');
    await evaluate(`window.focusedTabPanel=${panel('Details')};window.releaseTabResponse()`);
    await until(`${tabs}.dataset.selected==='Details'&&${panel('Details')}.hasAttribute('aria-labelledby')`);
    const result=await evaluate(`({focused:document.activeElement===${panel('Details')},retained:window.focusedTabPanel===${panel('Details')},visible:!${panel('Details')}.hidden,active:document.activeElement.outerHTML.slice(0,300)})`);
    reports.push({width,palette,state:'panel',...result});
    await writeFile(join(artifacts,`catalog-${mode}-tab-focus.json`),JSON.stringify(reports,null,2));
    assert.ok(result.focused&&result.retained&&result.visible,JSON.stringify(result));
    await key('Tab',true);
    assert.equal(await evaluate(`document.activeElement===${tabs}.querySelector('[data-tab=Details]')`),true,'Shift+Tab returns to the selected tab');

    await holdResponse();
    await key('ArrowRight');
    await until(`typeof window.releaseTabResponse==='function'&&!${panel('Source')}.hidden`);
    await key('Tab');await key('Tab');
    assert.equal(await evaluate(`document.activeElement===${panel('Source')}.querySelector('a')`),true,'Tab reaches the panel link before its response');
    await evaluate(`window.focusedPanelLink=document.activeElement;window.releaseTabResponse()`);
    await until(`${tabs}.dataset.selected==='Source'&&${panel('Source')}.hasAttribute('aria-labelledby')`);
    const descendant=await evaluate(`({focused:document.activeElement===window.focusedPanelLink,retained:window.focusedPanelLink===${panel('Source')}.querySelector('a'),visible:!${panel('Source')}.hidden})`);
    reports.push({width,palette,state:'descendant',...descendant});
    assert.ok(descendant.focused&&descendant.retained&&descendant.visible,JSON.stringify(descendant));
    const capture=await call('Page.captureScreenshot',{format:'png'});
    await writeFile(join(artifacts,`catalog-${mode}-tab-focus-${palette}-${width}.png`),Buffer.from(capture.data,'base64'));
    await key('Tab',true);await key('Tab',true);
    assert.equal(await evaluate(`document.activeElement===${tabs}.querySelector('[data-tab=Source]')`),true,'Reverse traversal exits the panel link through its panel to the selected tab');

    await holdResponse();
    await key('ArrowRight');
    await until(`typeof window.releaseTabResponse==='function'&&!${panel('Preview')}.hidden`);
    await evaluate(`document.querySelector('[data-catalog-light]').focus();window.releaseTabResponse()`);
    await until(`${tabs}.dataset.selected==='Preview'`);
    const outside=await evaluate(`document.activeElement===document.querySelector('[data-catalog-light]')`);
    reports.push({width,palette,state:'outside',focused:outside});
    assert.ok(outside,'Completing a tab update does not take focus back from another control');
    assert.equal(await evaluate(`(()=>{const tab=${tabs}.querySelector('[aria-selected=true]'),panel=${panel('Preview')};return tab.getAttribute('aria-controls')===panel.id&&panel.getAttribute('aria-labelledby')===tab.id})()`),true,'Accessible relationships survive each patch');
    await writeFile(join(artifacts,`catalog-${mode}-tab-focus.json`),JSON.stringify(reports,null,2));
  }
}
