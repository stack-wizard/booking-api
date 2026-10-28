-- One person table for B2B contacts, PMS guests and loyalty members (chain level). Identity keys, memberships and
-- consents live here, so reservation matching, deduplication and GDPR work the same for every module.

alter table crm_contact
  add column kind                    text not null default 'B2B' check (kind in ('B2B','GUEST')),
  add column source                  text not null default 'MANUAL' check (source in ('MANUAL','OPERA','IMPORT','WEB','BOOKING')),
  add column nationality             varchar(2) null,
  add column country                 varchar(2) null,
  add column city                    text null,
  add column postal_code             text null,
  add column address_line            text null,
  add column date_of_birth           date null,
  add column gender                  text null,
  add column language                text null,
  add column vip_code                text null,
  add column merged_into_id          bigint null references crm_contact(id) on delete set null,
  -- statistics the PMS reported for the profile (opening state), dated
  add column pms_opening_stays       integer not null default 0,
  add column pms_opening_nights      integer not null default 0,
  add column pms_opening_revenue     numeric(14,2) not null default 0,
  add column pms_opening_as_of       date null,
  add column pms_stats               jsonb not null default '{}'::jsonb,
  -- projection: opening + pms_reservation after the opening date (CrmGuestStatsService)
  add column stays_total             integer not null default 0,
  add column nights_total            integer not null default 0,
  add column revenue_total           numeric(14,2) not null default 0,
  add column room_revenue_total      numeric(14,2) not null default 0,
  add column first_stay              date null,
  add column last_stay               date null,
  add column next_arrival            date null,
  add column favorite_property_tenant_id bigint null,
  add column stats_updated_at        timestamptz null;
create index idx_crm_contact_kind on crm_contact (tenant_id, kind);
create index idx_crm_contact_merged_into on crm_contact (merged_into_id);
create index idx_crm_contact_last_stay on crm_contact (tenant_id, last_stay);
create unique index uq_crm_contact_opera_profile on crm_contact (tenant_id, opera_profile_id) where opera_profile_id is not null;

-- Keys that identify a person in incoming data: primary e-mail, secondary e-mails, phones, membership cards, PMS profile ids.
create table crm_contact_identifier (
  id         bigserial primary key,
  tenant_id  bigint not null,
  contact_id bigint not null references crm_contact(id) on delete cascade,
  type       text not null check (type in ('EMAIL','PHONE','CARD','OPERA_PROFILE')),
  value_norm text not null,
  is_primary boolean not null default false,
  source     text null,
  created_at timestamptz not null default now()
);
create unique index uq_crm_contact_identifier on crm_contact_identifier (tenant_id, type, value_norm);
create index idx_crm_contact_identifier_contact on crm_contact_identifier (contact_id);

-- Every programme card a person holds (ours and others', e.g. Radisson Rewards as reported by the PMS).
create table crm_contact_membership (
  id           bigserial primary key,
  tenant_id    bigint not null,
  contact_id   bigint not null references crm_contact(id) on delete cascade,
  program_code text not null,
  card_no      text not null,
  level        text null,
  is_primary   boolean not null default false,
  expires_at   date null,
  source       text not null default 'OPERA' check (source in ('OPERA','LOYALTY','IMPORT','MANUAL')),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create unique index uq_crm_contact_membership on crm_contact_membership (tenant_id, upper(program_code), card_no);
create index idx_crm_contact_membership_contact on crm_contact_membership (contact_id);

create table crm_contact_consent (
  id         bigserial primary key,
  tenant_id  bigint not null,
  contact_id bigint not null references crm_contact(id) on delete cascade,
  channel    text not null check (channel in ('EMAIL','SMS','PUSH')),
  granted    boolean not null,
  changed_at timestamptz not null default now(),
  source     text null,
  created_at timestamptz not null default now()
);
create unique index uq_crm_contact_consent on crm_contact_consent (contact_id, channel);
create index idx_crm_contact_consent_tenant on crm_contact_consent (tenant_id);

create table crm_contact_merge_log (
  id               bigserial primary key,
  tenant_id        bigint not null,
  from_contact_id  bigint not null references crm_contact(id) on delete cascade,
  into_contact_id  bigint not null references crm_contact(id) on delete cascade,
  reason           text null,
  operator_user_id bigint null references app_user(id) on delete set null,
  created_at       timestamptz not null default now()
);
create index idx_crm_contact_merge_from on crm_contact_merge_log (from_contact_id);
create index idx_crm_contact_merge_into on crm_contact_merge_log (into_contact_id);
create index idx_crm_contact_merge_tenant on crm_contact_merge_log (tenant_id);
create index idx_crm_contact_merge_operator on crm_contact_merge_log (operator_user_id);

-- Account production (B2B): what a company / agency / group produced per year and hotel, from pms_reservation.
create table crm_account_production (
  id                 bigserial primary key,
  tenant_id          bigint not null,
  account_id         bigint not null references crm_account(id) on delete cascade,
  year               integer not null,
  property_tenant_id bigint not null,
  role               text not null check (role in ('COMPANY','AGENT','GROUP')),
  stays              integer not null default 0,
  nights             integer not null default 0,
  room_revenue       numeric(14,2) not null default 0,
  fb_revenue         numeric(14,2) not null default 0,
  other_revenue      numeric(14,2) not null default 0,
  total_revenue      numeric(14,2) not null default 0,
  updated_at         timestamptz not null default now(),
  created_at         timestamptz not null default now()
);
create unique index uq_crm_account_production on crm_account_production (account_id, year, property_tenant_id, role);
create index idx_crm_account_production_tenant on crm_account_production (tenant_id, year);
