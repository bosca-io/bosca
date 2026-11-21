import { createRequire } from 'node:module';
import { resolve } from 'node:path';
const require = createRequire(resolve('../../bml/bml-runtime/package.json'));
const { build } = require('esbuild');
// Capture the exact provider used by Studio, before replacing its Node server with WorkOps BML.
const source = `
  import * as Y from 'yjs';
  import { Awareness } from 'y-protocols/awareness';
  import { AuthenticationMessage } from './node_modules/@hocuspocus/provider/src/OutgoingMessages/AuthenticationMessage.ts';
  import { SyncStepOneMessage } from './node_modules/@hocuspocus/provider/src/OutgoingMessages/SyncStepOneMessage.ts';
  import { UpdateMessage } from './node_modules/@hocuspocus/provider/src/OutgoingMessages/UpdateMessage.ts';
  import { AwarenessMessage } from './node_modules/@hocuspocus/provider/src/OutgoingMessages/AwarenessMessage.ts';
  import * as encoding from 'lib0/encoding';
  import { writeAuthenticated, writePermissionDenied } from '@hocuspocus/common';
  const name = '11111111-2222-4333-8444-555555555555.3';
  const document = new Y.Doc(); document.clientID = 7;
  const awareness = new Awareness(document); awareness.setLocalState({user:{name:'Fixture author'}});
  let update; document.on('update', value=>update=value); document.getText('body').insert(0,'Shared');
  const capture = (Type,args) => { const message = new Type(); message.get({documentName:name,...args}); return Array.from(message.toUint8Array()); };
  const frame = (type,write) => { const encoder=encoding.createEncoder();encoding.writeVarString(encoder,name);encoding.writeVarUint(encoder,type);write(encoder);return Array.from(encoding.toUint8Array(encoder)); };
  export const fixtures = {name,authentication:capture(AuthenticationMessage,{token:'fixture-token'}),
    sync:capture(SyncStepOneMessage,{document}),update:capture(UpdateMessage,{update}),
    awareness:capture(AwarenessMessage,{awareness,clients:[document.clientID]}),
    authenticated:frame(2,encoder=>writeAuthenticated(encoder,'read-write')),
    denied:frame(2,encoder=>writePermissionDenied(encoder,'permission-denied')),
    acknowledged:frame(8,encoder=>encoding.writeVarInt(encoder,1))};
  awareness.destroy(); document.destroy();
`;
const bundle = await build({stdin:{contents:source,resolveDir:resolve('../../bml/bml-design')},bundle:true,write:false,format:'esm',platform:'node'});
const { fixtures } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
process.stdout.write(JSON.stringify(fixtures,null,2)+'\n');
