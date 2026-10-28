-- Loyalty program configuration. Lives on the ORG tenant: one profile and one night count across all hotels.
-- Every rule a client may want to change is data (versioned rule sets), never code.

create table loyalty_program (
  id                   bigserial primary key,
  tenant_id            bigint not null,
  code                 text not null,
  name                 text not null,
  accrual_unit         text not null default 'NIGHT' check (accrual_unit in ('NIGHT','POINTS')),
  member_number_prefix text null,
  member_number_next   bigint not null default 1 check (member_number_next > 0),
  anonymous_pricing    text not null default 'NONE'
                       check (anonymous_pricing in ('NONE','SHOW_ENTRY_TIER','APPLY_ENTRY_TIER_ON_JOIN')),
  active               boolean not null default true,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now()
);
create unique index uq_loyalty_program_code on loyalty_program (tenant_id, lower(code));

-- A stay is evaluated with the rule set valid on its arrival date; old stays keep old rules.
create table loyalty_rule_set (
  id             bigserial primary key,
  tenant_id      bigint not null,
  program_id     bigint not null references loyalty_program(id) on delete cascade,
  version        integer not null check (version > 0),
  effective_from date not null,
  effective_to   date null,
  tier_policy    text not null default 'ROLLING_VALIDITY' check (tier_policy in ('ROLLING_VALIDITY','ANNUAL_CYCLE')),
  params         jsonb not null default '{}'::jsonb,
  status         text not null default 'DRAFT' check (status in ('DRAFT','ACTIVE','RETIRED')),
  note           text null,
  created_at     timestamptz not null default now(),
  check (effective_to is null or effective_to > effective_from)
);
create unique index uq_loyalty_rule_set_version on loyalty_rule_set (program_id, version);
create index idx_loyalty_rule_set_tenant on loyalty_rule_set (tenant_id);
alter table loyalty_rule_set
  add constraint ex_loyalty_rule_set_active_overlap
  exclude using gist (program_id with =, daterange(effective_from, effective_to, '[)') with &&)
  where (status = 'ACTIVE');

create table loyalty_tier (
  id          bigserial primary key,
  tenant_id   bigint not null,
  rule_set_id bigint not null references loyalty_rule_set(id) on delete cascade,
  code        text not null,
  name        text not null,
  rank        integer not null check (rank >= 0),
  threshold   integer not null check (threshold >= 0),
  display     jsonb not null default '{}'::jsonb,
  created_at  timestamptz not null default now()
);
create unique index uq_loyalty_tier_code on loyalty_tier (rule_set_id, upper(code));
create unique index uq_loyalty_tier_rank on loyalty_tier (rule_set_id, rank);
create index idx_loyalty_tier_tenant on loyalty_tier (tenant_id);

-- Ordered qualification pipeline; the first failing step rejects the stay with its reject_code.
create table loyalty_qualification_step (
  id          bigserial primary key,
  tenant_id   bigint not null,
  rule_set_id bigint not null references loyalty_rule_set(id) on delete cascade,
  step_order  integer not null,
  rule_code   text not null check (rule_code in ('MEMBER_MATCH','CHANNEL','HOLDER','ACTIVATION','WALLET',
                                                  'STAY_COMPLETED','EXCLUSIVITY_FLAG','UNIQUE_PER_DATE')),
  enabled     boolean not null default true,
  params      jsonb not null default '{}'::jsonb,
  reject_code text not null,
  created_at  timestamptz not null default now()
);
create unique index uq_loyalty_step_order on loyalty_qualification_step (rule_set_id, step_order);
create unique index uq_loyalty_step_rule on loyalty_qualification_step (rule_set_id, rule_code);
create index idx_loyalty_step_tenant on loyalty_qualification_step (tenant_id);

-- Which PMS source/market/channel codes count as direct (qualifying) bookings; null property = every hotel.
create table loyalty_channel_map (
  id                 bigserial primary key,
  tenant_id          bigint not null,
  program_id         bigint not null references loyalty_program(id) on delete cascade,
  property_tenant_id bigint null,
  code_type          text not null check (code_type in ('SOURCE','MARKET','ORIGIN','CHANNEL')),
  code               text not null,
  qualifying         boolean not null,
  created_at         timestamptz not null default now()
);
create unique index uq_loyalty_channel_map
  on loyalty_channel_map (program_id, coalesce(property_tenant_id, 0), code_type, upper(code));
create index idx_loyalty_channel_map_tenant on loyalty_channel_map (tenant_id);
