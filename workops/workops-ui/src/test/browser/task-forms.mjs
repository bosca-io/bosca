import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname));
const project = process.env.ATLAS_WORKOPS_TEST_PROJECT, program = process.env.ATLAS_WORKOPS_TEST_PROGRAM;
const profile = process.env.ATLAS_WORKOPS_TEST_PROFILE, script = process.env.ATLAS_WORKOPS_TEST_SCRIPT;
assert.ok(project && program && profile && script);
const reports = resolve('build/reports/task-forms-browser', new URL(base).port);
await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0), context = owned.connection.contexts()[0], page = context.pages()[0];
const errors = [];
const choiceRequests=[];
let intentionalLookupFailure = false;
page.on('request', request => {
  if(new URL(request.url()).pathname==='/_bml/contract/WorkOpsFormChoices/choices') choiceRequests.push(JSON.parse(request.postData()));
});
page.on('pageerror', error => errors.push(error.message));
page.on('console', message => {
  if(message.type()!=='error') return;
  if(intentionalLookupFailure && message.location().url.includes('/_bml/contract/WorkOpsFormChoices/choices') && message.text().includes('status of 500')) return;
  errors.push(message.text());
});
const gql = async (query, variables = {}) => {
  const result = await page.evaluate(async ({ query, variables }) => {
    const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) }); return response.json();
  }, { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
try {
  await page.goto(base + '/login');
  await page.locator('input[name=identifier]').fill('admin');
  await page.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await page.locator('button[type=submit]').click(); await page.waitForURL(url => url.pathname !== '/login');
  const choices = (await gql('{ workOps { tasks { taskTypes { id name } priorities { id name } } } }')).workOps.tasks;
  const type = choices.taskTypes.find(choice=>choice.name==='Task') ?? choices.taskTypes[0], priority = choices.priorities[0];
  const candidate = (await gql('mutation { profiles { add(linkToPrincipal:false,profile:{name:"Form Candidate",visibility:PUBLIC,searchable:true,attributes:[]}) { id } } }')).profiles.add.id;
  await gql('mutation { storageSystems { reindex(storageName:"Admin Search Index",deleteFirst:false,metadata:false,collections:false,profiles:true) } }');
  await page.waitForFunction(async () => {
    const response = await fetch('/graphql', { method:'POST', headers:{'Content-Type':'application/json',Authorization:`Bearer ${await globalThis.boscaAuth.getToken()}`}, body:JSON.stringify({query:'{ search { search(query:{query:"Form Candidate",storageSystemName:"Admin Search Index",filter:["_type = \\"profile\\""],limit:20,offset:0}) { documents { profile { name } } } } }'}) });
    const result=await response.json(); return result.data?.search.search.documents.some(row=>row.profile?.name==='Form Candidate');
  }, { timeout:30000 });
  const form = {
    key:'atlas.configured-task', name:'Configured task', type:'WORK_OPS', configuration:{workOps:{}}, schema:{type:'object',properties:{
      projectId:{type:'string'},summary:{type:'string',minLength:3},descriptionMarkdown:{type:'string'},
      assigneeProfileId:{type:'string',default:profile},priorityId:{type:'string',default:priority.id},taskTypeId:{type:'string',default:type.id},
      queryChoice:{type:'string'},scriptChoice:{type:'string'},attachment:{type:['string','null']},illustration:{type:['string','null']},
    },required:['projectId','summary']}, uiSchema:{version:1,layout:[{type:'section',label:'Task',children:[
      {type:'field',property:'projectId',control:'workops-select',workOpsEntity:'projects',label:'Project'},
      {type:'field',property:'summary',control:'text-input',label:'Summary'},
      {type:'field',property:'descriptionMarkdown',control:'textarea',label:'Description'},
      {type:'field',property:'assigneeProfileId',control:'profile-select',label:'Assignee'},
      {type:'field',property:'priorityId',control:'graphql-select',label:'Priority',graphql:{query:'{ workOps { tasks { priorities { id name } } } }',resultPath:'workOps.tasks.priorities',valuePath:'id',labelPath:'name'}},
      {type:'field',property:'taskTypeId',control:'api-script-select',label:'Task type',apiScript:{key:'form-choices',method:'GET',params:{choice:type.id,prefix:'GET choice',enabled:false},resultPath:'choices',valuePath:'id',labelPath:'label'}},
      {type:'field',property:'queryChoice',control:'graphql-select',label:'Query choice',graphql:{query:'query FormProfile($q:String!){ search { search(query:{query:$q,storageSystemName:"Admin Search Index",filter:["_type = \\"profile\\""],limit:20,offset:0}) { documents { profile { id name } } } } }',searchVariable:'q',resultPath:'search.search.documents',valuePath:'profile.id',labelPath:'profile.name'}},
      {type:'field',property:'scriptChoice',control:'api-script-select',label:'Script choice',apiScript:{key:'form-choices',method:'POST',params:{choice:'post-result',prefix:'POST choice',enabled:false},searchParam:'q',resultPath:'choices',valuePath:'id',labelPath:'label'}},
      {type:'field',property:'attachment',control:'file-upload',label:'Attachment',accept:'.txt'},
      {type:'field',property:'illustration',control:'image-upload',label:'Illustration'},
    ]}]},
  };
  await gql('mutation Save($input:FormSchemaInput!){ formSchemasMutation { save(input:$input) { id version } } }', {input:form});
  await gql('mutation Update($id:UUID!,$input:WorkOpsProjectInput!){ workOps { projects { update(id:$id,input:$input,expectedVersion:0) { id } } } }', {id:project,input:{programId:program,key:'FORM',name:'Forms project',ownerProfileId:profile,taskCreationFormSchemaKey:form.key}});
  const choose = async (dialog, property, query, label) => {
    const field=dialog.locator(`[data-schema-property=${property}]`);
    await field.locator('[data-bml-ref=trigger]').click();
    await field.locator('[data-bml-ref=query]').fill(query);
    await field.getByRole('option',{name:label,exact:true}).click();
  };
  for(const appearance of ['dark','light']) {
    await context.addCookies([{name:'git_ui_appearance',value:appearance,url:base}]);
    for(const width of [1920,1024,768,390]) {
      const requestStart=choiceRequests.length;
      await page.setViewportSize({width,height:1000});
      await page.goto(`${base}/workops/tasks?new=1&project=${project}`);
      const dialog=page.getByRole('dialog',{name:'New task',exact:true}); await dialog.waitFor({state:'visible'});
      for(const property of ['attachment','illustration']) for(const ref of ['fileProgress','fileCancel']) {
        assert.equal(await dialog.locator(`[data-schema-property=${property}] [data-bml-ref=${ref}]`).evaluate(element=>getComputedStyle(element).display),'none',`${property} ${ref} initially hidden`);
      }
      await page.waitForFunction(({priority})=>document.querySelector('[data-schema-property=priorityId] [data-bml-ref=selection]')?.textContent.trim()===priority,{priority:priority.name});
      await page.waitForFunction(()=>document.querySelector('[data-schema-property=taskTypeId] [data-bml-ref=selection]')?.textContent.trim()==='GET choice initial');
      const typeKey=await dialog.locator('[data-schema-property=taskTypeId]').getAttribute('data-schema-field-key');
      const priorityKey=await dialog.locator('[data-schema-property=priorityId]').getAttribute('data-schema-field-key');
      const providerCalls=key=>choiceRequests.slice(requestStart).filter(args=>args[2]===key);
      await choose(dialog,'taskTypeId','initial','GET choice initial');
      await choose(dialog,'priorityId',priority.name,priority.name);
      assert.equal(providerCalls(typeKey).length,1,'opening/filtering a static API-script source keeps its initial result');
      assert.equal(providerCalls(priorityKey).length,1,'static GraphQL choices load once');
      await choose(dialog,'assigneeProfileId','Form Candidate','Form Candidate');
      await choose(dialog,'queryChoice','Form Candidate','Form Candidate');
      await choose(dialog,'scriptChoice','picked','POST choice picked');
      const title=`Configured task ${appearance} ${width}`;
      await dialog.locator('[data-schema-property=summary] input').fill(title);
      await dialog.locator('[data-schema-property=descriptionMarkdown] textarea').fill('Configured field draft');
      const bytes=Buffer.from('Configured file bytes');
      await dialog.locator('[data-schema-property=attachment] input[type=file]').setInputFiles({name:'note.txt',mimeType:'text/plain',buffer:bytes});
      const svg='<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"><rect width="8" height="8" fill="blue"/></svg>';
      await dialog.locator('[data-schema-property=illustration] input[type=file]').setInputFiles({name:'image.svg',mimeType:'image/svg+xml',buffer:Buffer.from(svg)});
      await page.waitForFunction(()=>document.querySelector('[data-schema-property=illustration] img')?.naturalWidth===8);
      for(const property of ['attachment','illustration']) for(const ref of ['fileProgress','fileCancel']) {
        assert.equal(await dialog.locator(`[data-schema-property=${property}] [data-bml-ref=${ref}]`).evaluate(element=>getComputedStyle(element).display),'none',`${property} ${ref} hidden after reading`);
      }
      const draft=await dialog.locator('input[name=attributes]').inputValue().then(JSON.parse);
      assert.equal(draft.attachment,`data:text/plain;base64,${bytes.toString('base64')}`);
      assert.equal(draft.illustration,`data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`);
      assert.equal(draft.assigneeProfileId,candidate); assert.equal(draft.queryChoice,candidate); assert.equal(draft.scriptChoice,'post-result');
      if(appearance==='dark' && width===1920) {
        intentionalLookupFailure=true;
        await gql('mutation Disable($id:UUID!){ scripts { disableScript(id:$id) } }',{id:script});
        const field=dialog.locator('[data-schema-property=scriptChoice]'); await field.locator('[data-bml-ref=trigger]').click(); await field.locator('[data-bml-ref=query]').fill('unavailable');
        await page.waitForFunction(()=>document.querySelector('[data-schema-property=scriptChoice] [data-schema-choices-status]')?.textContent.includes('Could not load choices'));
        assert.equal(await field.locator('select').inputValue(),'post-result');
        await gql('mutation Enable($id:UUID!){ scripts { enableScript(id:$id) } }',{id:script});
        await field.locator('[data-bml-ref=query]').fill('retry'); await field.getByRole('option',{name:'POST choice retry',exact:true}).click();
        intentionalLookupFailure=false;
        await dialog.locator('[data-schema-property=priorityId] select').evaluate(select=>{select.add(new Option('Unavailable priority','00000000-0000-0000-0000-000000000000'));select.value='00000000-0000-0000-0000-000000000000';select.dispatchEvent(new Event('change',{bubbles:true}));});
        await dialog.getByRole('button',{name:'Create task',exact:true}).click();
        await dialog.getByRole('alert').first().waitFor({state:'visible'});
        assert.equal(await dialog.locator('[data-schema-property=summary] input').inputValue(),title);
        assert.equal(JSON.parse(await dialog.locator('input[name=attributes]').inputValue()).attachment,draft.attachment);
        await choose(dialog,'priorityId',priority.name,priority.name);
        const beforeReload=await dialog.locator('[data-workops-form-key]').getAttribute('data-bml-preserve');
        await dialog.getByRole('button',{name:'Retry loading task form',exact:true}).click();
        await page.waitForFunction(({previous})=>document.querySelector('[data-workops-form-key]')?.dataset.bmlPreserve!==previous,{previous:beforeReload});
        await page.waitForFunction(()=>document.querySelector('[data-schema-property=taskTypeId] [data-bml-ref=selection]')?.textContent.trim()==='GET choice initial');
        assert.equal(providerCalls(typeKey).length,2,'recovery refreshes a static provider without a schema-version change');
        assert.equal(await dialog.getByRole('button',{name:'Retry loading task form',exact:true}).count(),0);
        const changedForm=await gql('mutation Save($input:FormSchemaInput!){ formSchemasMutation { save(input:$input) { version } } }',{input:{...form,name:'Updated configured task'}});
        const queryField=dialog.locator('[data-schema-property=queryChoice]');
        await queryField.locator('[data-bml-ref=trigger]').click(); await queryField.locator('[data-bml-ref=query]').fill('changed form');
        await page.waitForFunction(()=>document.querySelector('[data-schema-property=queryChoice] [data-schema-choices-status]')?.textContent.includes('form changed'));
        await queryField.locator('[data-bml-ref=query]').press('Escape');
        await dialog.getByRole('button',{name:'Retry loading task form',exact:true}).click();
        await page.waitForFunction(({version})=>Number(document.querySelector('[data-workops-form-version]')?.dataset.workopsFormVersion)===version,{version:changedForm.formSchemasMutation.save.version});
        assert.equal(await dialog.locator('[data-schema-property=summary] input').inputValue(),title);
        assert.equal(JSON.parse(await dialog.locator('input[name=attributes]').inputValue()).attachment,draft.attachment);
        await choose(dialog,'queryChoice','Form Candidate','Form Candidate');
        await page.waitForFunction(()=>document.querySelector('[data-schema-property=taskTypeId] [data-bml-ref=selection]')?.textContent.trim()==='GET choice initial');
        assert.equal(providerCalls(typeKey).length,3);

      }
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
      await page.screenshot({path:resolve(reports,`${appearance}-${width}.png`),fullPage:true});
      await dialog.getByRole('button',{name:'Create task',exact:true}).click(); await dialog.waitFor({state:'hidden'});
      await page.getByRole('link',{name:'Refresh tasks',exact:true}).click();
      const taskLink=page.getByRole('link',{name:title,exact:true}); await taskLink.waitFor();
      const id=new URL(await taskLink.getAttribute('href'),base).pathname.split('/').at(-1);
      const saved=(await gql('query Task($id:UUID!){ workOps { tasks { task(id:$id) { summary assigneeProfileId priority { id } taskType { id } customFields } } } }',{id})).workOps.tasks.task;
      assert.equal(saved.summary,title); assert.equal(saved.assigneeProfileId,candidate);
      assert.equal(saved.priority.id,priority.id); assert.equal(saved.taskType.id,type.id);
      assert.equal(saved.customFields.attachment,draft.attachment); assert.equal(saved.customFields.illustration,draft.illustration);
      assert.equal(saved.customFields.queryChoice,candidate); assert.equal(saved.customFields.scriptChoice,'post-result');
    }
  }
  assert.deepEqual(errors,[]);
  console.log('Configured task form adapters passed real script/profile/GraphQL lookups, files, failed-save recovery and both palettes at four widths.');
} catch(error) { await page.screenshot({path:resolve(reports,'failure.png'),fullPage:true}).catch(()=>{}); console.error(await page.locator('body').innerText().catch(()=>'')); throw error; }
finally { await owned.close(); }
