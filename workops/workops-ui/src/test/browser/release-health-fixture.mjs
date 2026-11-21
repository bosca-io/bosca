export async function releaseHealthFixture(gql, fixtureProject) {
  const parent = (await gql('query($id:UUID!){workOps{projects{project(id:$id){ownerProfileId program{portfolio{id}}}}}}', { id: fixtureProject })).workOps.projects.project;
  const program = (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id}}}}', { input: { key: 'RHEALTH', name: 'Release health acceptance', portfolioId: parent.program.portfolio.id, ownerProfileId: parent.ownerProfileId } })).workOps.programs.create.id;
  const project = (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id}}}}', { input: { key: 'RHEALTHAPP', name: 'Release health application', programId: program, ownerProfileId: parent.ownerProfileId } })).workOps.projects.create.id;
  const version = (await gql('mutation($input:CreateWorkOpsVersionInput!){workOps{versions{create(input:$input){id}}}}', { input: { projectId: project, name: '1.0.0' } })).workOps.versions.create.id;
  const release = (await gql('mutation($input:CreateWorkOpsReleaseInput!){workOps{crossProject{createRelease(input:$input){id}}}}', { input: { programId: program, name: 'Application and service health acceptance' } })).workOps.crossProject.createRelease.id;
  await gql('mutation($release:UUID!,$project:UUID!,$version:UUID!){workOps{crossProject{bundleVersion(releaseId:$release,projectId:$project,versionId:$version)}}}', { release, project, version });
  const app = 'atlas-health-mobile', service = 'atlas-health-api', idle = 'atlas-health-idle';
  let appLink;
  const replaceApp = async applicationId => {
    if (appLink) await gql('mutation($project:UUID!,$id:UUID!){workOps{projects{removeAnalyticsApplication(projectId:$project,id:$id)}}}', { project, id: appLink });
    appLink = (await gql('mutation($project:UUID!,$application:String!){workOps{projects{addAnalyticsApplication(projectId:$project,applicationId:$application){id}}}}', { project, application: applicationId })).workOps.projects.addAnalyticsApplication.id;
  };
  await replaceApp(app);
  for (const identifier of [service, idle]) await gql('mutation($project:UUID!,$service:String!){workOps{projects{addAnalyticsService(projectId:$project,service:$service){id}}}}', { project, service: identifier });
  const groups = (await gql('query{security{groups{find(nameOrDescription:"analytics.manager",offset:0,limit:100,type:SYSTEM){id name}}}}')).security.groups.find;
  const analyticsGroup = groups.find(group => group.name === 'analytics.manager')?.id ??
    (await gql('mutation{security{groups{addGroup(name:"analytics.manager",description:"Analytics fixture manager",groupType:SYSTEM){id}}}}')).security.groups.addGroup.id;
  const permission = add => gql(`mutation($program:UUID!,$group:UUID!){workOps{programs{${add ? 'add' : 'remove'}Permission(id:$program,groupId:$group,action:VIEW)}}}`, { program, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
  const analyticsPermission = (principal, add) => gql(`mutation($group:UUID!,$principal:UUID!){security{${add ? 'add' : 'remove'}PrincipalGroup(groupId:$group,principalId:$principal)}}`, { group: analyticsGroup, principal });
  return { program, project, release, app, service, idle, replaceApp, permission, analyticsPermission };
}
