create table crm_team (
  id             bigserial primary key,
  tenant_id      bigint not null,
  name           text not null,
  parent_team_id bigint null references crm_team(id) on delete set null,
  active         boolean not null default true,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  created_by     bigint null,
  updated_by     bigint null
);
create unique index uq_crm_team_tenant_name on crm_team (tenant_id, lower(name));
create index idx_crm_team_parent on crm_team (parent_team_id);
create index idx_crm_team_tenant on crm_team (tenant_id);

create table crm_team_member (
  id          bigserial primary key,
  tenant_id   bigint not null,
  team_id     bigint not null references crm_team(id) on delete cascade,
  app_user_id bigint not null references app_user(id) on delete cascade,
  team_lead   boolean not null default false,
  created_at  timestamptz not null default now()
);
create unique index uq_crm_team_member on crm_team_member (team_id, app_user_id);
create index idx_crm_team_member_user on crm_team_member (tenant_id, app_user_id);

create table crm_custom_field_definition (
  id            bigserial primary key,
  tenant_id     bigint not null,
  entity        text not null
                check (entity in ('ACCOUNT','CONTACT','LEAD','OPPORTUNITY','EVENT')),
  field_key     text not null,
  label         text not null,
  field_type    text not null
                check (field_type in ('TEXT','NUMBER','BOOLEAN','DATE','SELECT','MULTISELECT')),
  options       jsonb not null default '[]'::jsonb,
  required      boolean not null default false,
  display_order int not null default 0,
  active        boolean not null default true,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  created_by    bigint null,
  updated_by    bigint null
);
create unique index uq_crm_cfd on crm_custom_field_definition (tenant_id, entity, field_key);
create index idx_crm_cfd_tenant on crm_custom_field_definition (tenant_id);
