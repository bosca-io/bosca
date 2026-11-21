export async function automationFixture(gql, fixtureProject, readerId) {
  const parent = (await gql('query($id:UUID!){workOps{projects{project(id:$id){ownerProfileId}}}}', { id: fixtureProject })).workOps.projects.project;
  const profile = parent.ownerProfileId;
  const portfolio = (await gql('mutation($input:WorkOpsPortfolioInput!){workOps{portfolios{create(input:$input){id}}}}', { input: { key: 'AUTO', name: 'Automation portfolio', ownerProfileId: profile } })).workOps.portfolios.create.id;
  const program = (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id}}}}', { input: { portfolioId: portfolio, key: 'AUTO', name: 'Automation program', ownerProfileId: profile } })).workOps.programs.create.id;
  const project = (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id}}}}', { input: { programId: program, key: 'AUTO', name: 'Automation project', ownerProfileId: profile } })).workOps.projects.create.id;
  const scopes = [{ scope: 'GLOBAL', scopeId: null }, { scope: 'PORTFOLIO', scopeId: portfolio }, { scope: 'PROGRAM', scopeId: program }, { scope: 'PROJECT', scopeId: project }];
  const fields = 'id name description enabled scope scopeId trigger conditions actions failureMode maxFiresPerTaskPerHour executionLogRetentionDays runAsProfileId version';
  const read = async c => (await gql(`query($scope:String!,$id:UUID){workOps{automation{rules(scope:$scope,scopeId:$id){${fields}}}}}`, { scope: c.scope, id: c.scopeId })).workOps.automation.rules;
  const input = (c, name, extra = {}) => ({ scope: c.scope, scopeId: c.scopeId, name, description: 'Automation <copy>', enabled: false, trigger: { type: 'TaskUpdated', filter: 'summary ~ "atlas"' }, conditions: [], actions: [], failureMode: 'CONTINUE', maxFiresPerTaskPerHour: 7, executionLogRetentionDays: 12, runAsProfileId: profile, ...extra });
  const create = async (c, name, extra = {}) => (await gql('mutation($input:WorkOpsAutomationRuleInput!){workOps{automation{create(input:$input){id}}}}', { input: input(c, name, extra) })).workOps.automation.create.id;
  const update = async (rule, extra = {}) => {
    const { id, version, ...stored } = rule;
    await gql('mutation($id:UUID!,$input:WorkOpsAutomationRuleInput!,$version:Long!){workOps{automation{update(id:$id,input:$input,expectedVersion:$version){id}}}}', { id, version, input: { ...stored, ...extra } });
  };
  const logs = async (id, offset = 0, limit = 100) => (await gql('query($id:UUID!,$offset:Long!,$limit:Int!){workOps{automation{executionLogs(ruleId:$id,offset:$offset,limit:$limit){id outcome startedAt finishedAt durationMs errorMessage taskId}}}}', { id, offset, limit })).workOps.automation.executionLogs;
  const adminGroup = (await gql('{security{groups{find(nameOrDescription:"administrators",offset:0,limit:100,type:SYSTEM){id name}}}}')).security.groups.find.find(value => value.name === 'administrators')?.id;
  if (!adminGroup) throw new Error('The isolated administrator group is missing.');
  let membership = false; const grants = new Map();
  const access = async (c, action, add) => {
    if (c.scope === 'GLOBAL') {
      await gql(`mutation($group:UUID!,$principal:UUID!){security{${add ? 'add' : 'remove'}PrincipalGroup(groupId:$group,principalId:$principal)}}`, { group: adminGroup, principal: readerId }); membership = add;
    } else {
      const root = c.scope.toLowerCase() + 's';
      await gql(`mutation($id:UUID!,$group:UUID!){workOps{${root}{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:${action})}}}`, { id: c.scopeId, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
      const key = c.scope + ':' + action; if (add) grants.set(key, { c, action }); else grants.delete(key);
    }
  };
  const cleanup = async () => {
    if (membership) await access(scopes[0], 'MANAGE', false);
    for (const { c, action } of [...grants.values()]) await access(c, action, false);
  };
  return { scopes, profile, project, read, input, create, update, logs, access, cleanup };
}
