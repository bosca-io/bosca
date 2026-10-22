import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** The compiled dependency keeps empty widgets hidden without dropping native form values. */
export async function checkEmptyWidgets({call,evaluate,until,base,mode,artifacts}) {
  const reports=[];
  try {
    for(const scripts of [true,false])for(const width of [1440,390]) {
      await call('Emulation.setScriptExecutionDisabled',{value:!scripts});
      await call('Emulation.setDeviceMetricsOverride',{width,height:1100,deviceScaleFactor:1,mobile:false});
      await call('Page.navigate',{url:base+'/selection'});
      await until(`document.readyState==='complete'&&Boolean(document.querySelector('[data-empty-views]'))`);
      const groups=await evaluate(`(()=>{const segments=document.querySelector('[data-empty-segments]'),views=document.querySelector('[data-empty-views]');
        const form=document.createElement('form');segments.after(form);form.append(segments);
        const values=[...new FormData(form)];form.before(segments);form.remove();
        return {values,hidden:!segments.checkVisibility()&&!views.checkVisibility(),
          callerHidden:[...document.querySelectorAll('[data-hidden-segments],[data-hidden-views]')]
            .map(root=>({hidden:root.hidden,visible:root.checkVisibility(),items:root.querySelectorAll('button').length})),
          targets:segments.querySelectorAll('button,a,[tabindex]').length+views.querySelectorAll('button,a,[tabindex]').length,
          populated:[...document.querySelectorAll('[data-unmatched-segments],[data-unmatched-views]')].map(root=>({role:root.role,visible:root.checkVisibility()}))};})()`);
      const empty=(await call('Accessibility.getFullAXTree')).nodes.filter(node=>!node.ignored&&['Empty choices','Empty views','Hidden choices','Hidden views'].includes(node.name?.value))
        .map(node=>({role:node.role?.value,name:node.name?.value}));
      reports.push({scripts,width,groups,empty});
      assert.deepEqual(empty,[]);assert.ok(groups.hidden&&groups.targets===0);
      assert.deepEqual(groups.values,[['empty','']]);
      assert.deepEqual(groups.callerHidden,[{hidden:true,visible:false,items:2},{hidden:true,visible:false,items:2}]);
      assert.deepEqual(groups.populated,[{role:'radiogroup',visible:true},{role:'tablist',visible:true}]);
      await call('Page.navigate',{url:base+'/composition'});
      await until(`document.readyState==='complete'&&Boolean(document.querySelector('[data-empty-tree]'))`);
      const handle=await call('Runtime.evaluate',{expression:`document.querySelector('[data-empty-tree] [role=tree]')`});
      const ax=await call('Accessibility.getPartialAXTree',{objectId:handle.result.objectId,fetchRelatives:false});
      await call('Runtime.releaseObject',{objectId:handle.result.objectId});
      assert.ok(ax.nodes[0].ignored);
      assert.equal(await evaluate(`!document.querySelector('[data-empty-tree] [role=tree]').checkVisibility()&&
        document.querySelector('[data-disabled-tree] [role=tree]').checkVisibility()&&
        [...document.querySelectorAll('[data-local-tree] a')].some(link=>link.checkVisibility())`),true);
      reports.at(-1).treeIgnored=ax.nodes[0].ignored;
    }
  } finally {
    await call('Emulation.setScriptExecutionDisabled',{value:false});
    await writeFile(join(artifacts,`empty-widgets-${mode}.json`),JSON.stringify(reports,null,2));
  }
}
