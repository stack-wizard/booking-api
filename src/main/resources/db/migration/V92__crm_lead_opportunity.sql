create table crm_lead (
  id              bigserial primary key,
  tenant_id       bigint not null,
  company_name    text null,
  first_name      text null,
  last_name       text null,
  email           text null,
  phone           text null,
  source          text null,
  status          text not null default 'NEW'
                  check (status in ('NEW','WORKING','QUALIFIED','DISQUALIFIED','CONVERTED')),
  owner_user_id   bigint null references app_user(id) on delete set null,
  description     text null,
  disqualify_reason_id bigint null references crm_outcome_reason(id) on delete set null,
  converted_account_id     bigint null references crm_account(id) on delete set null,
  converted_contact_id     bigint null references crm_contact(id) on delete set null,
  converted_opportunity_id bigint null,
  converted_at    timestamptz null,
  attrs           jsonb not null default '{}'::jsonb,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  created_by      bigint null,
  updated_by      bigint null
);
create index idx_crm_lead_tenant_status on crm_lead (tenant_id, status);
create index idx_crm_lead_owner on crm_lead (tenant_id, owner_user_id);

create table crm_opportunity (
  id                  bigserial primary key,
  tenant_id           bigint not null,
  name                text not null,
  account_id          bigint not null references crm_account(id) on delete restrict,
  primary_contact_id  bigint null references crm_contact(id) on delete set null,
  pipeline_id         bigint not null references crm_pipeline(id) on delete restrict,
  stage_id            bigint not null references crm_pipeline_stage(id) on delete restrict,
  owner_user_id       bigint null references app_user(id) on delete set null,
  team_id             bigint null references crm_team(id) on delete set null,
  amount              numeric(14,2) null,
  currency            text not null default 'EUR',
  expected_close_date date null,
  source              text null,
  status              text not null default 'OPEN'
                      check (status in ('OPEN','WON','LOST','TURNED_DOWN','CANCELLED')),
  outcome_reason_id   bigint null references crm_outcome_reason(id) on delete set null,
  outcome_note        text null,
  closed_at           timestamptz null,
  attrs               jsonb not null default '{}'::jsonb,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  created_by          bigint null,
  updated_by          bigint null
);
create index idx_crm_opp_tenant_status on crm_opportunity (tenant_id, status);
create index idx_crm_opp_account on crm_opportunity (account_id);
create index idx_crm_opp_stage on crm_opportunity (stage_id);
create index idx_crm_opp_owner on crm_opportunity (tenant_id, owner_user_id);

alter table crm_lead
  add constraint crm_lead_converted_opportunity_fkey
  foreign key (converted_opportunity_id) references crm_opportunity(id) on delete set null;

create table crm_stage_transition (
  id             bigserial primary key,
  tenant_id      bigint not null,
  opportunity_id bigint not null references crm_opportunity(id) on delete cascade,
  from_stage_id  bigint null references crm_pipeline_stage(id) on delete set null,
  to_stage_id    bigint not null references crm_pipeline_stage(id) on delete restrict,
  changed_by     bigint null references app_user(id) on delete set null,
  changed_at     timestamptz not null default now(),
  note           text null
);
create index idx_crm_transition_opp on crm_stage_transition (opportunity_id, changed_at);
create index idx_crm_transition_tenant on crm_stage_transition (tenant_id, changed_at);
