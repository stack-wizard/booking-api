-- Membership history: people join and leave teams; valid_to is exclusive (left on that day).
alter table crm_team_member
  add column valid_from date not null default current_date,
  add column valid_to   date null;

update crm_team_member set valid_from = created_at::date;

alter table crm_team_member
  add constraint ck_crm_team_member_validity check (valid_to is null or valid_to >= valid_from);

drop index uq_crm_team_member;
create unique index uq_crm_team_member_open on crm_team_member (team_id, app_user_id) where valid_to is null;
create index idx_crm_team_member_team on crm_team_member (team_id);
create index idx_crm_team_member_app_user on crm_team_member (app_user_id);

-- Every record belongs to an owner and a team; leaders see their team's records.
alter table crm_lead add column team_id bigint null references crm_team(id) on delete set null;
alter table event    add column team_id bigint null references crm_team(id) on delete set null;

create index idx_crm_lead_team on crm_lead (tenant_id, team_id);
create index idx_event_team on event (tenant_id, team_id);
create index idx_crm_account_team on crm_account (tenant_id, team_id);
create index idx_crm_opp_team on crm_opportunity (tenant_id, team_id);

with primary_team as (
  select distinct on (tenant_id, app_user_id) tenant_id, app_user_id, team_id
  from crm_team_member
  where valid_to is null
  order by tenant_id, app_user_id, valid_from, id
)
update crm_lead r set team_id = p.team_id
from primary_team p
where r.team_id is null and r.tenant_id = p.tenant_id and r.owner_user_id = p.app_user_id;

with primary_team as (
  select distinct on (tenant_id, app_user_id) tenant_id, app_user_id, team_id
  from crm_team_member
  where valid_to is null
  order by tenant_id, app_user_id, valid_from, id
)
update crm_account r set team_id = p.team_id
from primary_team p
where r.team_id is null and r.tenant_id = p.tenant_id and r.owner_user_id = p.app_user_id;

with primary_team as (
  select distinct on (tenant_id, app_user_id) tenant_id, app_user_id, team_id
  from crm_team_member
  where valid_to is null
  order by tenant_id, app_user_id, valid_from, id
)
update crm_opportunity r set team_id = p.team_id
from primary_team p
where r.team_id is null and r.tenant_id = p.tenant_id and r.owner_user_id = p.app_user_id;

update event e set team_id = o.team_id
from crm_opportunity o
where e.team_id is null and e.opportunity_id = o.id;

with primary_team as (
  select distinct on (tenant_id, app_user_id) tenant_id, app_user_id, team_id
  from crm_team_member
  where valid_to is null
  order by tenant_id, app_user_id, valid_from, id
)
update event r set team_id = p.team_id
from primary_team p
where r.team_id is null and r.tenant_id = p.tenant_id and r.owner_user_id = p.app_user_id;

-- Work handed over when someone leaves or changes team.
create table crm_reassignment (
  id            bigserial primary key,
  tenant_id     bigint not null,
  from_user_id  bigint null references app_user(id) on delete set null,
  to_user_id    bigint null references app_user(id) on delete set null,
  to_team_id    bigint null references crm_team(id) on delete set null,
  counts        jsonb not null default '{}'::jsonb,
  note          text null,
  created_by    bigint null references app_user(id) on delete set null,
  created_at    timestamptz not null default now()
);
create index idx_crm_reassignment_tenant on crm_reassignment (tenant_id, created_at desc);
create index idx_crm_reassignment_from on crm_reassignment (from_user_id);
create index idx_crm_reassignment_to on crm_reassignment (to_user_id);
create index idx_crm_reassignment_team on crm_reassignment (to_team_id);
create index idx_crm_reassignment_created_by on crm_reassignment (created_by);
