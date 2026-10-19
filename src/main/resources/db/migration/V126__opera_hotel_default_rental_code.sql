-- Default function-space rental code for OPERA Simple Events (not a room rate plan).
-- CUSTOM is the usual code when the amount is supplied by the caller.
alter table opera_hotel
  add column if not exists default_rental_code text null;
