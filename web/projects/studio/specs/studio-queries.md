# Studio Dashboard Queries

Analytics queries for bosca-studio stat tiles and dashboard visualizations. All queries execute through Trino against two catalogs:

- **`bosca."public".*`** — PostgreSQL tables (profiles, metadata, organizations, etc.)
- **`warehouse.bosca.events`** — Iceberg events table (sessions, impressions, interactions, errors)

**De-duplication:** Events can be sent more than once by clients (retries, network issues). Every query against the events table must de-duplicate by `client_id`. Use a CTE or subquery with `ROW_NUMBER()` partitioned by `client_id` to keep only one row per logical event.

**Impression semantics:** An impression normally records visibility, not interaction. Queries that infer activity, engagement, preference, popularity, or behavioral relevance must exclude impressions unless `element.type = 'page'`. Raw event-volume and explicitly requested visibility/exposure queries may count other impressions.

**Scroll semantics:** Scroll-depth milestones remain `Interaction` events because scrolling is user activity, but they measure how much of a page was viewed. Do not count each `scroll_depth` or `scroll_max_depth` row as another discrete engagement, conversion, affinity, popularity, or co-engagement event. Use depth as a separate quality/boosting dimension for an associated page view, or query it directly for view-depth analysis. Distinct-user and distinct-session activity metrics may include scroll events because the extra milestones do not increase those counts.

These should be installed via a `StudioQueryInstaller` following the `QueryInstaller` pattern in `bosca-framework/backend/framework/analytics/src/main/kotlin/bosca/analytics/installer/`.

---

## Audience

### `studio.audience.total-profiles`

```sql
select count(*) as value
from bosca."public".profiles
where type = 'generic'
```

### `studio.audience.active-profiles-30d`

```sql
with deduped as (
    select *, row_number() over (partition by client_id order by received) as rn
    from warehouse.bosca.events
    where created >= current_date - interval '30' day
      and context.user_id is not null
      and (type <> 'Impression' or element.type = 'page')
)
select count(distinct context.user_id) as value
from deduped
where rn = 1
```

### `studio.audience.active-profiles-30d-delta`

```sql
with deduped as (
    select *, row_number() over (partition by client_id order by received) as rn
    from warehouse.bosca.events
    where created >= current_date - interval '60' day
      and context.user_id is not null
      and (type <> 'Impression' or element.type = 'page')
),
events as (
    select * from deduped where rn = 1
),
current_period as (
    select count(distinct context.user_id) as value
    from events
    where created >= current_date - interval '30' day
),
prior_period as (
    select count(distinct context.user_id) as value
    from events
    where created >= current_date - interval '60' day
      and created < current_date - interval '30' day
)
select
    c.value,
    c.value - p.value as change,
    case when p.value > 0
        then round(cast(c.value - p.value as double) / p.value * 100, 1)
        else null
    end as change_pct
from current_period c, prior_period p
```

### `studio.audience.churn-risk`

Profiles that were active 30-90 days ago but not in the last 30 days.

```sql
with deduped as (
    select *, row_number() over (partition by client_id order by received) as rn
    from warehouse.bosca.events
    where created >= current_date - interval '90' day
      and context.user_id is not null
      and (type <> 'Impression' or element.type = 'page')
),
events as (
    select * from deduped where rn = 1
)
select count(distinct old_users.user_id) as value
from (
    select distinct context.user_id as user_id
    from events
    where created < current_date - interval '30' day
) old_users
left join (
    select distinct context.user_id as user_id
    from events
    where created >= current_date - interval '30' day
) recent_users on old_users.user_id = recent_users.user_id
where recent_users.user_id is null
```

### `studio.audience.total-organizations`

```sql
select count(*) as value
from bosca."public".organizations
```

### `studio.audience.new-organizations-7d`

```sql
select count(*) as value
from bosca."public".organizations
where created >= current_date - interval '7' day
```

### `studio.audience.total-org-members`

```sql
select count(*) as value
from bosca."public".organization_members
```

### `studio.audience.avg-org-members`

```sql
select round(avg(cnt)) as value
from (
    select organization_id, count(*) as cnt
    from bosca."public".organization_members
    group by organization_id
) sub
```

### `studio.audience.segment-member-count`

Parameterized — takes a segment name.

```sql
select s.name as segment, s.member_count as value
from segmentation.segments s
where s.name = :segment_name
  and s.status = 'active'
```

**Parameters:**
- `segment_name` (STRING, required)

---

## CMS

