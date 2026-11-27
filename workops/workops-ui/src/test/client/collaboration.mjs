import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
const require=createRequire(resolve('../../bml/bml-design/package.json'));
const {HocuspocusProvider,HocuspocusProviderWebsocket}=require('@hocuspocus/provider');
const WebSocket=require('ws'),Y=require('yjs');
const {yXmlFragmentToProsemirrorJSON}=require('y-prosemirror');
const url=process.env.WORKOPS_COLLABORATION_URL,peerUrl=process.env.WORKOPS_COLLABORATION_PEER_URL,api=process.env.WORKOPS_CONTENT_URL;
for(const value of [url,peerUrl,api]) assert.ok(['127.0.0.1','localhost','[::1]'].includes(new URL(value).hostname),'Use the isolated local fixture');
const id='11111111-2222-4333-8444-555555555555',people=[],timers=[];
const wait=async(check,label,timeout=5000)=>{
  const deadline=Date.now()+timeout;
  while(Date.now()<deadline){if(await check())return;await new Promise(resolve=>setTimeout(resolve,20));}
  throw new Error(`Timed out: ${label}\n${JSON.stringify(people.map(p=>({name:p.name,authenticated:p.provider.isAuthenticated,synced:p.provider.isSynced,unsynced:p.provider.unsyncedChanges,closed:p.closed,errors:p.errors,text:p.doc.getText('body').toString()})))}`);
};
const participant=(token,version=3,messageReconnectTimeout=30000)=>{
  const person={name:`${id}.${version}`,token,doc:new Y.Doc(),closed:[],errors:[],authentications:0};
  person.socket=new HocuspocusProviderWebsocket({url:token==='editor-b'?peerUrl:url,WebSocketPolyfill:WebSocket,autoConnect:false,messageReconnectTimeout,initialDelay:0,minDelay:20,delay:20,maxDelay:100,maxAttempts:2,
    onClose:({event})=>{if([4401,4403].includes(event.code))person.socket.disconnect();}});
  person.provider=new HocuspocusProvider({name:person.name,document:person.doc,websocketProvider:person.socket,token:()=>person.token,
    onAuthenticated:()=>person.authentications++,onClose:({event})=>person.closed.push(event.code),onAuthenticationFailed:({reason})=>person.errors.push(reason)});
  person.provider.attach();
  people.push(person);void person.socket.connect().catch(error=>person.errors.push(String(error)));
  return person;
};
const ready=person=>person.provider.isAuthenticated&&person.provider.isSynced;
const control=async(tokens)=>{
  const response=await fetch(api+'/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({tokens})});
  assert.equal(response.status,200);
};
const counters=async()=>await (await fetch(api+'/control')).json();
const snapshot=async(version=3)=>{
  const response=await fetch(`${api}/api/v1/content/metadata/${id}/document/collaboration?version=${version}`,{headers:{Authorization:'Bearer editor-a-new'}});
  assert.equal(response.status,200);const doc=new Y.Doc();Y.applyUpdate(doc,new Uint8Array(await response.arrayBuffer()));
  const text=doc.getText('body').toString();doc.destroy();return text;
};
try{
  const a=participant('editor-a'),b=participant('editor-b');
  await wait(()=>ready(a)&&ready(b),'two authenticated Studio providers sync');
  for(const person of [a,b]){
    const seeded=yXmlFragmentToProsemirrorJSON(person.doc.getXmlFragment('default'));
    assert.equal(seeded.content.length,3,'canonical content is seeded once before simultaneous editors join');
    assert.equal(seeded.content[0].attrs.level,1);
    assert.equal(seeded.content[0].content[0].text,'Existing document');
    assert.equal(seeded.content[1].content[0].marks[0].type,'bold');
    assert.deepEqual(seeded.content[2].attrs,{name:'details',version:4,references:['one','two']});
  }
  assert.equal((await counters()).seeds['3'],1,'one canonical read for the shared room');
  a.provider.awareness.setLocalStateField('user',{name:'Alice'});b.provider.awareness.setLocalStateField('user',{name:'Bob'});
  await wait(()=>a.provider.awareness.getStates().get(b.doc.clientID)?.user?.name==='Bob','remote awareness');
  a.doc.getText('body').insert(0,'From Alice');
  await wait(()=>b.doc.getText('body').toString()==='From Alice'&&!a.provider.hasUnsyncedChanges,'first persisted update and acknowledgement');
  b.doc.getText('body').insert(b.doc.getText('body').length,' and Bob');
  await wait(()=>a.doc.getText('body').toString()==='From Alice and Bob'&&!b.provider.hasUnsyncedChanges,'second editor update');
  const external=new Y.Doc();
  try{
    Y.applyUpdate(external,Y.encodeStateAsUpdate(a.doc));external.getMap('external').set('source','Content writer');
    const written=await fetch(`${api}/api/v1/content/metadata/${id}/document/collaboration?version=3`,{
      method:'PUT',headers:{Authorization:'Bearer editor-a'},body:Y.encodeStateAsUpdate(external)});
    assert.equal(written.status,202);
    await wait(()=>[a,b].every(person=>person.doc.getMap('external').get('source')==='Content writer'),'periodic reconciliation repairs a change without a pubsub notification',35000);
  }finally{external.destroy();}
  await control(['editor-a','editor-a-new','editor-b','editor-c']);a.token='editor-a-new';await a.provider.sendToken();
  await wait(()=>a.authentications>=2,'refreshed token authentication');
  await control(['editor-a-new','editor-b','editor-c']);
  await new Promise(resolve=>setTimeout(resolve,180));assert.equal(a.closed.length,0,'old credentials do not close a refreshed session');
  assert.equal(await snapshot(),'From Alice and Bob');
  await control(['editor-a-new','editor-c']);
  await wait(()=>b.closed.includes(4403),'permission revocation closes an idle participant');
  await wait(()=>!a.provider.awareness.getStates().has(b.doc.clientID),'revocation removes awareness');
  b.doc.getText('body').insert(0,'Denied edit ');
  assert.equal(await snapshot(),'From Alice and Bob');
  const c=participant('editor-c',4,500);await wait(()=>ready(c),'independent document version');
  let pulse=0;
  timers.push(setInterval(()=>c.provider.awareness.setLocalStateField('pulse',++pulse),100));
  const emptySeed=yXmlFragmentToProsemirrorJSON(c.doc.getXmlFragment('default'));
  assert.equal(emptySeed.content.length,2,'Content() serializes as an empty object and receives a valid initial heading and body');
  assert.equal(emptySeed.content[0].content[0].text,'Existing document');
  c.doc.getText('body').insert(0,'Separate version');
  await wait(()=>!c.provider.hasUnsyncedChanges,'version persistence');
  assert.equal(await snapshot(4),'Separate version');assert.equal(await snapshot(),'From Alice and Bob');
  await new Promise(resolve=>setTimeout(resolve,1200));
  assert.equal(c.closed.length,0,'a lone provider receives its accepted awareness heartbeat and stays connected');
  const opensBefore=(await counters()).opens['3'];
  a.socket.disconnect();await wait(()=>a.closed.some(code=>[1000,1005].includes(code)),'disconnect before reconnect');
  await wait(async()=>(await counters()).rooms===1,'the last participant releases its document room');
  a.doc.getText('body').insert(a.doc.getText('body').length,' offline');
  void a.socket.connect().catch(error=>a.errors.push(String(error)));
  await wait(()=>ready(a)&&!a.provider.hasUnsyncedChanges,'reconnect retains and persists the local document');
  assert.equal((await counters()).opens['3'],opensBefore+1,'the last disconnect releases the room and reconnect reloads Content');
  assert.equal((await counters()).seeds['3'],1,'persisted CRDT history is never reseeded on reconnect');
  assert.equal(await snapshot(),'From Alice and Bob offline');
  const denied=participant('denied',5);await wait(()=>denied.closed.includes(4403),'unauthorized participant rejected');
  assert.equal((await counters()).gets['5']??0,0,'denied participants do not load a document');
  for(const [value,code] of [['not binary',1003],[new Uint8Array([255]),1002]]){
    const socket=new WebSocket(url);await new Promise((resolve,reject)=>{socket.once('open',resolve);socket.once('error',reject);});
    const closed=new Promise(resolve=>socket.once('close',resolve));socket.send(value);assert.equal(await closed,code);
  }
  assert.ok(a.errors.length===0&&c.errors.length===0,JSON.stringify([a.errors,c.errors]));
  a.doc.getXmlFragment('default').get(2).setAttribute('references',['one',null,7,'','two']);
  await wait(()=>!a.provider.hasUnsyncedChanges,'persisted legacy references for shared editor normalization');
  process.stdout.write('WorkOps BML collaboration: two UI instances share Studio client edits and awareness through pub/sub, with persistence, token refresh, revocation, versions and reconnect.\n');
}finally{
  for(const timer of timers)clearInterval(timer);
  for(const person of people){person.provider.destroy();person.socket.destroy();person.doc.destroy();}
}
