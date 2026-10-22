import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Empty controls have no accessibility widget or keyboard target, including without scripts. */
export async function checkEmptyWidgets({navigate,evaluate,until,call,artifacts,mode}) {
  const reports=[];
  try {
    for(const scripts of [true,false])for(const width of [1440,390])for(const appearance of ['dark','light']) {
      await call('Emulation.setScriptExecutionDisabled',{value:!scripts});
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
      await call('Network.clearBrowserCookies');
      await navigate('/views');
      if(scripts)await until(`Boolean(document.querySelector('[data-views-refresh=dark]')?.dataset.bmlActionBound==='true')`);
      if(appearance==='light')await evaluate(`document.querySelector('[data-catalog-light]').click()`);
      const groups=await evaluate(`(()=>{
        const segments=document.querySelector('[data-views-empty-segments]'),tabs=document.querySelector('[data-views-empty-tabs]');
        const form=document.createElement('form');segments.after(form);form.append(segments);
        const values=[...new FormData(form)];form.before(segments);form.remove();
        return {values,groups:[segments,tabs].map(root=>({visible:root.checkVisibility(),
          targets:root.querySelectorAll('button,a,[tabindex]').length,box:root.getBoundingClientRect().toJSON()})),
          populated:[...document.querySelectorAll('[data-views-unmatched-segments],[data-views-unmatched-tabs]')]
            .map(root=>({role:root.role,visible:root.checkVisibility(),items:root.querySelectorAll('button').length})),
          overflow:document.documentElement.scrollWidth-innerWidth};
      })()`);
      const ax=await call('Accessibility.getFullAXTree');
      const empty= ax.nodes.filter(node=>!node.ignored&&['Empty views','Empty categories'].includes(node.name?.value))
        .map(node=>({role:node.role?.value,name:node.name?.value}));
      reports.push({scripts,width,appearance,path:'/views',groups,empty});
      await writeFile(join(artifacts,`catalog-${mode}-empty-widgets.json`),JSON.stringify(reports,null,2));
      assert.deepEqual(empty,[],'Empty lists do not advertise a widget to assistive technology');
      assert.deepEqual(groups.values,[['empty','']],'Empty segments retain their exact native form value');
      for(const group of groups.groups)assert.ok(!group.visible&&group.targets===0&&group.box.height===0,JSON.stringify(group));
      assert.deepEqual(groups.populated,[{role:'radiogroup',visible:true,items:2},{role:'tablist',visible:true,items:2}]);
      assert.ok(groups.overflow<=0);
      for(const path of ['/trees','/components/content']) {
        await navigate(path);
        if(appearance==='light')await evaluate(`document.querySelector('[data-catalog-light]').click()`);
        const state=await evaluate(`(()=>{const root=document.querySelector('[role=tree][aria-label="Empty files"]');
          return {visible:root.checkVisibility(),targets:root.querySelectorAll('a,button,[tabindex]').length,
            populated:[...document.querySelectorAll('[role=tree]')].filter(tree=>tree!==root&&tree.checkVisibility()).length,
            guidance:${path==='/trees'?`root.closest('section').parentElement.querySelector('.empty-state p')?.checkVisibility()`:'null'}};})()`);
        const tree=await call('Runtime.evaluate',{expression:`document.querySelector('[role=tree][aria-label="Empty files"]')`});
        const accessibility=await call('Accessibility.getPartialAXTree',{objectId:tree.result.objectId,fetchRelatives:false});
        await call('Runtime.releaseObject',{objectId:tree.result.objectId});
        reports.push({scripts,width,appearance,path,...state,ignored:accessibility.nodes[0].ignored});
        assert.ok(!state.visible&&state.targets===0&&state.populated>0&&accessibility.nodes[0].ignored,JSON.stringify(reports.at(-1)));
        if(path==='/trees') {
          assert.equal(state.guidance,true,'Caller empty-state explanation remains visible');
          assert.ok((await call('Accessibility.getFullAXTree')).nodes.some(node=>!node.ignored&&node.role?.value==='StaticText'
            &&node.name?.value==='The application supplies empty-state content beside the tree.'));
          await evaluate(`document.querySelector('[data-tree-empty]').parentElement.scrollIntoView({block:'center'})`);
          const shot=await call('Page.captureScreenshot',{format:'png'});
          await writeFile(join(artifacts,`catalog-${mode}-empty-${scripts?'scripted':'native'}-${appearance}-${width}.png`),Buffer.from(shot.data,'base64'));
        }
      }
    }
    // Native CSS follows an emptied/restored item list without requiring a client remount.
    await call('Emulation.setScriptExecutionDisabled',{value:false});
    await navigate('/trees');
    const tree=`document.querySelector('[data-tree-disabled] [role=tree]')`;
    await evaluate(`window.emptyTree=${tree};window.emptyTreeItems=[...window.emptyTree.childNodes];window.emptyTree.replaceChildren()`);
    assert.equal(await evaluate(`${tree}.checkVisibility()`),false);
    await evaluate(`window.emptyTree.append(...window.emptyTreeItems)`);
    assert.equal(await evaluate(`${tree}.checkVisibility()`),true);
    assert.equal(await evaluate(`${tree}===window.emptyTree&&${tree}.querySelector('a').checkVisibility()`),true);
  } finally {
    await call('Emulation.setScriptExecutionDisabled',{value:false});
    await writeFile(join(artifacts,`catalog-${mode}-empty-widgets.json`),JSON.stringify(reports,null,2));
  }
}
