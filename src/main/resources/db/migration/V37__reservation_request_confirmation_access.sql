alter table reservation_request
  add column if not exists confirmation_code text,
  add column if not exists confirmed_at timestamptz,
  add column if not exists public_access_token text,
  add column if not exists public_access_expires_at timestamptz;

create unique index if not exists uq_reservation_request_confirmation_code
  on reservation_request (tenant_id, confirmation_code)
  where confirmation_code is not null;

create unique index if not exists uq_reservation_request_public_access_token
  on reservation_request (public_access_token)
  where public_access_token is not null;
