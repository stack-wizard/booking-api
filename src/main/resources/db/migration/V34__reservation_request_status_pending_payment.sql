alter table reservation_request
  drop constraint if exists reservation_request_status_check;

alter table reservation_request
  add constraint reservation_request_status_check
    check (status in ('DRAFT','PENDING_PAYMENT','FINALIZED','CANCELLED'));
