alter table price_profile
  add column reservation_request_type text null;

alter table price_profile
  add constraint price_profile_reservation_request_type_check
    check (reservation_request_type is null or reservation_request_type in ('INTERNAL', 'EXTERNAL', 'WALKIN', 'INHOUSE'));

alter table reservation
  add column uom text null;

create index if not exists idx_price_profile_reservation_request_type
  on price_profile(reservation_request_type);
