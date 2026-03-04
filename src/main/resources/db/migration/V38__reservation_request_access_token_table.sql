create table if not exists reservation_request_access_token (
  id                     bigserial primary key,
  tenant_id              bigint not null,
  reservation_request_id bigint not null references reservation_request(id) on delete cascade,
  token                  text not null,
  expires_at             timestamptz not null,
  revoked_at             timestamptz,
  last_used_at           timestamptz,
  created_at             timestamptz not null default now()
);

create unique index if not exists uq_reservation_request_access_token_token
  on reservation_request_access_token (token);

create index if not exists idx_reservation_request_access_token_active_by_request
  on reservation_request_access_token (reservation_request_id, created_at desc)
  where revoked_at is null;

insert into reservation_request_access_token (
  tenant_id,
  reservation_request_id,
  token,
  expires_at
)
select
  rr.tenant_id,
  rr.id,
  rr.public_access_token,
  coalesce(rr.public_access_expires_at, now() + interval '2 day')
from reservation_request rr
where rr.public_access_token is not null
  and not exists (
    select 1
    from reservation_request_access_token rat
    where rat.token = rr.public_access_token
  );

drop index if exists uq_reservation_request_public_access_token;

alter table reservation_request
  drop column if exists public_access_token,
  drop column if exists public_access_expires_at;
