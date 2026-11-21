export async function releaseLiveFixture(gql, fixtureProject) {
  const parent = (await gql('query($id:UUID!){workOps{projects{project(id:$id){ownerProfileId program{portfolio{id}}}}}}', { id: fixtureProject })).workOps.projects.project;
  const program = (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id}}}}', { input: { key: 'RLIVE', name: 'Release live session acceptance', portfolioId: parent.program.portfolio.id, ownerProfileId: parent.ownerProfileId } })).workOps.programs.create.id;
  const release = (await gql('mutation($input:CreateWorkOpsReleaseInput!){workOps{crossProject{createRelease(input:$input){id}}}}', { input: { programId: program, name: 'Live session map acceptance' } })).workOps.crossProject.createRelease.id;
  const projects = [], versions = [], links = [];
  const app = 'atlas-live-mobile', second = 'atlas-live-desktop', replacement = 'atlas-live-replacement';
  const add = async (project, application) => {
    const id = (await gql('mutation($project:UUID!,$app:String!){workOps{projects{addAnalyticsApplication(projectId:$project,applicationId:$app){id}}}}', { project, app: application })).workOps.projects.addAnalyticsApplication.id;
    links.push({ project, app: application, id });
  };
  for (const [index, name] of ['Mobile application', 'Desktop application'].entries()) {
    const project = (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id}}}}', { input: { key: `RLIVE${index}`, name, programId: program, ownerProfileId: parent.ownerProfileId } })).workOps.projects.create.id;
    const version = (await gql('mutation($input:CreateWorkOpsVersionInput!){workOps{versions{create(input:$input){id}}}}', { input: { projectId: project, name: '1.0.0' } })).workOps.versions.create.id;
    await gql('mutation($release:UUID!,$project:UUID!,$version:UUID!){workOps{crossProject{bundleVersion(releaseId:$release,projectId:$project,versionId:$version)}}}', { release, project, version });
    projects.push(project); versions.push(version); await add(project, app);
    if (index === 1) await add(project, second);
  }
  const replace = async () => {
    for (const link of links.filter(link => link.app === app)) await gql('mutation($project:UUID!,$id:UUID!){workOps{projects{removeAnalyticsApplication(projectId:$project,id:$id)}}}', link);
    await add(projects[0], replacement);
  };
  const permission = add => gql(`mutation($id:UUID!,$group:UUID!){workOps{programs{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:VIEW)}}}`, { id: program, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
  return { program, release, projects, versions, app, second, replacement, replace, permission };
}
