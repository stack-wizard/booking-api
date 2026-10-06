-- Typed links between accounts; the group hierarchy stays on crm_account.parent_account_id.
create table crm_account_relation (
  id              bigserial primary key,
  tenant_id       bigint not null,
  from_account_id bigint not null references crm_account(id) on delete cascade,
  to_account_id   bigint not null references crm_account(id) on delete cascade,
  relation_type   text not null check (relation_type in ('AGENCY_FOR','PARTNER','SUPPLIER','OTHER')),
  note            text null,
  created_by      bigint null references app_user(id) on delete set null,
  created_at      timestamptz not null default now(),
  check (from_account_id <> to_account_id)
);
create unique index uq_crm_account_relation on crm_account_relation (from_account_id, to_account_id, relation_type);
create index idx_crm_account_relation_tenant on crm_account_relation (tenant_id);
create index idx_crm_account_relation_to on crm_account_relation (to_account_id);
create index idx_crm_account_relation_created_by on crm_account_relation (created_by);

-- Deal booked through an agency on behalf of the client account.
alter table crm_opportunity
  add column agency_account_id bigint null references crm_account(id) on delete set null;
create index idx_crm_opp_agency on crm_opportunity (agency_account_id);
