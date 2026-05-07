alter table resource
  add column if not exists opera_room_id text null;

comment on column resource.opera_room_id is 'OHIP roomId for Opera room stays (check-in / room rates).';

alter table reservation
  add column if not exists opera_reservation_id bigint null;

comment on column reservation.opera_reservation_id is 'OHIP PMS reservation id after create reservation.';

alter table reservation_request
  add column if not exists opera_profile_id text null;

comment on column reservation_request.opera_profile_id is 'OHIP guest profile id from first successful check-in.';

alter table opera_hotel
  add column if not exists checkin_deposit_payment_trx_code text null;

alter table opera_hotel
  add column if not exists checkin_deposit_payment_method_code text null;

comment on column opera_hotel.checkin_deposit_payment_trx_code is 'Transaction code for postPayment when mirroring deposit at check-in.';
comment on column opera_hotel.checkin_deposit_payment_method_code is 'paymentMethod code (e.g. CA) for check-in deposit postPayment.';