### `studio.cms.total-items`

```sql
select count(*) as value
from bosca."public".metadata
```

### `studio.cms.items-by-workflow-state`

```sql
select
    workflow_state_id as state,
    count(*) as value
from bosca."public".metadata
where workflow_state_id is not null
group by workflow_state_id
order by value desc
```

### `studio.cms.awaiting-review`

```sql
select count(*) as value
from bosca."public".metadata
where workflow_state_id = 'review'
```

### `studio.cms.published`

```sql
select count(*) as value
from bosca."public".metadata
where workflow_state_id = 'published'
```

### `studio.cms.draft`

```sql
select count(*) as value
from bosca."public".metadata
where workflow_state_id = 'draft'
```

### `studio.cms.items-by-content-type`

```sql
select
    content_type,
    count(*) as value
from bosca."public".metadata
group by content_type
order by value desc
```

### `studio.cms.total-collections`

```sql
select count(*) as value
from bosca."public".collections
where enabled = true
```

### `studio.cms.new-items-7d`

```sql
select count(*) as value
from bosca."public".metadata
where created >= current_date - interval '7' day
```

### `studio.cms.broken-relationships`

Items referencing a parent that doesn't exist.

```sql
select count(*) as value
from bosca."public".metadata m
where m.parent_id is not null
  and not exists (
      select 1 from bosca."public".metadata p where p.id = m.parent_id
  )
```

---

## Forms

### `studio.forms.total-forms`

```sql
select count(*) as value
from bosca."public".form_schemas
where deleted = false
```

### `studio.forms.live-forms`

```sql
select count(*) as value
from bosca."public".form_schemas
where deleted = false
  and published = true
```

### `studio.forms.total-submissions`

```sql
select count(*) as value
from bosca."public".form_submissions
```

### `studio.forms.submissions-30d`

```sql
select count(*) as value
from bosca."public".form_submissions
where created >= current_date - interval '30' day
```

### `studio.forms.submissions-today`

```sql
select count(*) as value
from bosca."public".form_submissions
where created >= current_date
```

### `studio.forms.submissions-by-day-30d`

```sql
select
    date(created) as date,
    count(*) as value
from bosca."public".form_submissions
where created >= current_date - interval '30' day
group by date(created)
order by date
```

---

## Analytics Events

### `studio.analytics.events-today`

```sql
with deduped as (
    select *, row_number() over (partition by client_id order by received) as rn
    from warehouse.bosca.events
    where created >= current_date
)
select count(*) as value
from deduped
where rn = 1
```

### `studio.analytics.events-by-type-24h`

```sql
with deduped as (
    select *, row_number() over (partition by client_id order by received) as rn
    from warehouse.bosca.events
    where created >= current_timestamp - interval '24' hour
)
select
    type,
    count(*) as value
from deduped
where rn = 1
group by type
order by value desc
```

### `studio.analytics.active-users-today`

```sql
with deduped as (
    select *, row_number() over (partition by client_id order by received) as rn
    from warehouse.bosca.events
    where created >= current_date
      and context.user_id is not null
      and (type <> 'Impression' or element.type = 'page')
)
select count(distinct context.user_id) as value
from deduped
where rn = 1
```

### `studio.analytics.sessions-today`

```sql
with deduped as (
    select *, row_number() over (partition by client_id order by received) as rn
    from warehouse.bosca.events
    where created >= current_date
      and (type <> 'Impression' or element.type = 'page')
)
select count(distinct context.session_id) as value
from deduped
where rn = 1
```

### `studio.analytics.errors-24h`

```sql
with deduped as (
    select *, row_number() over (partition by client_id order by received) as rn
    from warehouse.bosca.events
    where created >= current_timestamp - interval '24' hour
      and type = 'Error'
)
select count(*) as value
from deduped
where rn = 1
```

### `studio.analytics.search-queries-24h`

```sql
with deduped as (
    select *, row_number() over (partition by client_id order by received) as rn
    from warehouse.bosca.events
    where created >= current_timestamp - interval '24' hour
      and type = 'Interaction'
      and element.type = 'search'
)
select count(*) as value
from deduped
where rn = 1
```

### `studio.analytics.top-pages-7d`

```sql
with deduped as (
    select *, row_number() over (partition by client_id order by received) as rn
    from warehouse.bosca.events
    where created >= current_date - interval '7' day
      and type = 'Impression'
      and element.type = 'page'
)
select
    element.id as page,
    count(*) as impressions
from deduped
where rn = 1
group by element.id
order by impressions desc
fetch first 20 rows only
```

