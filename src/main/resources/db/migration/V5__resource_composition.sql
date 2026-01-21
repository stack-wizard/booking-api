create table resource_composition (
  id                 bigserial primary key,
  tenant_id           bigint not null,

  parent_resource_id  bigint not null references resource(id) on delete cascade,
  member_resource_id  bigint not null references resource(id) on delete restrict,

  qty                int not null default 1 check (qty > 0),

  attrs              jsonb not null default '{}'::jsonb,

  unique (parent_resource_id, member_resource_id)
);