alter table git.pull_requests add column delete_branch_on_merge boolean;

update git.pull_requests pr
set delete_branch_on_merge = coalesce((repository.configuration ->> 'deleteBranchOnMerge')::boolean, true)
from git.repositories repository
where repository.id = pr.repository_id;

alter table git.pull_requests alter column delete_branch_on_merge set not null;
alter table git.pull_requests alter column delete_branch_on_merge set default true;
