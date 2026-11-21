export async function releaseEnvironmentsFixture(gql, fixtureProject) {
  const parent = (await gql('query($id:UUID!){workOps{projects{project(id:$id){ownerProfileId program{portfolio{id}}}}}}', { id: fixtureProject })).workOps.projects.project;
  const program = (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id}}}}', { input: { key: 'RTENV', name: 'Release environment telemetry', portfolioId: parent.program.portfolio.id, ownerProfileId: parent.ownerProfileId } })).workOps.programs.create.id;
  const project = (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id}}}}', { input: { key: 'RTAPP', name: 'Environment application', programId: program, ownerProfileId: parent.ownerProfileId } })).workOps.projects.create.id;
  const type = (await gql('mutation{workOps{multiRepo{createEnvironmentType(name:"Release telemetry fixture stage"){id}}}}')).workOps.multiRepo.createEnvironmentType.id;
  const environment = async (key, name, order, sources = [], targetType = 'GENERIC') => (await gql('mutation($input:CreateWorkOpsEnvironmentInput!){workOps{multiRepo{createEnvironment(input:$input){id name}}}}', { input: { programId: program, key, name, displayOrder: order, promotionSourceIds: sources, targetType, typeId: type, requiresApproval: key === 'staging' } })).workOps.multiRepo.createEnvironment;
  const development = await environment('development', 'Development', 9), staging = await environment('staging', 'Staging', 0, [development.id]);
  const internal = await environment('play-internal', 'Play internal', 10, [], 'PLAY_TRACK');
  const play = await environment('play-production', 'Play production', 11, [internal.id], 'PLAY_TRACK');
  const flight = await environment('testflight', 'TestFlight', 12, [], 'TESTFLIGHT');
  const store = await environment('app-store', 'App Store', 13, [flight.id], 'APP_STORE');
  const version = async name => (await gql('mutation($input:CreateWorkOpsVersionInput!){workOps{versions{create(input:$input){id}}}}', { input: { projectId: project, name } })).workOps.versions.create.id;
  const previous = await version('1.0.0'), current = await version('2.0.0');
  const release = (await gql('mutation($input:CreateWorkOpsReleaseInput!){workOps{crossProject{createRelease(input:$input){id}}}}', { input: { programId: program, name: 'Environment state acceptance' } })).workOps.crossProject.createRelease.id;
  await gql('mutation($release:UUID!,$project:UUID!,$version:UUID!){workOps{crossProject{bundleVersion(releaseId:$release,projectId:$project,versionId:$version)}}}', { release, project, version: current });
  const deploy = async (environmentId, versionId, targetKind = 'HELM') => {
    const deployment = (await gql('mutation($input:WorkOpsDeployInput!){workOps{multiRepo{deploy(input:$input){id version}}}}', { input: { environmentId, projectId: project, versionId, releaseId: release, targetKind } })).workOps.multiRepo.deploy;
    return (await gql('mutation($id:UUID!,$version:Long!){workOps{multiRepo{markDeployed(deploymentId:$id,expectedVersion:$version){id status}}}}', deployment)).workOps.multiRepo.markDeployed;
  };
  await deploy(development.id, previous);
  const group = process.env.ATLAS_WORKOPS_TEST_READER_GROUP;
  const permission = add => gql(`mutation($id:UUID!,$group:UUID!){workOps{programs{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:VIEW)}}}`, { id: program, group });
  return { program, project, release, previous, current, development, staging, internal, play, flight, store, deploy, permission };
}
