create table reservation_request (
  id          bigserial primary key,
  tenant_id   bigint not null,
  type        text not null default 'EXTERNAL'
              check (type in ('INTERNAL','EXTERNAL')),
  created_at  timestamptz not null default now()
);

alter table reservation
  add column if not exists request_id bigint null references reservation_request(id) on delete restrict,
  add column if not exists request_type text not null default 'EXTERNAL'
    check (request_type in ('INTERNAL','EXTERNAL'));
