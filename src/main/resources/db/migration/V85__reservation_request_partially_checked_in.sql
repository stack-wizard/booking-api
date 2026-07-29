-- Allow partial check-in when a multi-day request has only some day-lines checked in.

alter table reservation_request
  drop constraint if exists reservation_request_status_check;

alter table reservation_request
  add constraint reservation_request_status_check
    check (status in (
      'DRAFT',
      'PENDING_PAYMENT',
      'MANUAL_REVIEW',
      'FINALIZED',
      'PARTIALLY_CHECKED_IN',
      'CHECKED_IN',
      'CHECKED_OUT',
      'CANCELLED',
      'EXPIRED'
    ));
