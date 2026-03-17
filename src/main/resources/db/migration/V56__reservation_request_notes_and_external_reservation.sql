alter table reservation_request
  add column if not exists notes text,
  add column if not exists external_reservation text;
