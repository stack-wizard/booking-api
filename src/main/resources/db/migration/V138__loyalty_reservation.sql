-- Snapshot of a PMS reservation as seen by loyalty (CSV export today, OPERA connector later).
-- Re-imports upsert by (hotel, system, confirmation); a status change triggers (re)evaluation.

create table loyalty_reservation (
  id                  bigserial primary key,
  tenant_id           bigint not null,
  program_id          bigint not null references loyalty_program(id) on delete restrict,
  property_tenant_id  bigint not null,
  external_system     text not null check (external_system in ('OPERA_V5','OPERA_CLOUD','BOOKING_API','CSV')),
  confirmation_no     text not null,
  external_sub_id     text not null default '',
  status              text not null check (status in ('RESERVED','IN_HOUSE','CHECKED_OUT','CANCELLED','NO_SHOW')),
  arrival             date not null,
  departure           date not null,
  nights              integer not null check (nights >= 0),
  units               integer not null default 1 check (units > 0),
  room_type           text null,
  source_code         text null,
  market_code         text null,
  channel_code        text null,
  rate_code           text null,
  holder_first_name   text null,
  holder_last_name    text null,
  holder_email_norm   text null,
  holder_phone_norm   text null,
  membership_no       text null,
  exclusivity_flag    boolean not null default false,
  cancelled_at        date null,
  amount              numeric(12,2) null,
  currency            text null,
  member_id           bigint null references loyalty_member(id) on delete set null,
  match_method        text null check (match_method in ('MEMBERSHIP_NO','EMAIL','SECONDARY_EMAIL','MANUAL')),
  raw                 jsonb not null default '{}'::jsonb,
  first_seen_batch_id bigint null,
  last_seen_batch_id  bigint null,
  export_as_of        timestamptz null,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  check (departure >= arrival)
);
create unique index uq_loyalty_reservation_ext
  on loyalty_reservation (property_tenant_id, external_system, confirmation_no, external_sub_id);
create index idx_loyalty_reservation_tenant on loyalty_reservation (tenant_id, status);
create index idx_loyalty_reservation_program on loyalty_reservation (program_id);
create index idx_loyalty_reservation_member on loyalty_reservation (member_id);
create index idx_loyalty_reservation_unmatched on loyalty_reservation (tenant_id) where member_id is null;
create index idx_loyalty_reservation_email on loyalty_reservation (tenant_id, holder_email_norm);

-- Every engine decision, newest last; PENDING rows are the evaluation queue.
create table loyalty_reservation_evaluation (
  id             bigserial primary key,
  tenant_id      bigint not null,
  reservation_id bigint not null references loyalty_reservation(id) on delete cascade,
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
