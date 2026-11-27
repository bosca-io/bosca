import assert from 'node:assert/strict';

export async function until(label, read, matches = Boolean) {
  const deadline = Date.now() + 25000;
  let value;
  do {
    value = await read();
    if (matches(value)) return value;
    await new Promise(resolve => setTimeout(resolve, 150));
  } while (Date.now() < deadline);
  assert.fail(`${label}: ${JSON.stringify(value)}`);
}

// Only the disposable production-process fixture invokes these API operations.
export async function releaseCiFixture(gql, fixtureProject) {
  const parent = (await gql('query($id:UUID!){workOps{projects{project(id:$id){ownerProfileId program{portfolio{id}}}}}}', { id: fixtureProject })).workOps.projects.project;
  const program = (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id}}}}', { input: { key: 'RCI', name: 'Atlas native release CI', portfolioId: parent.program.portfolio.id, ownerProfileId: parent.ownerProfileId } })).workOps.programs.create.id;
  const project = (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id}}}}', { input: { key: 'RCIAPP', name: 'Native release application', programId: program, ownerProfileId: parent.ownerProfileId } })).workOps.projects.create.id;
  const repository = (await gql('mutation($input:CreateGitRepositoryInput!){git{createRepository(input:$input){id}}}', { input: { ownerId: parent.ownerProfileId, slug: 'atlas-release-ci', name: 'Atlas release CI', visibility: 'PRIVATE', defaultBranch: 'main', initializeWithReadme: true } })).git.createRepository.id;
  await gql('mutation($project:UUID!,$repository:UUID!){workOps{projects{addProjectRepository(projectId:$project,repositoryId:$repository){id}}}}', { project, repository });
  const content = `name: Atlas native release
on:
  release:
    inputs:
      dryRun:
        type: boolean
        default: "false"
        required: true
      replicas:
        type: number
        default: "0"
        required: true
      region:
        type: choice
        options: [west, east]
        default: west
      message:
        type: string
  promotion:
    environments: [production]
    inputs:
      phasedRelease:
        type: boolean
        default: "false"
      track:
        type: choice
        options: [internal, production]
        default: production
environments:
  testing:
    deploy-on: release
  production:
    promotes-from: testing
    approval: required
jobs:
  prepare:
    if: event == 'release'
    runner: atlas-release-test
    environment: testing
    steps:
      - name: Build release
        run: echo fixture
  approve-production:
    if: event == 'promotion'
    environment: production
    approval: true
`;
  const commit = (await gql('mutation($input:CommitFileInput!){git{commitFile(input:$input){commitSha}}}', { input: { repositoryId: repository, branch: 'main', path: '.bosca/pipelines/release.yaml', content, message: 'Configure isolated release acceptance', authorName: 'Atlas Release Tester', authorEmail: 'release-tester@example.test' } })).git.commitFile.commitSha;
  const pipelines = () => gql('query($id:UUID!){git{pipelines(repositoryId:$id){id name triggerTypes}}}', { id: repository }).then(data => data.git.pipelines);
  const pipeline = (await until('Release pipeline synchronized', pipelines, rows => rows.some(row => row.triggerTypes.includes('RELEASE') && row.triggerTypes.includes('PROMOTION'))))[0];
  const environments = await until('Environment topology synchronized', () => gql('query($id:UUID!){workOps{multiRepo{environments(programId:$id){id key promotionSourceIds}}}}', { id: program }).then(data => data.workOps.multiRepo.environments), rows => rows.length === 2 && rows.some(row => row.key === 'production' && row.promotionSourceIds.length === 1));
  const version = (await gql('mutation($input:CreateWorkOpsVersionInput!){workOps{versions{create(input:$input){id}}}}', { input: { projectId: project, name: '1.0.0' } })).workOps.versions.create.id;
  const release = (await gql('mutation($input:CreateWorkOpsReleaseInput!){workOps{crossProject{createRelease(input:$input){id}}}}', { input: { programId: program, name: 'Native release acceptance' } })).workOps.crossProject.createRelease.id;
  await gql('mutation($release:UUID!,$project:UUID!,$version:UUID!){workOps{crossProject{bundleVersion(releaseId:$release,projectId:$project,versionId:$version)}}}', { release, project, version });
  const registration = (await gql('mutation{git{registerAgent(name:"Atlas release fixture",labels:["atlas-release-test"],mode:RUNNER){agent{id}token}}}')).git.registerAgent;
  const group = process.env.ATLAS_WORKOPS_TEST_READER_GROUP;
  const programPermission = (action, add) => gql(`mutation($id:UUID!,$group:UUID!){workOps{programs{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:${action})}}}`, { id: program, group });
  const repositoryPermission = (action, add) => gql(`mutation($input:PermissionInput!){git{${add ? 'add' : 'remove'}Permission(permission:$input)}}`, { input: { entityId: repository, groupId: group, action } });
  const environmentPermission = (add) => gql(`mutation($id:UUID!,$group:UUID!){workOps{multiRepo{${add ? 'add' : 'remove'}EnvironmentPermission(id:$id,groupId:$group,action:EXECUTE)}}}`, { id: environments.find(row => row.key === 'production').id, group });
  const runs = () => gql('query($id:UUID!){workOps{crossProject{releaseRuns(releaseId:$id){runId status}}}}', { id: release }).then(data => data.workOps.crossProject.releaseRuns);
  const run = id => gql('query($id:UUID!){git{pipelineRun(id:$id){id number pipelineId repositoryId ref commitSha triggerType triggeredBy status parameters jobs{id name status attempt awaitingApproval approvalComment approvedBy errorMessage steps{id name status exitCode errorMessage}}}}}', { id }).then(data => data.git.pipelineRun);
  const agent = (query, variables) => gql(query, variables, null, registration.token);
  const finish = async (id, status) => {
    const current = await run(id), job = current.jobs.find(row => row.name === 'prepare');
    const claimed = await until('Fixture-label job claimed by persistent agent', () => agent('mutation($agent:UUID!){git{claimJob(agentId:$agent,labels:["atlas-release-test"]){id steps{id}}}}', { agent: registration.agent.id }).then(data => data.git.claimJob));
    assert.equal(claimed.id, job.id);
    for (const step of claimed.steps) await agent('mutation($id:UUID!,$status:GitPipelineRunStatus!,$code:Int!,$error:String){git{updateStepStatus(stepId:$id,status:$status,exitCode:$code,errorMessage:$error)}}', { id: step.id, status, code: status === 'SUCCESS' ? 0 : 17, error: status === 'FAILURE' ? 'Isolated compiler failure' : null });
    await agent('mutation($id:UUID!,$status:GitPipelineRunStatus!,$error:String){git{updateJobStatus(jobId:$id,status:$status,errorMessage:$error){id}}}', { id: job.id, status, error: status === 'FAILURE' ? 'Isolated build failed' : null });
    return until('Native run settled', () => run(id), row => row.status === status);
  };
  const tag = () => gql('mutation($id:UUID!){git{createTag(repositoryId:$id,tag:"1.0.0",targetRef:"main",taggerName:"Atlas Release Tester",taggerEmail:"release-tester@example.test"){name sha targetSha isAnnotated}}}', { id: repository }).then(data => data.git.createTag);
  const restore = async () => {
    await environmentPermission(false);
    for (const action of ['MANAGE', 'VIEW']) await programPermission(action, false);
    for (const action of ['EDIT', 'EXECUTE', 'VIEW']) await repositoryPermission(action, false);
    await gql('mutation($id:UUID!){git{deregisterAgent(id:$id)}}', { id: registration.agent.id });
  };
  return { program, project, repository, pipeline: pipeline.id, version, release, commit, environments, programPermission, repositoryPermission, environmentPermission, runs, run, finish, tag, restore };
}
