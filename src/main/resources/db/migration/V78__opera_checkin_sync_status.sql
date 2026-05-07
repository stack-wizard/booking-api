alter table reservation
  add column if not exists opera_check_in_status text null;
alter table reservation
  add column if not exists opera_check_in_error text null;
alter table reservation
  add column if not exists opera_check_in_at timestamptz null;

comment on column reservation.opera_check_in_status is 'OHIP: RESERVATION_CREATED | CHECKIN_COMPLETE | CHECKIN_FAILED';
comment on column reservation.opera_check_in_error is 'Last OHIP check-in error (for retry)';
comment on column reservation.opera_check_in_at is 'When OHIP check-in last succeeded';

alter table reservation_request
  add column if not exists opera_deposit_post_status text null;
alter table reservation_request
  add column if not exists opera_deposit_post_at timestamptz null;
alter table reservation_request
  add column if not exists opera_deposit_post_error text null;

comment on column reservation_request.opera_deposit_post_status is 'OHIP deposit payment: SKIPPED | POSTED | FAILED';
comment on column reservation_request.opera_deposit_post_error is 'Last deposit postPayment error (for retry)';
