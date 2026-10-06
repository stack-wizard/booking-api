create table crm_segment (
  id              bigserial primary key,
  tenant_id       bigint not null,
  code            text not null,
  name            text not null,
  default_team_id bigint null references crm_team(id) on delete set null,
  active          boolean not null default true,
  display_order   integer not null default 0,
  created_at      timestamptz not null default now()
);
create unique index uq_crm_segment_code on crm_segment (tenant_id, lower(code));
create index idx_crm_segment_team on crm_segment (default_team_id);

-- First active rule by priority wins; null criteria match anything.
create table crm_assignment_rule (
  id                    bigserial primary key,
  tenant_id             bigint not null,
  name                  text not null,
  priority              integer not null default 100,
  active                boolean not null default true,
  segment               text null,
  country               varchar(2) null,
  source                text null,
  inquiry_type          text null,
  event_type            text null,
  min_pax               integer null check (min_pax is null or min_pax >= 0),
  max_pax               integer null check (max_pax is null or max_pax >= 0),
  target_team_id        bigint not null references crm_team(id) on delete cascade,
  strategy              text not null default 'ROUND_ROBIN'
                        check (strategy in ('ROUND_ROBIN','LEAST_LOADED','FIXED_USER','QUEUE')),
  fixed_user_id         bigint null references app_user(id) on delete set null,
  last_assigned_user_id bigint null references app_user(id) on delete set null,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now(),
  check (min_pax is null or max_pax is null or min_pax <= max_pax),
  check (strategy <> 'FIXED_USER' or fixed_user_id is not null)
);
create index idx_crm_assignment_rule_tenant on crm_assignment_rule (tenant_id, active, priority);
create index idx_crm_assignment_rule_team on crm_assignment_rule (target_team_id);
create index idx_crm_assignment_rule_fixed_user on crm_assignment_rule (fixed_user_id);
create index idx_crm_assignment_rule_last_user on crm_assignment_rule (last_assigned_user_id);

alter table crm_lead
  add column segment            text null,
  add column country            varchar(2) null,
  add column assignment_rule_id bigint null references crm_assignment_rule(id) on delete set null,
  add column assigned_at        timestamptz null;
create index idx_crm_lead_assignment_rule on crm_lead (assignment_rule_id);
