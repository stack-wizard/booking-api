create table crm_account (
  id                bigserial primary key,
  tenant_id         bigint not null,
  name              text not null,
  legal_name        text null,
  account_type      text not null default 'COMPANY'
                    check (account_type in ('COMPANY','AGENCY','ASSOCIATION','PERSON')),
  segment           text null,
  vat_id            text null,
  parent_account_id bigint null references crm_account(id) on delete set null,
  owner_user_id     bigint null references app_user(id) on delete set null,
  team_id           bigint null references crm_team(id) on delete set null,
  email             text null,
  phone             text null,
  website           text null,
  address_line      text null,
  city              text null,
  postal_code       text null,
  country           varchar(2) null,
  active            boolean not null default true,
  attrs             jsonb not null default '{}'::jsonb,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  created_by        bigint null,
  updated_by        bigint null
);
create index idx_crm_account_tenant on crm_account (tenant_id);
create index idx_crm_account_owner on crm_account (tenant_id, owner_user_id);
create index idx_crm_account_parent on crm_account (parent_account_id);
create unique index uq_crm_account_vat
  on crm_account (tenant_id, vat_id) where vat_id is not null;

create table crm_contact (
  id               bigserial primary key,
  tenant_id        bigint not null,
  account_id       bigint null references crm_account(id) on delete set null,
  first_name       text not null,
  last_name        text not null,
  email            text null,
  phone            text null,
  job_title        text null,
  platform_user_id uuid null,
  active           boolean not null default true,
  attrs            jsonb not null default '{}'::jsonb,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  created_by       bigint null,
  updated_by       bigint null
);
create index idx_crm_contact_tenant on crm_contact (tenant_id);
create index idx_crm_contact_account on crm_contact (account_id);
create unique index uq_crm_contact_email
  on crm_contact (tenant_id, lower(email)) where email is not null;

create table crm_account_contact_role (
  id         bigserial primary key,
  tenant_id  bigint not null,
  account_id bigint not null references crm_account(id) on delete cascade,
  contact_id bigint not null references crm_contact(id) on delete cascade,
  role       text not null
             check (role in ('DECISION_MAKER','BILLING','ON_SITE','TECHNICAL','OTHER')),
  primary_contact boolean not null default false,
  created_at timestamptz not null default now()
);
create unique index uq_crm_acr on crm_account_contact_role (account_id, contact_id, role);
create index idx_crm_acr_tenant on crm_account_contact_role (tenant_id);
