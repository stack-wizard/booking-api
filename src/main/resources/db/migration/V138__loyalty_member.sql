-- A member is a crm_contact (the person) enrolled in a program. Identity keys, memberships and consents live on the
-- contact (V137); tier and counters here are a cache of the ledger.

create table loyalty_member (
  id                      bigserial primary key,
  tenant_id               bigint not null,
  program_id              bigint not null references loyalty_program(id) on delete restrict,
  contact_id              bigint not null references crm_contact(id) on delete restrict,
  member_number           text not null,
  status                  text not null default 'ACTIVE'
                          check (status in ('ACTIVE','INACTIVE','DEACTIVATED','ANONYMIZED')),
  activated_at            timestamptz not null default now(),
  wallet_added_at         timestamptz null,
  wallet_exception_reason text null,
  assigned_tier_id        bigint null references loyalty_tier(id) on delete set null,
  computed_tier_id        bigint null references loyalty_tier(id) on delete set null,
  tier_override           boolean not null default false,
  tier_valid_until        date null,
  -- nights before this date no longer count toward the tier (set when a status expires)
  tier_count_from         date null,
  last_stay_checkout      date null,
  nights_total            integer not null default 0,
  nights_current_year     integer not null default 0,
  nights_24m              integer not null default 0,
  counters_year           integer null,
  referrer_member_id      bigint null references loyalty_member(id) on delete set null,
  source                  text not null default 'ADMIN' check (source in ('IMPORT','ADMIN','WEB','BOOKING')),
  external_ref            text null,
  attrs                   jsonb not null default '{}'::jsonb,
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now()
);
create unique index uq_loyalty_member_number on loyalty_member (program_id, member_number);
create unique index uq_loyalty_member_contact on loyalty_member (program_id, contact_id);
create index idx_loyalty_member_tenant on loyalty_member (tenant_id);
create index idx_loyalty_member_contact on loyalty_member (contact_id);
create index idx_loyalty_member_assigned_tier on loyalty_member (assigned_tier_id);
create index idx_loyalty_member_computed_tier on loyalty_member (computed_tier_id);
create index idx_loyalty_member_referrer on loyalty_member (referrer_member_id);
create index idx_loyalty_member_valid_until on loyalty_member (tier_valid_until) where tier_valid_until is not null;

create table loyalty_tier_history (
  id               bigserial primary key,
  tenant_id        bigint not null,
  member_id        bigint not null references loyalty_member(id) on delete cascade,
  from_tier_id     bigint null references loyalty_tier(id) on delete set null,
  to_tier_id       bigint null references loyalty_tier(id) on delete set null,
  reason           text not null check (reason in ('MIGRATION','UPGRADE','EXPIRY','ADJUSTMENT','ANNUAL_CYCLE','RECOMPUTE')),
  rule_set_id      bigint null references loyalty_rule_set(id) on delete set null,
  valid_until      date null,
  operator_user_id bigint null references app_user(id) on delete set null,
  comment          text null,
  created_at       timestamptz not null default now()
);
create index idx_loyalty_tier_history_member on loyalty_tier_history (member_id, created_at);
create index idx_loyalty_tier_history_tenant on loyalty_tier_history (tenant_id);
create index idx_loyalty_tier_history_from on loyalty_tier_history (from_tier_id);
create index idx_loyalty_tier_history_to on loyalty_tier_history (to_tier_id);
create index idx_loyalty_tier_history_rule_set on loyalty_tier_history (rule_set_id);
create index idx_loyalty_tier_history_operator on loyalty_tier_history (operator_user_id);
