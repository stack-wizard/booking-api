alter table reservation_request
  drop constraint if exists reservation_request_type_check;

alter table reservation_request
  add constraint reservation_request_type_check
    check (type in ('INTERNAL', 'EXTERNAL', 'WALKIN', 'INHOUSE'));

alter table reservation
  drop constraint if exists reservation_request_type_check;

alter table reservation
  add constraint reservation_request_type_check
    check (request_type in ('INTERNAL', 'EXTERNAL', 'WALKIN', 'INHOUSE'));
