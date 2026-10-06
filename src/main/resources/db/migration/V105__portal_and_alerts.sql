-- Phase 6: client portal link per event and alerts raised by scheduled scans.

create table portal_access_token (
  id           bigserial primary key,
  tenant_id    bigint not null,
  event_id     bigint not null references event(id) on delete cascade,
  contact_id   bigint null references crm_contact(id) on delete set null,
  token        text not null,
  expires_at   timestamptz not null,
  revoked_at   timestamptz null,
  last_used_at timestamptz null,
  created_by   bigint null references app_user(id) on delete set null,
  created_at   timestamptz not null default now()
);

create unique index uq_portal_access_token on portal_access_token (token);
create index idx_portal_access_token_event on portal_access_token (event_id);
create index idx_portal_access_token_contact on portal_access_token (contact_id);
create index idx_portal_access_token_tenant on portal_access_token (tenant_id);
create index idx_portal_access_token_user on portal_access_token (created_by);

create index if not exists idx_crm_contact_platform_user on crm_contact (tenant_id, platform_user_id);

create table crm_alert (
  id              bigserial primary key,
  tenant_id       bigint not null,
  kind            text not null
                  check (kind in ('DECISION_DATE_DUE','GUARANTEE_DUE','BEO_NOT_ISSUED','QUOTE_EXPIRED','MILESTONE_DUE','QUOTE_DECIDED')),
  event_id        bigint null references event(id) on delete cascade,
  quote_id        bigint null references sales_quote(id) on delete cascade,
  milestone_id    bigint null references sales_payment_milestone(id) on delete cascade,
  assigned_to     bigint null references app_user(id) on delete set null,
  message         text not null,
  due_date        date null,
  dedupe_key      text not null,
  acknowledged_at timestamptz null,
  acknowledged_by bigint null references app_user(id) on delete set null,
  created_at      timestamptz not null default now()
);

create unique index uq_crm_alert_dedupe on crm_alert (tenant_id, dedupe_key);
create index idx_crm_alert_open on crm_alert (tenant_id, assigned_to) where acknowledged_at is null;
create index idx_crm_alert_event on crm_alert (event_id);
create index idx_crm_alert_quote on crm_alert (quote_id);
create index idx_crm_alert_milestone on crm_alert (milestone_id);
create index idx_crm_alert_assigned on crm_alert (assigned_to);
create index idx_crm_alert_ack_user on crm_alert (acknowledged_by);
