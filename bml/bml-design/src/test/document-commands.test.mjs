import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom'), { build } = require('esbuild');
const bundle = await build({ stdin: { contents: `export { Editor } from '@tiptap/core'; export { default as StarterKit } from '@tiptap/starter-kit';
 export * from './src/main/client/helpers/document-commands.ts';`, resolveDir: process.cwd() },
 bundle: true, write: false, format: 'esm', platform: 'browser' });
const { Editor, StarterKit, documentCommands } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
async function fixture(check) {
 const window = new Window({ url: 'http://commands.test/' });
 for (const name of ['window','document','Node','Element','HTMLElement','Event','KeyboardEvent','MutationObserver','navigator','getComputedStyle','innerHeight']) Object.defineProperty(globalThis, name, {
   configurable: true, value: name === 'getComputedStyle' ? window[name].bind(window) : window[name],
 });
 globalThis.requestAnimationFrame = window.requestAnimationFrame.bind(window); globalThis.cancelAnimationFrame = window.cancelAnimationFrame.bind(window);
 const root=document.createElement('div'), surface=document.createElement('div'); root.append(surface); document.body.append(root);
 let editor; const selected=[];
 const commands=()=>['Heading 2','Heading 3','Bold','Italic'].map(label=>({label,active:()=>label==='Italic', run:range=>{
   selected.push(label); editor.chain().focus().deleteRange(range).insertContent(label).run();
 }}));
 editor=new Editor({element:surface,extensions:[StarterKit,documentCommands(root,'test',commands)],content:'<p></p>'});
 const wait=()=>new Promise(resolve=>setTimeout(resolve,30));
 const key=value=>{const event=new KeyboardEvent('keydown',{key:value,bubbles:true,cancelable:true});editor.view.dom.dispatchEvent(event);return event;};
 const type=async value=>{editor.commands.focus(); await wait();editor.commands.insertContent(value);await wait();};
 try { await check({root,editor,key,type,wait,selected,menu:()=>root.querySelector('.document-commands')}); }
 finally {editor.destroy(); await window.happyDOM.abort();}
}
test('slash commands filter and wrap keyboard choice before executing the selected document transaction',()=>fixture(async({editor,type,key,menu,selected})=>{
 await type('/heading'); assert.equal(menu().hidden,false); assert.equal(menu().querySelectorAll('[role=option]').length,2);
 assert.equal(key('ArrowUp').defaultPrevented,true); assert.equal(menu().querySelector('[aria-selected=true]').textContent,'Heading 3');
 assert.equal(key('Enter').defaultPrevented,true); assert.deepEqual(selected,['Heading 3']); assert.equal(editor.getText(),'Heading 3'); assert.equal(menu().hidden,true);
}));
test('empty results retain literal input and Escape dismisses until the query changes',()=>fixture(async({editor,type,key,menu,wait})=>{
 await type('/none'); assert.equal(menu().textContent,'No commands found.'); assert.equal(key('Escape').defaultPrevented,true);
 editor.view.dispatch(editor.state.tr); await wait(); assert.equal(menu().hidden,true); assert.equal(editor.getText(),'/none');
 editor.commands.setContent('<p></p>'); await type('/'); assert.equal(menu().hidden,false);
}));
test('pointer selection retains the editor transaction range and destroys popup controls on unmount',()=>fixture(async({root,editor,type,menu,selected,wait})=>{
 await type('/ital'); const option=menu().querySelector('[role=option]'); assert.equal(option.dataset.active,'true');
 option.click(); await wait(); assert.deepEqual(selected,['Italic']); assert.equal(editor.getText(),'Italic');
 editor.destroy(); assert.equal(root.querySelector('.document-commands'),null); assert.doesNotThrow(()=>option.click()); assert.deepEqual(selected,['Italic']);
}));
test('readonly editors cannot open or activate formatting commands',()=>fixture(async({editor,type,key,menu,selected})=>{
 editor.setEditable(false); await type('/'); assert.equal(menu(),null); assert.equal(key('Enter').defaultPrevented,false); assert.deepEqual(selected,[]);
}));
