import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
const require=createRequire(resolve('../../bml/bml-design/package.json'));
const {HocuspocusProvider,HocuspocusProviderWebsocket}=require('@hocuspocus/provider');
const WebSocket=require('ws'),Y=require('yjs');
const urls=[process.env.WORKOPS_COLLABORATION_URL,process.env.WORKOPS_COLLABORATION_PEER_URL];
const api=process.env.WORKOPS_CONTENT_URL;
for(const value of [...urls,api])assert.ok(['127.0.0.1','localhost','[::1]'].includes(new URL(value).hostname),'Use the isolated local fixture');
const room=process.env.WORKOPS_COLLABORATION_ROOM??'11111111-2222-4333-8444-555555555555.6';
const [id,version]=room.split('.');
assert.match(id,/^[0-9a-f-]{36}$/i);assert.match(version,/^[1-9][0-9]*$/);
const tokenA=process.env.WORKOPS_COLLABORATION_TOKEN_A??'editor-a-new';
const tokenB=process.env.WORKOPS_COLLABORATION_TOKEN_B??'editor-c';
const people=[];
const participant=(url,token)=>{
  const person={doc:new Y.Doc(),closed:[],errors:[]};
  person.socket=new HocuspocusProviderWebsocket({url,WebSocketPolyfill:WebSocket,autoConnect:false,initialDelay:0,minDelay:20,delay:20,maxDelay:100,maxAttempts:2,
    onClose:({event})=>{person.closed.push(event.code);if([1009,4401,4403].includes(event.code))person.socket.disconnect();}});
  person.provider=new HocuspocusProvider({name:room,document:person.doc,websocketProvider:person.socket,token,
    onAuthenticationFailed:({reason})=>person.errors.push(reason)});
  person.provider.attach();people.push(person);
  void person.socket.connect().catch(error=>person.errors.push(String(error)));
  return person;
};
const wait=async(check,label)=>{
  const deadline=Date.now()+10000;
  while(Date.now()<deadline){
    if(people.some(person=>person.closed.includes(1009)))throw new Error(`Document transfer rejected as too large: ${label}`);
    if(await check())return;
    await new Promise(resolve=>setTimeout(resolve,20));
  }
  throw new Error(`Timed out: ${label}; ${JSON.stringify(people.map(person=>({closed:person.closed,errors:person.errors,length:person.doc.getText('large').length,unsynced:person.provider.unsyncedChanges})))}`);
};
try{
  const a=participant(urls[0],tokenA),b=participant(urls[1],tokenB);
  await wait(()=>[a,b].every(person=>person.provider.isAuthenticated&&person.provider.isSynced),'two large-document editors ready');
  const text='Large collaborative document. '.repeat(45000);
  assert.ok(Buffer.byteLength(text)>1024*1024,'exercise a complete update above the ordinary WebSocket limit');
  a.doc.getText('large').insert(0,text);
  await wait(()=>b.doc.getText('large').toString()===text&&!a.provider.hasUnsyncedChanges,'large update persists and reaches a different UI instance');
  b.doc.getText('large').insert(text.length,' Final edit.');
  await wait(()=>a.doc.getText('large').toString()===text+' Final edit.'&&!b.provider.hasUnsyncedChanges,'edits retain the complete large document');
  const response=await fetch(`${api}/api/v1/content/metadata/${id}/document/collaboration?version=${version}`,{headers:{Authorization:`Bearer ${tokenA}`}});
  assert.equal(response.status,200);
  const durable=new Y.Doc();
  try{Y.applyUpdate(durable,new Uint8Array(await response.arrayBuffer()));assert.equal(durable.getText('large').toString(),text+' Final edit.');}
  finally{durable.destroy();}
  a.provider.destroy();a.socket.destroy();a.doc.destroy();
  const reopened=participant(urls[0],tokenA);
  await wait(()=>reopened.provider.isSynced&&reopened.doc.getText('large').toString()===text+' Final edit.','opening persisted large state completes both sync directions');
  assert.ok(people.every(person=>person.errors.length===0));
  process.stdout.write('Large WorkOps collaboration updates, cross-instance edits, persistence and reopening passed.\n');
}finally{
  for(const person of people){person.provider.destroy();person.socket.destroy();person.doc.destroy();}
}
