-- Phase 3: event is its own commercial header (parallel to reservation_request, not inside it).

create table event (
  id                  bigserial primary key,
  tenant_id           bigint not null,
  account_id          bigint not null references crm_account(id) on delete restrict,
  primary_contact_id  bigint null references crm_contact(id) on delete set null,
  opportunity_id      bigint null references crm_opportunity(id) on delete set null,
  name                text not null,
  status              text not null default 'INQUIRY'
                      check (status in ('INQUIRY','TENTATIVE','DEFINITE','ACTUAL','TURNED_DOWN','LOST','CANCELLED')),
  event_type          text null,
  decision_date       date null,
  date_from           date not null,
  date_to             date not null,
  expected_pax        int null check (expected_pax >= 0),
  guaranteed_pax      int null check (guaranteed_pax >= 0),
  guarantee_due_date  date null,
  actual_pax          int null check (actual_pax >= 0),
  currency            text not null default 'EUR',
  owner_user_id       bigint null references app_user(id) on delete set null,
  outcome_reason_id   bigint null references crm_outcome_reason(id) on delete set null,
  outcome_note        text null,
  notes               text null,
  attrs               jsonb not null default '{}'::jsonb,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  created_by          bigint null,
  updated_by          bigint null,
  constraint event_date_range check (date_to >= date_from)
);

create index idx_event_tenant_status on event (tenant_id, status, date_from);
create index idx_event_account on event (account_id);
create index idx_event_contact on event (primary_contact_id);
create index idx_event_opportunity on event (opportunity_id);
create index idx_event_owner on event (owner_user_id);
create index idx_event_outcome_reason on event (outcome_reason_id);
create index idx_event_tentative_decision on event (decision_date) where status = 'TENTATIVE';

create table event_status_history (
  id                 bigserial primary key,
  tenant_id          bigint not null,
  event_id           bigint not null references event(id) on delete cascade,
  from_status        text null,
  to_status          text not null,
  outcome_reason_id  bigint null references crm_outcome_reason(id) on delete set null,
  note               text null,
  changed_by         bigint null references app_user(id) on delete set null,
  created_at         timestamptz not null default now()
);

create index idx_event_status_history_event on event_status_history (event_id, created_at);
create index idx_event_status_history_tenant on event_status_history (tenant_id);
create index idx_event_status_history_reason on event_status_history (outcome_reason_id);
create index idx_event_status_history_user on event_status_history (changed_by);
