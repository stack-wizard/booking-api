-- Banquet event order: versioned snapshot of functions and items; the PDF is rendered from the snapshot.

create table event_order (
  id          bigserial primary key,
  tenant_id   bigint not null,
  event_id    bigint not null references event(id) on delete cascade,
  version     int not null check (version > 0),
  status      text not null default 'ISSUED' check (status in ('ISSUED','SUPERSEDED')),
  snapshot    jsonb not null,
  note        text null,
  issued_by   bigint null references app_user(id) on delete set null,
  created_at  timestamptz not null default now()
);

create unique index uq_event_order_version on event_order (event_id, version);
create index idx_event_order_tenant on event_order (tenant_id);
create index idx_event_order_user on event_order (issued_by);