### `studio.analytics.dau-30d`

Daily active users over 30 days with day-over-day change.

```sql
with deduped as (
    select *, row_number() over (partition by client_id order by received) as rn
    from warehouse.bosca.events
    where created >= current_date - interval '30' day
      and context.user_id is not null
      and (type <> 'Impression' or element.type = 'page')
),
daily_users as (
    select
        date_trunc('day', created) as date,
        count(distinct context.user_id) as value
    from deduped
    where rn = 1
    group by date_trunc('day', created)
)
select
    date,
    value,
    lag(value) over (order by date) as previous_value,
    round(
        cast(value - lag(value) over (order by date) as double)
        / nullif(lag(value) over (order by date), 0) * 100
    ) as change_pct
from daily_users
order by date
```

---

## WorkOps

### `studio.workops.open-tasks`

```sql
select count(*) as value
from workops.task t
join workops.status s on t.status_id = s.id
where t.deleted_at is null
  and s.category in ('TODO', 'IN_PROGRESS')
```

### `studio.workops.tasks-by-status-category`

```sql
select
    s.category,
    count(*) as value
from workops.task t
join workops.status s on t.status_id = s.id
where t.deleted_at is null
group by s.category
```

### `studio.workops.blocked-tasks`

```sql
select count(*) as value
from workops.task t
join workops.status s on t.status_id = s.id
where t.deleted_at is null
  and s.name = 'Blocked'
```

### `studio.workops.completed-7d`

```sql
select count(*) as value
from workops.task t
join workops.status s on t.status_id = s.id
where t.deleted_at is null
  and s.category = 'DONE'
  and t.resolution_at >= current_date - interval '7' day
```

### `studio.workops.active-projects`

```sql
select count(*) as value
from workops.project
where archived_at is null
```

---

## Experiments

### `studio.experiments.total-flags`

```sql
select count(*) as value
from bosca."public".feature_flags
```

### `studio.experiments.enabled-flags`

```sql
select count(*) as value
from bosca."public".feature_flags
where enabled = true
```

### `studio.experiments.active-experiments`

```sql
select count(*) as value
from bosca."public".experiments
where status = 'running'
```

---

## Localization

### `studio.localization.total-locales`

```sql
select count(distinct language_tag) as value
from bosca."public".metadata
where language_tag is not null
```

### `studio.localization.avg-completion`

Average translation coverage across non-master locales. Compares each locale's item count to the master locale count.

```sql
with master_count as (
    select count(*) as total
    from bosca."public".metadata
    where language_tag = 'en-US'
),
locale_counts as (
    select
        language_tag,
        count(*) as translated
    from bosca."public".metadata
    where language_tag != 'en-US'
      and language_tag is not null
    group by language_tag
)
select round(avg(cast(l.translated as double) / nullif(m.total, 0) * 100)) as value
from locale_counts l, master_count m
```

---

## System

### `studio.system.total-jobs-running`

```sql
select count(*) as value
from bosca."public".job_definitions
where status = 'running'
```

### `studio.system.jobs-failed-24h`

```sql
select count(*) as value
from bosca."public".job_definitions
where status = 'failed'
  and modified >= current_timestamp - interval '24' hour
```

---

## Dashboard Key Mapping

Each page uses a dashboard that groups the relevant queries above as `NUMBER`-type visualizations:

| Dashboard key | Queries |
|---|---|
| `studio.audience.profiles` | `total-profiles`, `active-profiles-30d`, `segment-member-count` (VIP), `churn-risk` |
| `studio.audience.organizations` | `total-organizations`, `new-organizations-7d`, `total-org-members`, `avg-org-members` |
| `studio.cms.metadata` | `total-items`, `published`, `awaiting-review`, `draft` |
| `studio.forms.forms` | `total-forms`, `live-forms`, `submissions-30d`, `submissions-today` |
| `studio.workops.tasks` | `open-tasks`, `tasks-by-status-category` (in_progress), `blocked-tasks`, `completed-7d` |
| `studio.workops.projects` | `active-projects` |
| `studio.experiments.flags` | `total-flags`, `enabled-flags`, `active-experiments` |
| `studio.localization.locales` | `total-locales`, `avg-completion` |
| `studio.system.jobs` | `total-jobs-running`, `jobs-failed-24h` |
| `studio.analytics.overview` | `active-users-today`, `events-today`, `sessions-today`, `errors-24h`, `dau-30d` |
