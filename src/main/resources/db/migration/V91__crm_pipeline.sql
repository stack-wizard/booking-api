create table crm_pipeline (
  id          bigserial primary key,
  tenant_id   bigint not null,
  code        text not null,
  name        text not null,
  description text null,
  is_default  boolean not null default false,
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  created_by  bigint null,
  updated_by  bigint null
);
create unique index uq_crm_pipeline_code on crm_pipeline (tenant_id, upper(code));
create unique index uq_crm_pipeline_default
  on crm_pipeline (tenant_id) where is_default;
create index idx_crm_pipeline_tenant on crm_pipeline (tenant_id);

create table crm_pipeline_stage (
  id            bigserial primary key,
  tenant_id     bigint not null,
  pipeline_id   bigint not null references crm_pipeline(id) on delete cascade,
  code          text not null,
  name          text not null,
  display_order int not null,
  probability   numeric(5,2) not null default 0 check (probability between 0 and 100),
  stage_kind    text not null default 'OPEN'
                check (stage_kind in ('OPEN','WON','LOST')),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  created_by    bigint null,
  updated_by    bigint null
);
create unique index uq_crm_stage_code on crm_pipeline_stage (pipeline_id, upper(code));
create unique index uq_crm_stage_order on crm_pipeline_stage (pipeline_id, display_order);
create index idx_crm_stage_pipeline on crm_pipeline_stage (pipeline_id);

create table crm_stage_requirement (
  id            bigserial primary key,
  tenant_id     bigint not null,
  stage_id      bigint not null references crm_pipeline_stage(id) on delete cascade,
  field_path    text not null,
  requirement   text not null default 'REQUIRED'
                check (requirement in ('REQUIRED','MIN_VALUE','MAX_VALUE')),
  value_spec    text null,
  message       text not null,
  created_at    timestamptz not null default now()
);
create index idx_crm_stage_req_stage on crm_stage_requirement (stage_id);

create table crm_outcome_reason (
  id            bigserial primary key,
  tenant_id     bigint not null,
  kind          text not null
                check (kind in ('WON','LOST','TURNED_DOWN','CANCELLED')),
  code          text not null,
  name          text not null,
  display_order int not null default 0,
  active        boolean not null default true,
  created_at    timestamptz not null default now()
);
create unique index uq_crm_outcome_reason on crm_outcome_reason (tenant_id, kind, upper(code));
create index idx_crm_outcome_reason_tenant on crm_outcome_reason (tenant_id);
