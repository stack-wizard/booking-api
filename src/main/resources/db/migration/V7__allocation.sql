create table allocation (
  id               bigserial primary key,
  tenant_id         bigint not null,

  reservation_id    bigint not null references reservation(id) on delete cascade,

  requested_resource_id bigint not null references resource(id) on delete restrict,
  allocated_resource_id bigint not null references resource(id) on delete restrict,

  resource_kind  text not null check (resource_kind in ('POOL','EXACT')),
  composit_resource boolean not null default false,
  composit_resource_id bigint null,

  starts_at         timestamptz not null,
  ends_at           timestamptz not null check (ends_at > starts_at),

  status            text not null default 'HOLD'
                   check (status in ('HOLD','CONFIRMED','CANCELLED')),

  booked_range      tstzrange generated always as (tstzrange(starts_at, ends_at, '[)')) stored,

  attrs             jsonb not null default '{}'::jsonb,
  created_at        timestamptz not null default now()
);
