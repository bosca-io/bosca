import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

/** Browser accessibility metadata and native keyboard behavior; spoken AT needs a separate review. */
export async function checkDeepTrees({navigate,evaluate,until,call,artifacts,mode}) {
  const report={browser:await call('Browser.getVersion'),cases:[]};
  const root=id=>`document.querySelector('[data-deep-tree=${id}]')`;
  const row=(id,index)=>`${root(id)}.querySelectorAll('[role=treeitem]')[${index}]`;
  const target=(id,index)=>`${row(id,index)}.querySelector('.ui-tree-row')`;
  const ax=async expression=>{
    const handle=await call('Runtime.evaluate',{expression});
    assert.ok(handle.result.objectId,expression);
    try {
      const {nodes}=await call('Accessibility.getPartialAXTree',{objectId:handle.result.objectId,fetchRelatives:false});
      const node=nodes[0];
      return {ignored:node.ignored,role:node.role?.value,name:node.name?.value,
        properties:Object.fromEntries((node.properties??[]).map(property=>[property.name,property.value.value]))};
    } finally {await call('Runtime.releaseObject',{objectId:handle.result.objectId});}
  };
  const key=async value=>{
    for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:value,
      windowsVirtualKeyCode:{Enter:13,Tab:9,Home:36,End:35,ArrowDown:40,ArrowUp:38,ArrowLeft:37,ArrowRight:39}[value],
      ...(type==='keyDown'&&value==='Enter'?{text:'\r'}:{})});
  };
  const focused=async(id,index)=>assert.ok(await evaluate(`document.activeElement===${target(id,index)}`),`${id}: focus at row ${index}`);
  const screenshot=async name=>{
    const capture=await call('Page.captureScreenshot',{format:'png'});
    await writeFile(join(artifacts,`catalog-${mode}-deep-tree-${name}.png`),Buffer.from(capture.data,'base64'));
  };
  try {
    for(const width of [1440,390]) {
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
      await navigate('/trees');
      await until(`document.querySelector('[data-deep-tree=dark] [tabindex="0"]')&&document.querySelector('[data-tree-refresh=dark]')?.dataset.bmlActionBound==='true'`);
      await evaluate('document.fonts.ready');
      for(const id of ['dark','light']) {
        const result={width,palette:id,levels:[]};report.cases.push(result);
        const tree=await ax(`${root(id)}.querySelector('[role=tree]')`);
        assert.equal(tree.ignored,false);assert.equal(tree.role,'tree');
        assert.equal(tree.name,`Thirteen-level files, ${id} palette`);
        for(let index=0;index<13;index++) {
          const item=await ax(row(id,index));result.levels.push(item);
          assert.equal(item.ignored,false);assert.equal(item.role,'treeitem');
          assert.equal(item.properties.level,index+1,JSON.stringify(item));
          assert.equal(item.properties.selected,index===12);
          if(index<12) {
            assert.equal(item.name,`Level ${index+1}`);
            assert.equal(item.properties.expanded,true);
          } else {
            assert.equal(item.name,'veryLongComponentIdentifierThatRemainsReadableWithoutSpaces.bml');
            assert.equal(item.properties.expanded,undefined,'A file is not an expandable parent');
          }
        }
        const handle=await call('Runtime.evaluate',{expression:row(id,12)});
        try {
          const {nodes}=await call('Accessibility.getPartialAXTree',{objectId:handle.result.objectId,fetchRelatives:true});
          result.hierarchy=nodes.map(node=>({id:node.nodeId,parent:node.parentId,role:node.role?.value,name:node.name?.value,
            level:node.properties?.find(property=>property.name==='level')?.value.value}));
          const byId=new Map(result.hierarchy.map(node=>[node.id,node]));let child=result.hierarchy[0];
          for(let level=13;level>1;level--) {
            const group=byId.get(child.parent);assert.equal(group?.role,'group',JSON.stringify(result.hierarchy));
            child=byId.get(group.parent);assert.equal(child?.role,'treeitem');assert.equal(child.level,level-1);
          }
          assert.equal(byId.get(child.parent)?.role,'tree');
        } finally {await call('Runtime.releaseObject',{objectId:handle.result.objectId});}
        result.siblings=await evaluate(`[...${root(id)}.querySelectorAll('[role=treeitem]')].map(row=>({position:row.ariaPosInSet,size:row.ariaSetSize,parent:row.parentElement.role}))`);
        assert.deepEqual(result.siblings.map(item=>[item.position,item.size]),[['1','3'],...Array.from({length:12},()=>['1','1']),['2','3'],['3','3']]);
        assert.ok(result.siblings.every(item=>item.parent==='tree'),'Native rows stay in their server-rendered DOM positions');
        result.file=await ax(target(id,12));
        assert.equal(result.file.role,'link');assert.equal(result.file.name,result.levels[12].name);
        result.disabled=await ax(target(id,13));
        assert.equal(result.disabled.role,'button');assert.equal(result.disabled.properties.disabled,true);
        assert.equal(await evaluate(`${root(id)}.querySelectorAll('[tabindex="0"]').length`),1);
        await evaluate(`${root(id)}.querySelector('[tabindex="0"]').focus()`);await focused(id,12);
        await key('Home');await focused(id,0);
        for(let index=1;index<13;index++){await key('ArrowRight');await focused(id,index);}
        await key('ArrowRight');await focused(id,12);
        await key('ArrowLeft');await focused(id,11);
        await key('ArrowLeft');await focused(id,11);
        assert.equal(await evaluate(`${row(id,12)}.hidden`),true);
        result.closed=await ax(row(id,11));
        assert.equal(result.closed.properties.expanded,false);assert.equal(result.closed.name,'Level 12');
        assert.equal((await ax(row(id,12))).ignored,true,'Collapsed descendants leave the accessibility tree');
        await key('ArrowLeft');await focused(id,10);
        await key('ArrowRight');await focused(id,11);
        await key('ArrowRight');await focused(id,11);
        assert.equal(await evaluate(`${row(id,12)}.hidden`),false);
        await key('ArrowRight');await focused(id,12);
        await key('ArrowDown');await focused(id,14);
        await key('ArrowUp');await focused(id,12);
        await key('Home');await key('Enter');
        await key('End');await focused(id,14);
        await key('ArrowUp');await focused(id,0);
        await key('Enter');await key('End');await key('ArrowUp');await focused(id,12);
        assert.equal(await evaluate(`${root(id)}.querySelectorAll('[aria-selected="true"]').length`),1);
        assert.equal((await ax(row(id,12))).properties.selected,true,'Focus changes leave the caller selection intact');
        result.geometry=await evaluate(`(()=>{const tree=${root(id)},link=${target(id,12)},label=link.querySelector('.tree-label');const box=tree.getBoundingClientRect(),text=label.getBoundingClientRect();return {height:box.height,scrollTop:tree.scrollTop,labelWidth:text.width,labelHeight:text.height,labelRight:text.right,treeRight:box.right,overflow:document.documentElement.scrollWidth-innerWidth,href:link.getAttribute('href')};})()`);
        const geometry=result.geometry;
        assert.ok(geometry.height<=(width===390?220:260)&&geometry.scrollTop>0,JSON.stringify(geometry));
        assert.ok(geometry.overflow<=0&&geometry.labelWidth>0&&geometry.labelRight<=geometry.treeRight+1,JSON.stringify(geometry));
        if(width===390)assert.ok(geometry.labelHeight>=32,'Deep phone labels wrap');
        assert.equal(geometry.href,'/components/content#ref-tree-item');
        result.colors=(await evaluate(`(${contrastSamples.toString()})('[data-deep-tree=${id}] .tree-label')`)).text;
        for(const sample of result.colors)assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
        await evaluate(`${root(id)}.scrollIntoView({block:'center'})`);await screenshot(`${id}-${width}`);
      }
      await key('Enter');
      await until(`location.pathname==='/components/content'&&location.hash==='#ref-tree-item'`);
      await call('Emulation.setScriptExecutionDisabled',{value:true});
      try {
        await navigate('/trees');
        for(const id of ['dark','light']) {
          const native=await evaluate(`({rows:${root(id)}.querySelectorAll('[role=treeitem]').length,links:[...${root(id)}.querySelectorAll('a')].map(link=>({href:link.getAttribute('href'),visible:link.checkVisibility(),tabIndex:link.tabIndex})),disabled:${target(id,13)}.disabled,overflow:document.documentElement.scrollWidth-innerWidth})`);
          report.cases.push({width,palette:id,native});
          assert.equal(native.rows,15);assert.equal(native.links.length,2);assert.equal(native.disabled,true);
          assert.ok(native.links.every(link=>link.visible&&link.tabIndex===0&&link.href.startsWith('/')));
          assert.ok(native.overflow<=0);assert.equal((await ax(row(id,12))).properties.level,13);
        }
        await evaluate(`${root('light')}.scrollIntoView({block:'center'})`);await screenshot(`native-${width}`);
      } finally {await call('Emulation.setScriptExecutionDisabled',{value:false});}
    }
  } finally {
    await writeFile(join(artifacts,`catalog-${mode}-deep-tree.json`),JSON.stringify(report,null,2));
  }
}
