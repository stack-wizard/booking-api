create table crm_activity (
  id             bigserial primary key,
  tenant_id      bigint not null,
  activity_type  text not null
                 check (activity_type in ('CALL','EMAIL','MEETING','TASK','NOTE')),
  subject        text not null,
  body           text null,
  account_id     bigint null references crm_account(id) on delete cascade,
  contact_id     bigint null references crm_contact(id) on delete set null,
  opportunity_id bigint null references crm_opportunity(id) on delete cascade,
  lead_id        bigint null references crm_lead(id) on delete cascade,
  assigned_to    bigint null references app_user(id) on delete set null,
  due_at         timestamptz null,
  done_at        timestamptz null,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  created_by     bigint null,
  updated_by     bigint null,
  constraint crm_activity_has_parent check (
    account_id is not null or opportunity_id is not null or lead_id is not null
  )
);
create index idx_crm_activity_account on crm_activity (account_id, created_at desc);
create index idx_crm_activity_opp on crm_activity (opportunity_id, created_at desc);
create index idx_crm_activity_open
  on crm_activity (tenant_id, assigned_to, due_at) where done_at is null;
create index idx_crm_activity_tenant on crm_activity (tenant_id);

create table crm_attachment (
  id             bigserial primary key,
  tenant_id      bigint not null,
  account_id     bigint null references crm_account(id) on delete cascade,
  opportunity_id bigint null references crm_opportunity(id) on delete cascade,
  file_name      text not null,
  content_type   text null,
  size_bytes     bigint null,
  storage_key    text not null,
  created_at     timestamptz not null default now(),
  created_by     bigint null,
  constraint crm_attachment_has_parent check (
    account_id is not null or opportunity_id is not null
  )
);
create index idx_crm_attachment_account on crm_attachment (account_id);
create index idx_crm_attachment_opp on crm_attachment (opportunity_id);
create index idx_crm_attachment_tenant on crm_attachment (tenant_id);
