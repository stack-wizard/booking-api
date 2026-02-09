alter table reservation
  add column if not exists expires_at timestamptz null;

alter table allocation
  add column if not exists expires_at timestamptz null;

alter table reservation_request
  add column if not exists expires_at timestamptz null;
