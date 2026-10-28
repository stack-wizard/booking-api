-- Reservation / stay as the PMS reported it (OPERA export today, connector later, our own bookings too). Owned by the
-- platform, read by loyalty, CRM and revenue. One row per PMS reservation id (OPERA RESV_NAME_ID: sharers, splits and
-- multi-room legs are separate rows). Re-imports upsert; nothing here is business logic.

create table pms_reservation (
  id                     bigserial primary key,
  tenant_id              bigint not null,
  property_tenant_id     bigint not null,
  pms_system             text not null check (pms_system in ('OPERA_V5','OPERA_CLOUD','BOOKING_API','CSV')),
  pms_reservation_id     text not null,
  confirmation_no        text null,
  confirmation_leg_no    integer null,
  parent_pms_reservation_id text null,
  external_reference     text null,
  status                 text not null check (status in ('PROSPECT','RESERVED','IN_HOUSE','CHECKED_OUT','CANCELLED','NO_SHOW','WAITLIST')),
  status_raw             text null,
  arrival                date not null,
  departure              date not null,
  original_departure     date null,
  actual_check_in        timestamptz null,
  actual_check_out       timestamptz null,
  nights                 integer not null check (nights >= 0),
  units                  integer not null default 1 check (units > 0),
  adults                 integer null,
  children               integer null,
  booking_date           date null,
  cancelled_at           date null,
  cancellation_reason    text null,
  room_no                text null,
  room_type              text null,
  room_type_charged      text null,
  rate_code              text null,
  source_code            text null,
  market_code            text null,
  origin_code            text null,
  channel_code           text null,
  block_code             text null,
  guarantee_code         text null,
  payment_method         text null,
  is_day_use             boolean not null default false,
  is_complimentary       boolean not null default false,
  is_house_use           boolean not null default false,
  is_pseudo_room         boolean not null default false,
  is_walk_in             boolean not null default false,
  holder_first_name      text null,
  holder_last_name       text null,
  holder_email_norm      text null,
  holder_phone_norm      text null,
  holder_nationality     varchar(2) null,
  holder_pms_profile_id  text null,
  membership_type        text null,
  membership_no          text null,
  membership_level       text null,
  company_pms_profile_id text null,
  agent_pms_profile_id   text null,
  group_pms_profile_id   text null,
  rate_amount            numeric(12,2) null,
  room_revenue           numeric(14,2) null,
  fb_revenue             numeric(14,2) null,
  other_revenue          numeric(14,2) null,
  tax_total              numeric(14,2) null,
  total_revenue          numeric(14,2) null,
  room_revenue_as_payee  numeric(14,2) null,
  currency               text null,
  exchange_rate          numeric(14,6) null,
  revenue_final          boolean not null default false,
  contact_id             bigint null references crm_contact(id) on delete set null,
  match_method           text null check (match_method in ('PMS_PROFILE','MEMBERSHIP_NO','EMAIL','SECONDARY_EMAIL','PHONE','MANUAL')),
  raw                    jsonb not null default '{}'::jsonb,
  first_seen_batch_id    bigint null,
  last_seen_batch_id     bigint null,
  source_business_date   date null,
  export_as_of           timestamptz null,
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now(),
  check (departure >= arrival)
);
create unique index uq_pms_reservation_pms_id on pms_reservation (property_tenant_id, pms_system, pms_reservation_id);
create index idx_pms_reservation_tenant_status on pms_reservation (tenant_id, status);
create index idx_pms_reservation_contact on pms_reservation (contact_id);
create index idx_pms_reservation_unmatched on pms_reservation (tenant_id) where contact_id is null;
create index idx_pms_reservation_email on pms_reservation (tenant_id, holder_email_norm);
create index idx_pms_reservation_confirmation on pms_reservation (property_tenant_id, confirmation_no);
create index idx_pms_reservation_departure on pms_reservation (property_tenant_id, departure);
create index idx_pms_reservation_company on pms_reservation (tenant_id, company_pms_profile_id) where company_pms_profile_id is not null;
create index idx_pms_reservation_agent on pms_reservation (tenant_id, agent_pms_profile_id) where agent_pms_profile_id is not null;

-- Everyone linked to the reservation: holder, accompanying guests, company / agent / group profiles.
create table pms_reservation_guest (
  id             bigserial primary key,
  tenant_id      bigint not null,
  reservation_id bigint not null references pms_reservation(id) on delete cascade,
  role           text not null check (role in ('HOLDER','ACCOMPANYING','COMPANY','AGENT','GROUP','CONTACT')),
  pms_profile_id text null,
  contact_id     bigint null references crm_contact(id) on delete set null,
  account_id     bigint null references crm_account(id) on delete set null,
  first_name     text null,
  last_name      text null,
  created_at     timestamptz not null default now()
);
create index idx_pms_reservation_guest_resv on pms_reservation_guest (reservation_id);
create index idx_pms_reservation_guest_contact on pms_reservation_guest (contact_id);
create index idx_pms_reservation_guest_account on pms_reservation_guest (account_id);
create index idx_pms_reservation_guest_tenant on pms_reservation_guest (tenant_id);

-- Every loyalty engine decision for a reservation, newest last; PENDING rows are the evaluation queue.
create table loyalty_reservation_evaluation (
  id             bigserial primary key,
  tenant_id      bigint not null,
  reservation_id bigint not null references pms_reservation(id) on delete cascade,
  member_id      bigint null references loyalty_member(id) on delete set null,
  result         text not null check (result in ('PENDING','POSTED','REJECTED','REVERSED','SKIPPED')),
  reject_code    text null,
  nights_posted  integer not null default 0,
  rule_set_id    bigint null references loyalty_rule_set(id) on delete set null,
  details        jsonb not null default '{}'::jsonb,
  lease_until    timestamptz null,
  attempts       integer not null default 0,
  created_at     timestamptz not null default now(),
  processed_at   timestamptz null
);
create index idx_loyalty_eval_reservation on loyalty_reservation_evaluation (reservation_id, id);
create index idx_loyalty_eval_pending on loyalty_reservation_evaluation (created_at) where result = 'PENDING';
create index idx_loyalty_eval_tenant on loyalty_reservation_evaluation (tenant_id);
create index idx_loyalty_eval_member on loyalty_reservation_evaluation (member_id);
create index idx_loyalty_eval_rule_set on loyalty_reservation_evaluation (rule_set_id);
