create index on sharedqueue.jobs(queue, claimed_until) where claimed_until is not null;
